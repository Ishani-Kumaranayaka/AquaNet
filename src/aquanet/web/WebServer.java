package aquanet.web;

import aquanet.common.NetworkController;
import aquanet.common.NetworkSnapshot;
import aquanet.common.TankInfo;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AquaNet web front-end: a tiny HTTP server built on the JDK's own
 * com.sun.net.httpserver (no external libraries).
 *
 * <h3>HTTP in one paragraph (viva)</h3>
 * HTTP is a text protocol on top of TCP. The browser opens a TCP connection to
 * port 8080 and sends a REQUEST: a request line ("GET /api/state HTTP/1.1"),
 * headers, a blank line and an optional body. We answer with a RESPONSE: a status
 * line ("HTTP/1.1 200 OK"), headers (Content-Type, Content-Length...), blank line
 * and the body. Each route below is one handler that reads the request and writes
 * the response.
 *
 * <h3>Routes</h3>
 * <pre>
 *  GET  /                -> web/index.html (any other path = static file from webRoot)
 *  GET  /api/state       -> current snapshot as JSON
 *  GET  /api/history     -> last 120 snapshots' tank percentages (JSON array)
 *  GET  /api/alerts      -> last 50 alerts (JSON array)
 *  POST /api/command     -> body "PUMP|P1|ON" (text/plain) -> {"ok":..,"message":..}
 *  GET  /events          -> Server-Sent Events stream ("state" and "alert" events)
 * </pre>
 *
 * <h3>SSE vs WebSocket (viva)</h3>
 * Server-Sent Events are a plain long-lived HTTP response with Content-Type
 * text/event-stream; the server keeps writing "event: x / data: y" blocks and the
 * browser's EventSource parses them and reconnects automatically. It is one-way
 * (server to browser), needs no handshake/upgrade and no extra library. A WebSocket
 * is a full-duplex protocol that upgrades the HTTP connection; it is more powerful
 * but needs a frame encoder/decoder. Our browser only receives data (commands go
 * through ordinary POST requests), so SSE is the simplest correct tool.
 *
 * <h3>Thread safety (viva)</h3>
 * Three kinds of threads touch this class: (1) HTTP worker threads from the
 * executor (one per request, an SSE stream occupies one for its lifetime),
 * (2) the simulation thread that calls our state/event listeners every tick, and
 * (3) the thread that calls start()/stop(). Rules used:
 * the snapshot is immutable; history/alerts are ring buffers guarded by
 * synchronized blocks; the list of SSE clients is a CopyOnWriteArrayList; the
 * simulation thread never writes to a socket - it only offers a string to each
 * client's BlockingQueue (non-blocking), and the client's own worker thread does
 * the (possibly slow) network write. A slow browser therefore cannot stall the
 * simulation.
 */
public class WebServer {
    private static final int HISTORY_SIZE = 120;
    private static final int ALERT_SIZE = 50;
    private static final int KEEPALIVE_SECONDS = 15;
    private static final int QUEUE_LIMIT = 200;

    private final NetworkController controller;
    private final int port;
    private final Path webRoot;

    private HttpServer http;
    private ExecutorService pool;
    private volatile boolean running;

    /** One connected browser on /events. */
    private static final class SseClient {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<String>();
        volatile boolean dead;
    }
    private final CopyOnWriteArrayList<SseClient> clients = new CopyOnWriteArrayList<SseClient>();

    // ring buffers (guarded by their own monitor)
    private final ArrayDeque<String> history = new ArrayDeque<String>();
    private final ArrayDeque<String> alerts = new ArrayDeque<String>();
    private final Object historyLock = new Object();
    /** Accounts + sessions (users.txt is created in the "data" folder next to where the server is started). */
    private final AuthService auth = new AuthService(java.nio.file.Paths.get("data", "users.txt"));
    /** User problem reports and the admin audit trail. */
    private final ReportBoard board = new ReportBoard();
    private final Object alertLock = new Object();

