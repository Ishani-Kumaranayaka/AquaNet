package aquanet.client;

import aquanet.common.NetworkSnapshot;
import aquanet.common.PumpInfo;
import aquanet.common.TankInfo;
import aquanet.common.ValveInfo;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JToggleButton;

/**
 * Operator controls, built dynamically from the snapshot: one ON/OFF toggle per
 * pump, one 0-100 slider per valve, one leak inject/repair button pair per tank,
 * an AUTO/MANUAL toggle and RESET.
 *
 * Rebuild rule: the widgets are recreated only when the SET of ids changes
 * (see {@link #signature}); on normal ticks the existing widgets are just
 * synchronised with the new values. While syncing, the flag {@link #syncing}
 * is set so programmatic changes do not fire a command back to the server
 * (otherwise every state push would echo as a command).
 *
 * Sliders: a command is sent only when {@code getValueIsAdjusting()} is false,
 * i.e. when the user RELEASES the knob - not on every drag tick - which keeps
 * network traffic low.
 */
class ControlPanel extends JPanel {
    /** Where commands go (ConnectionManager.sendCommand in the real app). */
    interface CommandSink { boolean send(String command); }

    private final CommandSink sink;
    private String signature = "";
    private boolean syncing;
    private boolean enabled;

    private final Map<String, JToggleButton> pumpButtons = new HashMap<String, JToggleButton>();
    private final Map<String, JSlider> valveSliders = new HashMap<String, JSlider>();
    private final Map<String, JLabel> valveLabels = new HashMap<String, JLabel>();
    private JToggleButton modeButton;
    private final List<JComponent> all = new ArrayList<JComponent>();

    ControlPanel(CommandSink sink) {
        this.sink = sink;
        setBackground(Theme.CARD);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        setLayout(new BorderLayout());
        add(new JLabel("Controls - waiting for server..."), BorderLayout.CENTER);
    }

    /** Enable/disable every control (disabled while disconnected). */
    void setControlsEnabled(boolean on) {
        enabled = on;
        for (JComponent c : all) c.setEnabled(on);
    }

    void update(NetworkSnapshot s) {
        String sig = signatureOf(s);
        if (!sig.equals(signature)) { signature = sig; rebuild(s); }
        syncing = true;
        try {
            for (PumpInfo p : s.pumps) {
                JToggleButton b = pumpButtons.get(p.id);
                if (b != null) { b.setSelected(p.on); b.setText(p.id + (p.on ? "  ON" : "  OFF")); }
            }
            for (ValveInfo v : s.valves) {
                JSlider sl = valveSliders.get(v.id);
                if (sl != null && !sl.getValueIsAdjusting()) sl.setValue(v.openPercent);
                JLabel l = valveLabels.get(v.id);
                if (l != null && sl != null && !sl.getValueIsAdjusting()) l.setText(v.id + " " + v.from + "→" + v.to + "  " + v.openPercent + "%");
            }
            boolean auto = "AUTO".equals(s.mode);
            modeButton.setSelected(auto);
            modeButton.setText("Mode: " + s.mode + "  (click to switch)");
        } finally { syncing = false; }
    }

    private static String signatureOf(NetworkSnapshot s) {
        StringBuilder sb = new StringBuilder();
        for (TankInfo t : s.tanks) sb.append('T').append(t.id);
        for (PumpInfo p : s.pumps) sb.append('P').append(p.id);
        for (ValveInfo v : s.valves) sb.append('V').append(v.id);
        return sb.toString();
    }

    private void rebuild(NetworkSnapshot s) {
        removeAll(); all.clear(); pumpButtons.clear(); valveSliders.clear(); valveLabels.clear();
        JPanel body = new JPanel(new GridBagLayout());
        body.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 0, 2, 0);
        c.gridy = 0;

        // --- mode ---
        modeButton = new JToggleButton("Mode");
        modeButton.addActionListener(e -> {
            if (syncing) return;
            // after the click, isSelected() is the NEW desired state
            sink.send("MODE|" + (modeButton.isSelected() ? "AUTO" : "MANUAL"));
        });
        reg(modeButton);
        body.add(section("Operating mode"), c); c.gridy++;
        body.add(modeButton, c); c.gridy++;

        // --- pumps ---
        body.add(section("Pumps"), c); c.gridy++;
        JPanel pumpRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        pumpRow.setOpaque(false);
        for (final PumpInfo p : s.pumps) {
            final JToggleButton b = new JToggleButton(p.id);
            b.addActionListener(e -> {
                if (!syncing) sink.send("PUMP|" + p.id + "|" + (b.isSelected() ? "ON" : "OFF"));
            });
            pumpButtons.put(p.id, b);
            reg(b);
            pumpRow.add(b);
        }
        body.add(pumpRow, c); c.gridy++;

        // --- valves ---
        body.add(section("Valves (release slider to apply)"), c); c.gridy++;
        for (final ValveInfo v : s.valves) {
            final JLabel lab = new JLabel(v.id);
            final JSlider sl = new JSlider(0, 100, v.openPercent);
            sl.setMajorTickSpacing(25);
            sl.setPaintTicks(true);
            sl.addChangeListener(e -> {
                lab.setText(v.id + " " + v.from + "→" + v.to + "  " + sl.getValue() + "%");
                // send only after the user lets go (valueIsAdjusting becomes false)
                if (!sl.getValueIsAdjusting() && !syncing) sink.send("VALVE|" + v.id + "|" + sl.getValue());
            });
            valveSliders.put(v.id, sl);
            valveLabels.put(v.id, lab);
            reg(sl);
            body.add(lab, c); c.gridy++;
            body.add(sl, c); c.gridy++;
        }

        // --- leaks ---
        body.add(section("Leak simulation"), c); c.gridy++;
        for (final TankInfo t : s.tanks) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 1));
            row.setOpaque(false);
            JButton inject = new JButton("Leak " + t.id);
            JButton repair = new JButton("Repair " + t.id);
            inject.addActionListener(e -> sink.send("LEAK|" + t.id + "|ON"));
            repair.addActionListener(e -> sink.send("LEAK|" + t.id + "|OFF"));
            reg(inject); reg(repair);
            row.add(inject); row.add(repair);
            body.add(row, c); c.gridy++;
        }

        // --- reset ---
        JButton reset = new JButton("RESET simulation");
        reset.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, "Reset the whole network to its starting state?",
                    "Reset", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) sink.send("RESET");
        });
        reg(reset);
        c.insets = new Insets(10, 0, 2, 0);
        body.add(reset, c);

        add(body, BorderLayout.NORTH);
        setControlsEnabled(enabled);
        revalidate(); repaint();
    }

    private void reg(JComponent comp) { all.add(comp); comp.setEnabled(enabled); }

    private static Component section(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.font(java.awt.Font.BOLD, 12f));
        l.setForeground(Theme.MUTED);
        l.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        return l;
    }
}
