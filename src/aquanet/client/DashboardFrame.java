package aquanet.client;

import aquanet.common.NetworkSnapshot;
import aquanet.common.Protocol;
import aquanet.common.TankInfo;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Main window. Assembles the panels and is the ONLY place where network events
 * are turned into GUI updates.
 *
 * Why invokeLater: Swing is single-threaded - components may only be touched
 * from the Event Dispatch Thread (EDT). {@link ConnectionManager} calls our
 * listener from its reader thread, so every callback in {@link UiBridge}
 * wraps its work in SwingUtilities.invokeLater(...), which queues the Runnable
 * onto the EDT. This also means the reader thread never blocks on painting,
 * and the GUI never blocks on the socket.
 *
 * Layout (all layout managers, no absolute positioning):
 *   NORTH  connection bar          CENTER-top  tank panels (row)
 *   SOUTH  status bar              CENTER      split: network diagram | live chart
 *   EAST   controls + alerts/chat
 */
public class DashboardFrame extends JFrame {
    private final ConnectionManager connection;

    // connection bar
    private final JTextField hostField = new JTextField("localhost", 12);
    private final JTextField portField = new JTextField(String.valueOf(Protocol.TCP_PORT), 5);
    private final JTextField nameField = new JTextField(defaultName(), 10);
    private final JCheckBox reconnectBox = new JCheckBox("Auto-reconnect", true);
    private final JButton connectButton = new JButton("Connect");
    private boolean sessionActive;   // true from Connect click until a final disconnect

    // main panels
    private final JPanel tanksRow = new JPanel(new GridLayout(0, 3, 10, 10));
    private JPanel tanksWrap;   // grows by one row of tank cards for every 3 tanks
    private final Map<String, TankPanel> tankPanels = new HashMap<String, TankPanel>();
    private String tankSignature = "";
    private final NetworkDiagramPanel diagram = new NetworkDiagramPanel();
    private final TrendChartPanel chart = new TrendChartPanel();
    private final ControlPanel controls;
    private final AlertChatPanel alerts;

    // status bar
    private final JLabel connLabel = new JLabel("Disconnected");
    private final JLabel tickLabel = new JLabel("Tick: -");
    private final JLabel modeLabel = new JLabel("Mode: -");
    private final JLabel clientsLabel = new JLabel("Clients: -");
    private final JLabel rttLabel = new JLabel("RTT: - ms");

    /** Shared animation clock (~25 fps) for tanks and pipe arrows; runs on the EDT. */
    private final Timer animTimer;