    private static final Map<String, String> TYPES = new HashMap<String, String>();
    static {
        TYPES.put("html", "text/html; charset=utf-8");
        TYPES.put("css", "text/css; charset=utf-8");
        TYPES.put("js", "application/javascript; charset=utf-8");
        TYPES.put("json", "application/json; charset=utf-8");
        TYPES.put("svg", "image/svg+xml");
        TYPES.put("png", "image/png");
        TYPES.put("jpg", "image/jpeg");
        TYPES.put("ico", "image/x-icon");
        TYPES.put("txt", "text/plain; charset=utf-8");
    }

    public WebServer(NetworkController controller, int port, String webRootDir) {
        this.controller = controller;
        this.port = port;
        this.webRoot = Paths.get(webRootDir).toAbsolutePath().normalize();
    }

    /** Create the HTTP server, register routes and start accepting connections. */
    public void start() {
        try {
            http = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new RuntimeException("Cannot start web server on port " + port + ": " + e.getMessage(), e);
        }
        final AtomicInteger n = new AtomicInteger();
        pool = Executors.newCachedThreadPool(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "web-worker-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        http.setExecutor(pool);

        // Longest-prefix matching: "/" catches everything not matched by a more specific context.
        http.createContext("/", ex -> guarded(ex, this::handleStatic));
        http.createContext("/api/login", ex -> guarded(ex, this::handleLogin));
        http.createContext("/api/register", ex -> guarded(ex, this::handleRegister));
        http.createContext("/api/logout", ex -> guarded(ex, this::handleLogout));
        http.createContext("/api/me", ex -> guarded(ex, this::handleMe));
        http.createContext("/api/report", ex -> guarded(ex, this::handleReport));
        http.createContext("/api/reports", ex -> guarded(ex, this::handleReports));
        http.createContext("/api/users", ex -> guarded(ex, this::handleUsers));
        http.createContext("/api/audit", ex -> guarded(ex, this::handleAudit));
        http.createContext("/api/state", ex -> guarded(ex, this::handleState));
        http.createContext("/api/history", ex -> guarded(ex, this::handleHistory));
        http.createContext("/api/alerts", ex -> guarded(ex, this::handleAlerts));
        http.createContext("/api/command", ex -> guarded(ex, this::handleCommand));
        http.createContext("/events", ex -> guarded(ex, this::handleEvents));

        // seed history and subscribe to the simulation
        recordHistory(controller.snapshot());
        controller.addStateListener(this::onState);
        controller.addEventListener(this::onAlert);

        running = true;
        http.start();
        log("listening on http://localhost:" + port + "/  (root: " + webRoot + ")");
    }

    /** Stop accepting requests and close all SSE streams. */
    public void stop() {
        running = false;
        for (SseClient c : clients) { c.dead = true; c.queue.offer(""); }
        clients.clear();
        if (http != null) http.stop(0);
        if (pool != null) pool.shutdownNow();
        log("stopped");
    }

    // ------------------------------------------------------------------ listeners

    /** Called by the simulation thread once per tick. Must be fast and never block. */
    private void onState(NetworkSnapshot s) {
        recordHistory(s);
        broadcast("event: state\ndata: " + s.toJson() + "\n\n");
    }

    private void onAlert(String text) {
        String json = alertJson(text);
        synchronized (alertLock) {
            alerts.addLast(json);
            while (alerts.size() > ALERT_SIZE) alerts.removeFirst();
        }
        broadcast("event: alert\ndata: " + json + "\n\n");
    }

    private void recordHistory(NetworkSnapshot s) {
        StringBuilder sb = new StringBuilder("{\"tick\":").append(s.tick).append(",\"tanks\":{");
        boolean first = true;
        for (TankInfo t : s.tanks) {
            if (!first) sb.append(',');
            first = false;
            sb.append(NetworkSnapshot.jsonQuote(t.id)).append(':')
              .append(String.format(java.util.Locale.ROOT, "%.2f", t.percent()));
        }
        sb.append("}}");
        synchronized (historyLock) {
            history.addLast(sb.toString());
            while (history.size() > HISTORY_SIZE) history.removeFirst();
        }
    }

    /** "ALERT|LEVEL|message" -> {"time":"HH:mm:ss","level":"..","message":".."} */
    private static String alertJson(String text) {
        String level = "INFO", msg = text;
        String[] p = text.split("\\|", 3);
        if (p.length == 3 && p[0].equals("ALERT")) { level = p[1]; msg = p[2]; }
        else if (p.length == 2 && p[0].equals("ALERT")) { msg = p[1]; }
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
        return "{\"time\":" + NetworkSnapshot.jsonQuote(time) + ",\"level\":" + NetworkSnapshot.jsonQuote(level)
                + ",\"message\":" + NetworkSnapshot.jsonQuote(msg) + "}";
    }

    private void broadcast(String frame) {
        for (SseClient c : clients) {
            if (c.dead) { clients.remove(c); continue; }
            if (c.queue.size() > QUEUE_LIMIT) { c.dead = true; clients.remove(c); c.queue.offer(""); continue; }
            c.queue.offer(frame);
        }
    }

    // ------------------------------------------------------------------ handlers

    private interface Handler { void handle(HttpExchange ex) throws Exception; }

    /** Wraps every handler: logs the request, converts exceptions to 500 and always closes the exchange. */
    private void guarded(HttpExchange ex, Handler h) {
        long t0 = System.currentTimeMillis();
        try {
            h.handle(ex);
        } catch (Exception e) {
            if (!(e instanceof IOException)) {
                log("error " + ex.getRequestMethod() + " " + ex.getRequestURI() + ": " + e);
                try { sendText(ex, 500, "text/plain; charset=utf-8", "Internal server error"); } catch (Exception ignore) { }
            }
        } finally {
            if (!ex.getRequestURI().getPath().equals("/events")) {
                log(ex.getRemoteAddress().getAddress().getHostAddress() + " " + ex.getRequestMethod() + " "
                        + ex.getRequestURI().getPath() + " -> " + ex.getResponseCode() + " ("
                        + (System.currentTimeMillis() - t0) + " ms)");
            }
            ex.close();
        }
    }

    private void handleState(HttpExchange ex) throws IOException {
        if (!method(ex, "GET", "HEAD")) return;
        if (require(ex, false) == null) return;
        sendText(ex, 200, "application/json; charset=utf-8", controller.snapshot().toJson());
    }

    private void handleHistory(HttpExchange ex) throws IOException {
        if (!method(ex, "GET", "HEAD")) return;
        if (require(ex, false) == null) return;
        StringBuilder sb = new StringBuilder("[");
        synchronized (historyLock) {
            boolean first = true;
            for (String s : history) { if (!first) sb.append(','); first = false; sb.append(s); }
        }
        sendText(ex, 200, "application/json; charset=utf-8", sb.append(']').toString());
    }

    private void handleAlerts(HttpExchange ex) throws IOException {
        if (!method(ex, "GET", "HEAD")) return;
        if (require(ex, false) == null) return;
        StringBuilder sb = new StringBuilder("[");
        synchronized (alertLock) {
            boolean first = true;
            for (String s : alerts) { if (!first) sb.append(','); first = false; sb.append(s); }
        }
        sendText(ex, 200, "application/json; charset=utf-8", sb.append(']').toString());
    }

    /** POST body "PUMP|P1|ON" -> controller.execute("web", body) -> {"ok":bool,"message":".."} */
    private void handleCommand(HttpExchange ex) throws IOException {
        if (!method(ex, "POST")) return;
        AuthService.Session who = require(ex, true);          // only ADMIN may control the network
        if (who == null) return;
        String body = new String(readBody(ex.getRequestBody(), 1024), StandardCharsets.UTF_8).trim();
        if (body.isEmpty()) {
            sendText(ex, 400, "application/json; charset=utf-8", "{\"ok\":false,\"message\":\"empty command\"}");
            return;
        }
        String reply = controller.execute("web:" + who.user, body);
        board.audit(who.user, "command " + body + " -> " + (reply == null ? "no reply" : reply));   // "OK|msg" or "ERR|msg"
        boolean ok = reply != null && reply.startsWith("OK");
        String msg = reply == null ? "no reply" : (reply.indexOf('|') >= 0 ? reply.substring(reply.indexOf('|') + 1) : reply);
        log("command from web: " + body + " -> " + reply);
        sendText(ex, 200, "application/json; charset=utf-8",
                "{\"ok\":" + ok + ",\"message\":" + NetworkSnapshot.jsonQuote(msg) + "}");
    }

    /**
     * Server-Sent Events. The response is never "finished": headers are sent with
     * length 0 (= chunked transfer), then this worker thread loops forever taking
     * ready-made frames from the client's queue and writing them. If nothing happens
     * for 15 s a comment line (": keep-alive") is written; it is ignored by the
     * browser but makes a dead TCP connection fail fast (IOException -> cleanup).
     */
    private void handleEvents(HttpExchange ex) throws IOException {
        if (!method(ex, "GET")) return;
        if (require(ex, false) == null) return;
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "text/event-stream; charset=utf-8");
        h.set("Cache-Control", "no-cache");
        h.set("Connection", "keep-alive");
        h.set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);

