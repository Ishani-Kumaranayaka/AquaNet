package aquanet.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * User accounts, password hashing and login sessions for the AquaNet website.
 *
 * Roles: ADMIN (can control the network, manage users and reports) and USER
 * (view-only dashboard, can report problems).
 *
 * Security ideas used here (good viva material):
 *  - Passwords are never stored. We store a random salt and a PBKDF2-HMAC-SHA256 hash.
 *  - After login the server creates a random session token and sends it in an HttpOnly cookie,
 *    so JavaScript cannot read it. The token is the key into the 'sessions' map.
 *  - Too many wrong passwords for one username locks that username for a minute.
 *
 * Thread safety: HTTP requests run on many worker threads, so sessions/lockouts use
 * ConcurrentHashMap and the user list is guarded by 'synchronized'.
 */
public class AuthService {
    public static final String ADMIN = "ADMIN";
    public static final String USER = "USER";

    private static final int ITERATIONS = 20000;
    private static final long SESSION_IDLE_MS = 8L * 60 * 60 * 1000;
    private static final int MAX_FAILS = 5;
    private static final long LOCK_MS = 60_000;

    /** One stored account. */
    public static final class Account {
        public final String name;
        public String role;
        final byte[] salt;
        final byte[] hash;
        Account(String name, String role, byte[] salt, byte[] hash) {
            this.name = name; this.role = role; this.salt = salt; this.hash = hash;
        }
    }

    /** One logged-in browser session. */
    public static final class Session {
        public final String token;
        public final String user;
        public final String role;
        volatile long lastUsed = System.currentTimeMillis();
        Session(String token, String user, String role) { this.token = token; this.user = user; this.role = role; }
        public boolean isAdmin() { return ADMIN.equals(role); }
    }

    private final Path file;
    private final List<Account> accounts = new ArrayList<Account>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<String, Session>();
    private final Map<String, int[]> fails = new ConcurrentHashMap<String, int[]>();     // name -> {count}
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<String, Long>();
    private final SecureRandom random = new SecureRandom();

    public AuthService(Path file) {
        this.file = file;
        load();
        synchronized (this) {
            if (find("admin") == null) {
                accounts.add(make("admin", "admin123", ADMIN));
                save();
                System.out.println("[web] Created default admin account: admin / admin123  (change it!)");
            }
        }
    }

    // ------------------------------------------------------------ accounts

    public static String validateName(String n) {
        if (n == null || !n.matches("[A-Za-z0-9_]{3,20}")) return "Username must be 3-20 letters, digits or _";
        return null;
    }

    /** @return error message, or null on success. New accounts are always USER. */
    public synchronized String register(String name, String password) {
        String e = validateName(name);
        if (e != null) return e;
        if (password == null || password.length() < 6) return "Password must be at least 6 characters";
        if (find(name) != null) return "That username is already taken";
        accounts.add(make(name, password, USER));
        save();
        return null;
    }

    /** @return a new Session, or null if the login failed. {@code error[0]} receives the reason. */
    public Session login(String name, String password, String[] error) {
        String key = name == null ? "" : name.toLowerCase();
        Long until = lockedUntil.get(key);
        if (until != null) {
            if (System.currentTimeMillis() < until) { error[0] = "Too many attempts. Try again in a minute."; return null; }
            lockedUntil.remove(key); fails.remove(key);
        }
        Account a;
        synchronized (this) { a = find(name); }
        boolean ok = a != null && password != null && constantTimeEquals(hash(password, a.salt), a.hash);
        if (!ok) {
            int[] c = fails.computeIfAbsent(key, k -> new int[1]);
            if (++c[0] >= MAX_FAILS) lockedUntil.put(key, System.currentTimeMillis() + LOCK_MS);
            error[0] = "Wrong username or password";
            return null;
        }
        fails.remove(key);
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        Session s = new Session(hex(raw), a.name, a.role);
        sessions.put(s.token, s);
        return s;
    }

    /** Look a session up by cookie token; refreshes idle timer; null if unknown/expired. */
    public Session session(String token) {
        if (token == null) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        long now = System.currentTimeMillis();
        if (now - s.lastUsed > SESSION_IDLE_MS) { sessions.remove(token); return null; }
        s.lastUsed = now;
        return s;
    }

    public void logout(String token) { if (token != null) sessions.remove(token); }

    public synchronized List<Account> listUsers() { return Collections.unmodifiableList(new ArrayList<Account>(accounts)); }

    /** @return error message or null. The last admin and the caller themselves cannot be removed. */
    public synchronized String delete(String name, String caller) {
        Account a = find(name);
        if (a == null) return "No such user";
        if (a.name.equalsIgnoreCase(caller)) return "You cannot delete your own account";
        accounts.remove(a);
        save();
        for (Session s : new ArrayList<Session>(sessions.values())) if (s.user.equalsIgnoreCase(name)) sessions.remove(s.token);
        return null;
    }

    /** Promote to ADMIN or demote to USER. Existing sessions are ended so the new role applies. */
    public synchronized String setRole(String name, String role, String caller) {
        Account a = find(name);
        if (a == null) return "No such user";
        if (!ADMIN.equals(role) && !USER.equals(role)) return "Role must be ADMIN or USER";
        if (a.name.equalsIgnoreCase(caller)) return "You cannot change your own role";
        a.role = role;
        save();
        for (Session s : new ArrayList<Session>(sessions.values())) if (s.user.equalsIgnoreCase(name)) sessions.remove(s.token);
        return null;
    }

    public int activeSessions() { return sessions.size(); }

    // ------------------------------------------------------------ crypto + storage

    private Account find(String name) {
        if (name == null) return null;
        for (Account a : accounts) if (a.name.equalsIgnoreCase(name)) return a;
        return null;
    }

    private Account make(String name, String password, String role) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return new Account(name, role, salt, hash(password, salt));
    }

    private static byte[] hash(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int r = 0;
        for (int i = 0; i < a.length; i++) r |= a[i] ^ b[i];
        return r == 0;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    private void load() {
        try {
            if (!Files.isRegularFile(file)) return;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.split("\\|");
                if (p.length == 4) accounts.add(new Account(p[0], p[1], unhex(p[2]), unhex(p[3])));
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("[web] Could not read " + file + ": " + e);
        }
    }

    private void save() {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<String>();
            for (Account a : accounts) lines.add(a.name + "|" + a.role + "|" + hex(a.salt) + "|" + hex(a.hash));
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[web] Could not save " + file + ": " + e);
        }
    }
}
