package aquanet.client;

import aquanet.common.NetworkSnapshot;

/**
 * Callback interface between the networking layer ({@link ConnectionManager})
 * and the rest of the client.
 *
 * IMPORTANT: ConnectionManager calls these methods from its own background
 * threads (the reader thread / the reconnect loop), NOT from the Swing Event
 * Dispatch Thread (EDT). Swing components are not thread-safe, so any
 * implementation that touches the GUI must hop to the EDT first
 * (SwingUtilities.invokeLater). DashboardFrame does exactly that in one place.
 */
public interface ClientListener {
    /** The server sent WELCOME|clientId|tickMillis - the session is now usable. */
    void onConnected(int clientId, int tickMillis);

    /** A STATE|... line was received and decoded into a snapshot. */
    void onState(NetworkSnapshot snapshot);

    /** ALERT|LEVEL|message  (level is INFO, WARN or CRIT). */
    void onAlert(String level, String message);

    /** ACK|message - the server accepted one of our commands. */
    void onAck(String message);

    /** ERR|message - the server rejected a command. */
    void onError(String message);

    /** CHAT|from|text - a chat message broadcast by the server. */
    void onChat(String from, String text);

    /** The link is down (failed connect, dropped link, or user disconnect). */
    void onDisconnected(String reason);

    /** A PING/PONG round trip finished; rttMillis is the measured latency. */
    void onLatency(long rttMillis);
}