        SseClient c = new SseClient();
        clients.add(c);
        log("SSE client connected (" + clients.size() + " total) " + ex.getRemoteAddress());
        OutputStream out = ex.getResponseBody();
        try {
            write(out, "retry: 2000\n\n");
            write(out, "event: state\ndata: " + controller.snapshot().toJson() + "\n\n");  // immediate first picture
            while (running && !c.dead) {
                String frame = c.queue.poll(KEEPALIVE_SECONDS, TimeUnit.SECONDS);
                if (c.dead) break;
                if (frame == null) frame = ": keep-alive\n\n";
                if (frame.isEmpty()) continue;
                write(out, frame);
            }
        } catch (IOException e) {
            // browser closed the tab / network died - normal
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            c.dead = true;
            clients.remove(c);
            log("SSE client left (" + clients.size() + " remaining)");
        }
    }

    private static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Static files with path-traversal protection. */
    private void handleStatic(HttpExchange ex) throws IOException {
        if (!method(ex, "GET", "HEAD")) return;
        String path = ex.getRequestURI().getPath();          // already percent-decoded
        if (path == null || path.isEmpty() || path.equals("/") || path.equals("/index.html")) {
            // home: send people to the right place for their role
            AuthService.Session me = currentSession(ex);
            redirect(ex, me == null ? "/login.html" : me.isAdmin() ? "/admin.html" : "/user.html");
            return;
        }
        if (path.equals("/user.html") || path.equals("/admin.html")) {
            AuthService.Session me = currentSession(ex);
            if (me == null) { redirect(ex, "/login.html"); return; }
            if (path.equals("/admin.html") && !me.isAdmin()) { redirect(ex, "/user.html"); return; }
        }
        // 1) reject suspicious input outright
        if (path.contains("..") || path.indexOf('\0') >= 0 || path.indexOf('\\') >= 0) {
            sendText(ex, 403, "text/plain; charset=utf-8", "Forbidden");
            return;
        }
        // 2) resolve and double-check the result is still inside the web root
        Path file = webRoot.resolve(path.substring(1)).normalize();
        if (!file.startsWith(webRoot)) {
            sendText(ex, 403, "text/plain; charset=utf-8", "Forbidden");
            return;
        }
        if (!Files.isRegularFile(file)) {
            sendText(ex, 404, "text/plain; charset=utf-8", "404 Not Found: " + path);
            return;
        }
        String name = file.getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        String type = TYPES.containsKey(ext) ? TYPES.get(ext) : "application/octet-stream";
        byte[] data = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        sendBytes(ex, 200, data);
    }


    // ------------------------------------------------------------------ accounts, roles, reports

    private static final String JSON = "application/json; charset=utf-8";

    private static String jsonMsg(boolean ok, String msg) {
        return "{\"ok\":" + ok + ",\"message\":" + NetworkSnapshot.jsonQuote(msg) + "}";
    }

    private static String bodyText(HttpExchange ex, int max) throws IOException {
        return new String(readBody(ex.getRequestBody(), max), StandardCharsets.UTF_8).trim();
    }

    /** Session cookie value ("aqsid=...") or null. */
    private static String tokenOf(HttpExchange ex) {
        java.util.List<String> cookies = ex.getRequestHeaders().get("Cookie");
        if (cookies == null) return null;
        for (String line : cookies)
            for (String part : line.split(";")) {
                String c = part.trim();
                if (c.startsWith("aqsid=")) return c.substring(6);
            }
        return null;
    }

    private AuthService.Session currentSession(HttpExchange ex) { return auth.session(tokenOf(ex)); }

    /**
     * Gatekeeper used by every protected route. Answers 401 (not logged in) or 403 (wrong role)
     * and returns null, otherwise returns the session.
     */
    private AuthService.Session require(HttpExchange ex, boolean adminOnly) throws IOException {
        AuthService.Session s = currentSession(ex);
        if (s == null) { sendText(ex, 401, JSON, jsonMsg(false, "Please log in")); return null; }
        if (adminOnly && !s.isAdmin()) { sendText(ex, 403, JSON, jsonMsg(false, "Administrator access required")); return null; }
        return s;
    }

    private static void redirect(HttpExchange ex, String to) throws IOException {
        ex.getResponseHeaders().set("Location", to);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(302, -1);
    }

    /** POST body "username|password". On success sets the HttpOnly session cookie. */
    private void handleLogin(HttpExchange ex) throws IOException {
        if (!method(ex, "POST")) return;
        String[] p = bodyText(ex, 300).split("\\|", 2);
        String[] err = new String[1];
        AuthService.Session s = p.length == 2 ? auth.login(p[0], p[1], err) : null;
        if (s == null) {
            if (p.length < 2) err[0] = "Enter username and password";
            board.audit(p[0], "failed login");
            sendText(ex, 401, JSON, jsonMsg(false, err[0]));
            return;
        }
        ex.getResponseHeaders().add("Set-Cookie", "aqsid=" + s.token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=28800");
        board.audit(s.user, "logged in (" + s.role + ")");
        sendText(ex, 200, JSON, "{\"ok\":true,\"user\":" + NetworkSnapshot.jsonQuote(s.user)
                + ",\"role\":" + NetworkSnapshot.jsonQuote(s.role) + "}");
    }

    /** POST body "username|password": creates a normal USER account. */
    private void handleRegister(HttpExchange ex) throws IOException {
        if (!method(ex, "POST")) return;
        String[] p = bodyText(ex, 300).split("\\|", 2);
        String err = p.length < 2 ? "Enter username and password" : auth.register(p[0], p[1]);
        if (err != null) { sendText(ex, 400, JSON, jsonMsg(false, err)); return; }
        board.audit(p[0], "registered a new account");
        sendText(ex, 200, JSON, jsonMsg(true, "Account created. You can log in now."));
    }

    private void handleLogout(HttpExchange ex) throws IOException {
        if (!method(ex, "POST")) return;
        AuthService.Session s = currentSession(ex);
        if (s != null) board.audit(s.user, "logged out");
        auth.logout(tokenOf(ex));
        ex.getResponseHeaders().add("Set-Cookie", "aqsid=; Path=/; HttpOnly; Max-Age=0");
        sendText(ex, 200, JSON, jsonMsg(true, "Logged out"));
    }

    private void handleMe(HttpExchange ex) throws IOException {
        if (!method(ex, "GET")) return;
        AuthService.Session s = require(ex, false);
        if (s == null) return;
        sendText(ex, 200, JSON, "{\"ok\":true,\"user\":" + NetworkSnapshot.jsonQuote(s.user)
                + ",\"role\":" + NetworkSnapshot.jsonQuote(s.role) + "}");
    }

    /** POST body "tankId|description": a user reports a problem (leak, low pressure, ...). */
    private void handleReport(HttpExchange ex) throws IOException {
        if (!method(ex, "POST")) return;
        AuthService.Session s = require(ex, false);
        if (s == null) return;
        String[] p = bodyText(ex, 600).split("\\|", 2);
        String tank = p.length == 2 ? p[0].replaceAll("[^A-Za-z0-9_-]", "") : "";
        String text = (p.length == 2 ? p[1] : p[0]).trim();
        if (text.length() < 3 || text.length() > 300) { sendText(ex, 400, JSON, jsonMsg(false, "Describe the problem in 3-300 characters")); return; }
        int id = board.add(s.user, tank, text);
        sendText(ex, 200, JSON, jsonMsg(true, "Report #" + id + " sent to the administrators"));
    }

    /**
     * GET: admin sees every report, a user sees their own.
     * POST body "id|note": admin marks a report resolved.
     */
    private void handleReports(HttpExchange ex) throws IOException {
        AuthService.Session s = require(ex, false);
        if (s == null) return;
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            if (!s.isAdmin()) { sendText(ex, 403, JSON, jsonMsg(false, "Administrator access required")); return; }
            String[] p = bodyText(ex, 400).split("\\|", 2);
            try {
                boolean ok = board.resolve(Integer.parseInt(p[0].trim()), s.user, p.length == 2 ? p[1].trim() : "");
                sendText(ex, ok ? 200 : 404, JSON, jsonMsg(ok, ok ? "Report resolved" : "No such report"));
            } catch (NumberFormatException e) {
                sendText(ex, 400, JSON, jsonMsg(false, "Bad report id"));
            }
            return;
        }
        if (!method(ex, "GET")) return;
        sendText(ex, 200, JSON, board.json(s.user, s.isAdmin()));
    }

    /**
     * Admin only. GET: list accounts. POST body "DELETE|name" or "ROLE|name|ADMIN/USER".
     */
    private void handleUsers(HttpExchange ex) throws IOException {
        AuthService.Session s = require(ex, true);
        if (s == null) return;
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            String[] p = bodyText(ex, 200).split("\\|");
            String err;
            if (p.length == 2 && p[0].equals("DELETE")) err = auth.delete(p[1], s.user);
            else if (p.length == 3 && p[0].equals("ROLE")) err = auth.setRole(p[1], p[2], s.user);
            else err = "Bad request";
            if (err == null) board.audit(s.user, "user admin: " + String.join(" ", p));
            sendText(ex, err == null ? 200 : 400, JSON, jsonMsg(err == null, err == null ? "Done" : err));
            return;
        }
        if (!method(ex, "GET")) return;
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (AuthService.Account a : auth.listUsers()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":").append(NetworkSnapshot.jsonQuote(a.name)).append(",\"role\":")
              .append(NetworkSnapshot.jsonQuote(a.role)).append('}');
        }
        sendText(ex, 200, JSON, sb.append(']').toString());
    }

    private void handleAudit(HttpExchange ex) throws IOException {
        if (!method(ex, "GET")) return;
        if (require(ex, true) == null) return;
        sendText(ex, 200, JSON, "{\"sessions\":" + auth.activeSessions() + ",\"openReports\":" + board.openCount()
                + ",\"log\":" + board.auditJson() + "}");
    }

    // ------------------------------------------------------------------ helpers

    /** Returns true if the method is allowed; otherwise answers 405 and returns false. */
    private boolean method(HttpExchange ex, String... allowed) throws IOException {
        String m = ex.getRequestMethod();
        for (String a : allowed) if (a.equalsIgnoreCase(m)) return true;
        ex.getResponseHeaders().set("Allow", String.join(", ", allowed));
        sendText(ex, 405, "text/plain; charset=utf-8", "Method Not Allowed");
        return false;
    }

    private static void sendText(HttpExchange ex, int code, String type, String body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        sendBytes(ex, code, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange ex, int code, byte[] data) throws IOException {
        if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Content-Length", Integer.toString(data.length));
            ex.sendResponseHeaders(code, -1);
            return;
        }
        ex.sendResponseHeaders(code, data.length);
        OutputStream os = ex.getResponseBody();
        os.write(data);
        os.close();
    }

    private static byte[] readBody(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[256];
        int n;
        while ((n = in.read(buf)) > 0 && bos.size() < max) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static void log(String s) {
        System.out.println("[web " + new SimpleDateFormat("HH:mm:ss").format(new Date()) + "] " + s);
    }
}
