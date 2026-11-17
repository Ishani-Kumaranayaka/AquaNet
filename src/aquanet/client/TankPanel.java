package aquanet.client;

import aquanet.common.TankInfo;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JPanel;

/**
 * Custom-painted view of ONE tank: an outlined vessel filled with animated
 * water, coloured by status, plus numbers (percent, litres, in/out flow,
 * leak warning, UDP-sensor reading).
 *
 * Animation idea: the server only sends a new level once per second, but we
 * redraw ~25 times per second. {@link #animate()} (called by a shared
 * javax.swing.Timer on the EDT) moves the displayed level a fraction of the way
 * towards the real level each frame (easing) and advances a wave phase so the
 * water surface ripples. paintComponent only draws; it never changes state.
 */
class TankPanel extends JPanel {
    private TankInfo info;          // latest data (EDT only)
    private double shownFrac;       // 0..1, eased towards the real level
    private double phase;           // wave phase in radians
    private boolean firstUpdate = true;

    TankPanel(String id) {
        setOpaque(false);
        setName(id);
        setPreferredSize(new Dimension(260, 230));
        setMinimumSize(new Dimension(180, 170));
    }

    void update(TankInfo t) {
        this.info = t;
        double target = Math.max(0, Math.min(1, t.capacity <= 0 ? 0 : t.level / t.capacity));
        if (firstUpdate) { shownFrac = target; firstUpdate = false; }
        repaint();
    }

    /** One animation frame; called from the shared Swing timer. */
    void animate() {
        phase += 0.18;
        if (info != null) {
            double target = Math.max(0, Math.min(1, info.capacity <= 0 ? 0 : info.level / info.capacity));
            shownFrac += (target - shownFrac) * 0.12;
        }
        repaint();
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();

        // card background
        g.setColor(Theme.CARD);
        g.fill(new RoundRectangle2D.Double(2, 2, w - 5, h - 5, 16, 16));
        g.setColor(Theme.BORDER);
        g.draw(new RoundRectangle2D.Double(2, 2, w - 5, h - 5, 16, 16));

        if (info == null) { g.dispose(); return; }
        TankInfo t = info;
        String status = t.status();
        Color sc = Theme.statusColor(status);

        // header: name left, status chip right
        g.setFont(Theme.font(java.awt.Font.BOLD, 14f));
        g.setColor(Theme.TEXT);
        g.drawString(t.name + " (" + t.id + ")", 14, 26);
        g.setFont(Theme.font(java.awt.Font.BOLD, 11f));
        FontMetrics fm = g.getFontMetrics();
        int cw = fm.stringWidth(status) + 16;
        g.setColor(sc);
        g.fill(new RoundRectangle2D.Double(w - cw - 12, 11, cw, 20, 20, 20));
        g.setColor(Color.WHITE);
        g.drawString(status, w - cw - 4, 25);

        // the vessel
        int tx = 16, ty = 42;
        int tw = Math.max(70, Math.min(120, (int) (w * 0.42)));
        int th = h - ty - 16;
        Shape vessel = new RoundRectangle2D.Double(tx, ty, tw, th, 14, 14);
        g.setColor(new Color(0xF4F7FA));
        g.fill(vessel);

        // water (clipped to the vessel so the wave never leaks out)
        double wl = ty + th * (1 - shownFrac);               // y of water surface
        java.awt.Shape oldClip = g.getClip();
        g.clip(vessel);
        if (shownFrac > 0.004) {
            Path2D wave = new Path2D.Double();
            wave.moveTo(tx, ty + th);
            double amp = Math.min(3.5, th * 0.03);
            for (int x = 0; x <= tw; x += 3) {
                wave.lineTo(tx + x, wl + Math.sin(phase + x * 0.12) * amp);
            }
            wave.lineTo(tx + tw, ty + th);
            wave.closePath();
            g.setPaint(new GradientPaint(0, (float) wl, sc.brighter(), 0, ty + th, sc.darker()));
            g.fill(wave);
        }
        // scale marks at 25/50/75 %
        g.setColor(new Color(0, 0, 0, 60));
        g.setFont(Theme.font(java.awt.Font.PLAIN, 9f));
        for (int pct = 25; pct <= 75; pct += 25) {
            int y = (int) (ty + th * (1 - pct / 100.0));
            g.drawLine(tx, y, tx + 10, y);
            g.drawString(pct + "", tx + 13, y + 3);
        }
        g.setClip(oldClip);
        g.setStroke(new BasicStroke(2.5f));
        g.setColor(status.equals(TankInfo.OVERFLOW) ? Theme.S_OVERFLOW : new Color(0x56616D));
        g.draw(vessel);

        // numbers on the right
        int nx = tx + tw + 16;
        int y = ty + 22;
        g.setFont(Theme.font(java.awt.Font.BOLD, 26f));
        g.setColor(sc.darker());
        g.drawString(String.format("%.1f%%", t.percent()), nx, y);
        y += 22;
        g.setFont(Theme.font(java.awt.Font.PLAIN, 13f));
        g.setColor(Theme.TEXT);
        g.drawString(String.format("%.0f / %.0f L", t.level, t.capacity), nx, y);
        y += 22;
        g.setColor(Theme.GOOD);
        g.drawString(String.format("In   +%.1f L/s", t.inflow), nx, y);
        y += 18;
        g.setColor(Theme.S_LOW);
        g.drawString(String.format("Out -%.1f L/s", t.outflow), nx, y);
        y += 20;
        g.setFont(Theme.font(java.awt.Font.PLAIN, 12f));
        g.setColor(Theme.MUTED);
        if (t.measured >= 0) g.drawString(String.format("Sensor: %.0f L", t.measured), nx, y);
        else g.drawString("Sensor: offline", nx, y);
        y += 22;
        if (t.leaking) {
            // pulsing red drop icon + label
            boolean pulse = Math.sin(phase * 0.8) > -0.3;
            g.setColor(pulse ? Theme.BAD : new Color(0xF2A29E));
            Path2D drop = new Path2D.Double();
            drop.moveTo(nx + 8, y - 14);
            drop.curveTo(nx + 18, y - 2, nx + 14, y + 4, nx + 8, y + 4);
            drop.curveTo(nx + 2, y + 4, nx - 2, y - 2, nx + 8, y - 14);
            g.fill(drop);
            g.setFont(Theme.font(java.awt.Font.BOLD, 13f));
            g.drawString("LEAK", nx + 24, y + 2);
        }
        g.dispose();
    }
}
