package aquanet.client;

import aquanet.common.NetworkSnapshot;
import aquanet.common.Protocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The client's TCP layer. It owns the java.net.Socket and knows the text
 * protocol (see {@link Protocol}); it knows nothing about Swing.
 *
 * Threads involved:
 *  - A "worker" thread (created per connect() call) opens the socket and then
 *    sits in a blocking readLine() loop - this is THE READER THREAD. Every line
 *    the server pushes (STATE every tick, ALERT, ACK, ...) is parsed there and
 *    passed to the {@link ClientListener}. Blocking I/O must never run on the
 *    Swing EDT or the window would freeze, hence the separate thread.
 *    If the link drops and auto-reconnect is on, the same thread sleeps with
 *    exponential back-off (1s, 2s, 4s ... max 15s) and tries again.
 *  - A daemon scheduler thread sends PING every 5 seconds. When the matching
 *    PONG arrives (handled on the reader thread) the elapsed time is the RTT.
 *  - Any thread (normally the EDT) may call sendCommand/sendChat; writes are
 *    serialised by a lock so two lines can never be interleaved on the wire.
 *
 * A "generation" counter identifies the current connect() call. If the user
 * disconnects and reconnects, an old worker that wakes up late sees a different
 * generation and silently exits instead of reporting stale events.
 */
public class ConnectionManager {
    private static final int PING_INTERVAL_SECONDS = 5;
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int MAX_BACKOFF_SECONDS = 15;

    private final ClientListener listener;

    /** Guards socket/out; all sends synchronise on it. */
    private final Object sendLock = new Object();
    private Socket socket;       // guarded by sendLock
    private PrintWriter out;     // guarded by sendLock

    private volatile boolean wantConnected;
    private volatile boolean autoReconnect = true;
    private volatile boolean connected;     // true between WELCOME-able socket open and drop
    private volatile int generation;
    private volatile long rttMillis = -1;
    private volatile long pingSentNanos;    // 0 = no ping outstanding