    public DashboardFrame() {
        super("AquaNet - Water Network Control Centre");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { shutdown(); }
        });

        connection = new ConnectionManager(new UiBridge());
        controls = new ControlPanel(cmd -> connection.sendCommand(cmd));
        controls.setControlsEnabled(false);
        alerts = new AlertChatPanel(text -> sendChat(text));

        getContentPane().setBackground(Theme.BG);
        setLayout(new BorderLayout(8, 8));
        ((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(buildTopBar(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildEast(), BorderLayout.EAST);
        add(buildStatusBar(), BorderLayout.SOUTH);

        animTimer = new Timer(40, e -> {
            for (TankPanel tp : tankPanels.values()) tp.animate();
            diagram.animate();
        });
        animTimer.start();

        setMinimumSize(new Dimension(1000, 640));
        setSize(1320, 820);
        setLocationRelativeTo(null);
    }

    /** Pre-fill the connection fields from command-line arguments and connect straight away. */
    public void prefill(String host, String port, String name) {
        if (host != null) hostField.setText(host);
        if (port != null) portField.setText(port);
        if (name != null) nameField.setText(name);
        onConnectClicked();
    }

    private void sendChat(String text) {
        if (!connection.sendChat(text)) alerts.addAlert("WARN", "Not connected - chat message not sent");
    }

    private static String defaultName() {
        String u = System.getProperty("user.name");
        return (u == null || u.trim().isEmpty()) ? "operator" : u.trim();
    }

    // ---------------------------------------------------------------- layout

    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        bar.setBackground(Theme.CARD);
        bar.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        JLabel title = new JLabel("AquaNet");
        title.setFont(Theme.font(java.awt.Font.BOLD, 18f));
        title.setForeground(Theme.ACCENT);
        bar.add(title);
        bar.add(new JLabel("  Host:")); bar.add(hostField);
        bar.add(new JLabel("Port:")); bar.add(portField);
        bar.add(new JLabel("Name:")); bar.add(nameField);
        bar.add(reconnectBox);
        bar.add(connectButton);
        reconnectBox.setOpaque(false);
        reconnectBox.addActionListener(e -> connection.setAutoReconnect(reconnectBox.isSelected()));
        connectButton.addActionListener(e -> onConnectClicked());
        // Enter in any field = click Connect
        hostField.addActionListener(e -> { if (!sessionActive) onConnectClicked(); });
        portField.addActionListener(e -> { if (!sessionActive) onConnectClicked(); });
        nameField.addActionListener(e -> { if (!sessionActive) onConnectClicked(); });
        return bar;
    }

    private JPanel buildCenter() {
        tanksRow.setOpaque(false);
        tanksWrap = new JPanel(new BorderLayout());
        tanksWrap.setOpaque(false);
        tanksWrap.add(tanksRow, BorderLayout.CENTER);
        tanksWrap.setPreferredSize(new Dimension(600, 240));

        JSplitPane lower = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, diagram, chart);
        lower.setResizeWeight(0.55);
        lower.setBorder(null);
        lower.setDividerSize(6);
        diagram.setMinimumSize(new Dimension(200, 150));
        chart.setMinimumSize(new Dimension(200, 150));

        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.setOpaque(false);
        center.add(tanksWrap, BorderLayout.NORTH);
        center.add(lower, BorderLayout.CENTER);

        return center;
    }

    private JPanel buildEast() {
        JPanel east = new JPanel(new BorderLayout(0, 8));
        east.setOpaque(false);
        east.setPreferredSize(new Dimension(360, 100));
        JScrollPane cs = new JScrollPane(controls, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        cs.setBorder(null);
        cs.setOpaque(false);
        cs.getViewport().setOpaque(false);
        cs.setMinimumSize(new Dimension(200, 120));
        alerts.setMinimumSize(new Dimension(200, 150));
        // the user can drag the divider between controls and the alert/chat area
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, cs, alerts);
        split.setResizeWeight(0.5);
        split.setDividerLocation(400);
        split.setBorder(null);
        split.setOpaque(false);
        east.add(split, BorderLayout.CENTER);
        return east;
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 24, 4));
        bar.setBackground(Theme.CARD);
        bar.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        connLabel.setForeground(Theme.BAD);
        connLabel.setFont(Theme.font(java.awt.Font.BOLD, 12f));
        bar.add(connLabel); bar.add(tickLabel); bar.add(modeLabel); bar.add(clientsLabel); bar.add(rttLabel);
        return bar;
    }

    // --------------------------------------------------------------- actions

    private void onConnectClicked() {
        if (sessionActive) {                       // button currently says "Disconnect"
            sessionActive = false;
            connection.disconnect();               // fires onDisconnected("Disconnected by user")
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Port must be a number between 1 and 65535.",
                    "Invalid port", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String name = nameField.getText().trim();
        if (name.isEmpty()) name = defaultName();
        sessionActive = true;
        setFieldsEditable(false);
        connectButton.setText("Disconnect");
        setConn("Connecting...", Theme.S_LOW);
        connection.setAutoReconnect(reconnectBox.isSelected());
        connection.connect(hostField.getText().trim(), port, name);   // returns immediately
    }

    private void setFieldsEditable(boolean on) {
        hostField.setEnabled(on); portField.setEnabled(on); nameField.setEnabled(on);
    }

    private void setConn(String text, Color c) { connLabel.setText(text); connLabel.setForeground(c); }

    private void shutdown() {
        connection.disconnect();
        connection.close();
        animTimer.stop();
        dispose();
        System.exit(0);
    }

    // ------------------------------------------------------ applying updates (EDT only)

    private void applySnapshot(NetworkSnapshot s) {
        // (re)build tank panels if the set of tanks changed
        StringBuilder sig = new StringBuilder();
        for (TankInfo t : s.tanks) sig.append(t.id).append(',');
        if (!sig.toString().equals(tankSignature)) {
            tankSignature = sig.toString();
            tanksRow.removeAll();
            tankPanels.clear();
            for (TankInfo t : s.tanks) {
                TankPanel tp = new TankPanel(t.id);
                tankPanels.put(t.id, tp);
                tanksRow.add(tp);
            }
            int rows = Math.max(1, (s.tanks.size() + 2) / 3);
            tanksWrap.setPreferredSize(new Dimension(600, 10 + rows * 215));
            tanksWrap.revalidate();
            tanksRow.revalidate();
        }
        for (TankInfo t : s.tanks) {
            TankPanel tp = tankPanels.get(t.id);
            if (tp != null) tp.update(t);
        }
        diagram.update(s);
        chart.addSnapshot(s);
        controls.update(s);
        tickLabel.setText("Tick: " + s.tick);
        modeLabel.setText("Mode: " + s.mode);
        clientsLabel.setText("Clients: " + s.clients);
    }

    /**
     * Adapter between the reader thread and the EDT: each method just queues
     * the real work with invokeLater, so the GUI code above always runs on the EDT.
     */
    private final class UiBridge implements ClientListener {
        @Override public void onConnected(final int clientId, final int tickMillis) {
            SwingUtilities.invokeLater(() -> {
                setConn("Connected (client #" + clientId + ")", Theme.GOOD);
                controls.setControlsEnabled(true);
                alerts.addAlert("INFO", "Connected to server as client #" + clientId + ", tick " + tickMillis + " ms");
            });
        }
        @Override public void onState(final NetworkSnapshot s) {
            SwingUtilities.invokeLater(() -> applySnapshot(s));
        }
        @Override public void onAlert(final String level, final String message) {
            SwingUtilities.invokeLater(() -> alerts.addAlert(level, message));
        }
        @Override public void onAck(final String message) {
            SwingUtilities.invokeLater(() -> alerts.addAlert("ACK", message));
        }
        @Override public void onError(final String message) {
            SwingUtilities.invokeLater(() -> alerts.addAlert("ERR", message));
        }
        @Override public void onChat(final String from, final String text) {
            SwingUtilities.invokeLater(() -> alerts.addChat(from, text));
        }
        @Override public void onDisconnected(final String reason) {
            SwingUtilities.invokeLater(() -> {
                controls.setControlsEnabled(false);
                rttLabel.setText("RTT: - ms");
                alerts.addAlert("WARN", "Link down: " + reason);
                if (reason.contains("retrying")) {
                    setConn("Reconnecting...", Theme.S_LOW);
                } else {
                    sessionActive = false;
                    connectButton.setText("Connect");
                    setFieldsEditable(true);
                    setConn("Disconnected", Theme.BAD);
                }
            });
        }
        @Override public void onLatency(final long rtt) {
            SwingUtilities.invokeLater(() -> rttLabel.setText("RTT: " + rtt + " ms"));
        }
    }
}