    private final ScheduledExecutorService pinger = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "aquanet-pinger");
        t.setDaemon(true);
        return t;
    });

    public ConnectionManager(ClientListener listener) {
        this.listener = listener;
        pinger.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() { ping(); }
        }, 1, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ API

    public void setAutoReconnect(boolean on) { autoReconnect = on; }
    public boolean isAutoReconnect() { return autoReconnect; }
    public boolean isConnected() { return connected; }
    /** Last measured round-trip time in ms, or -1 if none yet. */
    public long getLatencyMillis() { return rttMillis; }

    /** Start (or restart) a connection in the background; returns immediately. */
    public synchronized void connect(final String host, final int port, final String name) {
        shutdownCurrent();
        wantConnected = true;
        final int myGen = ++generation;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { runWorker(myGen, host, port, name); }
        }, "aquanet-reader");
        t.setDaemon(true);
        t.start();
    }

    /** User-requested disconnect: no reconnect attempts afterwards. */
    public synchronized void disconnect() {
        boolean was = wantConnected;
        sendLine(Protocol.QUIT);
        shutdownCurrent();
        if (was) listener.onDisconnected("Disconnected by user");
    }

    /** Free the pinger thread (call when the application exits). */
    public void close() {
        wantConnected = false;
        shutdownCurrent();
        pinger.shutdownNow();
    }

    /** Send a controller command (its own '|' separators are kept), e.g. "PUMP|P1|ON" -> wire line "CMD|PUMP|P1|ON". */
    public boolean sendCommand(String command) {
        return sendLine(Protocol.CMD + Protocol.SEP + command.replace('\n', ' ').replace('\r', ' ').trim());
    }

    /** Send a chat line (the '|' character is replaced so it cannot break the protocol). */
    public boolean sendChat(String text) {
        return sendLine(Protocol.CHAT + Protocol.SEP + Protocol.clean(text));
    }

    /** Send a PING now; the RTT is reported through onLatency when PONG returns. */
    public void ping() {
        if (!connected) return;
        pingSentNanos = System.nanoTime();
        sendLine(Protocol.PING);
    }

    // ------------------------------------------------------------ internals

    /** Invalidate the running worker and close its socket (unblocks readLine). */
    private void shutdownCurrent() {
        wantConnected = false;
        generation++;
        connected = false;
        rttMillis = -1;
        closeSocket();
    }

    private void closeSocket() {
        synchronized (sendLock) {
            out = null;
            if (socket != null) {
                try { socket.close(); } catch (IOException ignore) { }
                socket = null;
            }
        }
    }

    /** Thread-safe single-line write. Returns false if not connected / write failed. */
    private boolean sendLine(String line) {
        synchronized (sendLock) {
            if (out == null) return false;
            out.print(line);
            out.print('\n');
            out.flush();
            if (out.checkError()) {   // PrintWriter swallows IOExceptions; checkError reveals them
                closeSocket();        // makes the reader thread's readLine() fail -> reconnect logic
                return false;
            }
            return true;
        }
    }

    private boolean stillCurrent(int myGen) { return wantConnected && generation == myGen; }

    /** Body of the reader/worker thread: connect, read until the link dies, maybe retry. */
    private void runWorker(int myGen, String host, int port, String name) {
        int attempt = 0;
        while (stillCurrent(myGen)) {
            String reason;
            Socket s = null;
            try {
                s = new Socket();
                s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                s.setTcpNoDelay(true);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                synchronized (sendLock) {
                    if (!stillCurrent(myGen)) { s.close(); return; }
                    socket = s;
                    out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)));
                }
                connected = true;
                attempt = 0;
                sendLine(Protocol.HELLO + Protocol.SEP + Protocol.clean(name));

                // ---- the reader loop: blocks until a full line arrives ----
                String line;
                while ((line = in.readLine()) != null) {
                    if (!stillCurrent(myGen)) return;
                    handleLine(line);
                }
                reason = "Server closed the connection";
            } catch (IOException e) {
                reason = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                if (s != null) { try { s.close(); } catch (IOException ignore) { } }
            }

            if (!stillCurrent(myGen)) return;   // user disconnected meanwhile: stay silent
            connected = false;
            rttMillis = -1;
            closeSocket();

            if (!autoReconnect) {
                wantConnected = false;
                listener.onDisconnected(reason);
                return;
            }
            int wait = Math.min(MAX_BACKOFF_SECONDS, 1 << Math.min(attempt, 4));
            attempt++;
            listener.onDisconnected(reason + " - retrying in " + wait + "s");
            for (int i = 0; i < wait * 10 && stillCurrent(myGen); i++) {
                try { Thread.sleep(100); } catch (InterruptedException e) { return; }
            }
        }
    }

    /**
     * Protocol dispatcher: one server line -> one listener call.
     * Format is "KEYWORD|field|field..."; we split with a limit so that the
     * last field (free text) may itself contain '|'.
     */
    void handleLine(String line) {
        if (line.isEmpty()) return;
        try {
            String[] f = Protocol.split(line, 3);
            String kind = f[0];
            if (Protocol.STATE.equals(kind)) {
                // STATE|<snapshot>: the snapshot uses '#' and ';' so the first '|' is the only one
                String[] g = Protocol.split(line, 2);
                listener.onState(NetworkSnapshot.fromWire(g[1]));
            } else if (Protocol.WELCOME.equals(kind)) {
                // The server sends ids like "C1"; keep only the digits so both "C1" and "1" work.
                String digits = f.length > 1 ? f[1].replaceAll("[^0-9]", "") : "";
                int id = digits.isEmpty() ? 0 : Integer.parseInt(digits);
                int tick = f.length > 2 ? Integer.parseInt(f[2].trim()) : Protocol.TICK_MILLIS;
                listener.onConnected(id, tick);
            } else if (Protocol.ALERT.equals(kind)) {
                listener.onAlert(f.length > 1 ? f[1] : "INFO", f.length > 2 ? f[2] : "");
            } else if (Protocol.ACK.equals(kind)) {
                listener.onAck(Protocol.split(line, 2).length > 1 ? Protocol.split(line, 2)[1] : "");
            } else if (Protocol.ERR.equals(kind)) {
                listener.onError(Protocol.split(line, 2).length > 1 ? Protocol.split(line, 2)[1] : "");
            } else if (Protocol.CHAT.equals(kind)) {
                if (f.length >= 3) listener.onChat(f[1], f[2]);
                else if (f.length == 2) listener.onChat("?", f[1]);
            } else if (Protocol.PONG.equals(kind)) {
                long sent = pingSentNanos;
                if (sent != 0) {
                    pingSentNanos = 0;
                    rttMillis = Math.max(0, (System.nanoTime() - sent) / 1_000_000L);
                    listener.onLatency(rttMillis);
                }
            }
            // unknown keywords are ignored on purpose (forward compatibility)
        } catch (RuntimeException e) {
            // a malformed line must never kill the reader thread
            System.err.println("[client] ignoring malformed line: " + line + " (" + e + ")");
        }
    }
}
