package aquanet.client;

import aquanet.common.NetworkSnapshot;
import aquanet.common.PumpInfo;
import aquanet.common.TankInfo;
import aquanet.common.ValveInfo;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JPanel;

/**
 * Schematic pipe diagram: SOURCE -> pump(s) -> tanks -> valves -> tanks ...
 *
 * The layout is computed from the snapshot on every paint, so it adapts to any
 * number of tanks/pumps/valves and to the window size:
 *  - column 0 = water source, column 1 = pumps, then one column per "depth"
 *    of tank. A tank's depth is 0 if no valve feeds it, otherwise
 *    1 + the depth of its upstream tank (valve.from -> valve.to).
 *  - items in a column are spread evenly from top to bottom.
 * Pipes are elbow polylines. Flowing pipes are blue and carry little arrows
 * that slide along the pipe; the slide position comes from {@link #phase},
 * advanced by {@link #animate()} from the shared timer.
 */
class NetworkDiagramPanel extends JPanel {
    private NetworkSnapshot snap;
    private double phase;   // 0..1 progress of the flow arrows

    NetworkDiagramPanel() {
        setBackground(Theme.CARD);
        setBorder(javax.swing.BorderFactory.createLineBorder(Theme.BORDER));
    }

    void update(NetworkSnapshot s) { this.snap = s; repaint(); }

    void animate() { phase = (phase + 0.02) % 1.0; if (snap != null) repaint(); }

    // ---- tiny helper types -------------------------------------------------
    private static final class Pt { double x, y; Pt(double x, double y) { this.x = x; this.y = y; } }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(Theme.font(java.awt.Font.BOLD, 13f));
        g.setColor(Theme.TEXT);
        g.drawString("Distribution network", 10, 19);
        if (snap == null) {
            g.setFont(Theme.font(java.awt.Font.PLAIN, 13f));
            g.setColor(Theme.MUTED);
            g.drawString("Waiting for data from server...", 20, 50);
            g.dispose();
            return;
        }
        NetworkSnapshot s = snap;
        int w = getWidth(), h = getHeight() - 30, top = 30;

        // ---- 1. depth of each tank ----
        Map<String, Integer> depth = new HashMap<String, Integer>();
        int n = s.tanks.size();
        for (TankInfo t : s.tanks) depth.put(t.id, -1);
        for (TankInfo t : s.tanks) {
            boolean fed = false;
            for (ValveInfo v : s.valves) if (v.to.equals(t.id) && s.tank(v.from) != null) fed = true;
            if (!fed) depth.put(t.id, 0);
        }
        for (int pass = 0; pass < n; pass++) {          // relax; bounded so cycles cannot loop forever
            for (ValveInfo v : s.valves) {
                Integer df = depth.get(v.from);
                if (df != null && df >= 0 && depth.containsKey(v.to)) {
                    depth.put(v.to, Math.min(n - 1, Math.max(depth.get(v.to), df + 1)));
                }
            }
        }
        int maxDepth = 0;
        for (TankInfo t : s.tanks) {
            if (depth.get(t.id) < 0) depth.put(t.id, 0);
            maxDepth = Math.max(maxDepth, depth.get(t.id));
        }

        // ---- 2. node centres ----
        int cols = 2 + maxDepth + 1;
        double colW = (w - 10) / (double) cols;
        Map<String, Pt> pos = new HashMap<String, Pt>();
        Pt source = new Pt(5 + colW * 0.5, top + h / 2.0);
        for (int i = 0; i < s.pumps.size(); i++)
            pos.put(s.pumps.get(i).id, new Pt(5 + colW * 1.5, top + h * (i + 0.5) / s.pumps.size()));
        for (int d = 0; d <= maxDepth; d++) {
            List<TankInfo> col = new ArrayList<TankInfo>();
            for (TankInfo t : s.tanks) if (depth.get(t.id) == d) col.add(t);
            for (int i = 0; i < col.size(); i++)
                pos.put(col.get(i).id, new Pt(5 + colW * (2.5 + d), top + h * (i + 0.5) / col.size()));
        }
        double tankW = Math.max(60, Math.min(colW * 0.5, 110));
        double tankH = Math.max(50, Math.min(h * 0.28, 95));
        double pumpR = Math.max(16, Math.min(26, colW * 0.18));

        // ---- 3. pipes (drawn first, nodes cover their ends) ----
        List<TankInfo> roots = new ArrayList<TankInfo>();
        for (TankInfo t : s.tanks) if (depth.get(t.id) == 0) roots.add(t);
        if (roots.isEmpty() && n > 0) roots.add(s.tanks.get(0));
        for (int i = 0; i < s.pumps.size(); i++) {
            PumpInfo p = s.pumps.get(i);
            Pt pp = pos.get(p.id);
            boolean flowing = p.on && p.flowRate > 0;
            drawPipe(g, new Pt(source.x + 28, source.y), new Pt(pp.x - pumpR, pp.y), flowing, null);
            if (!roots.isEmpty()) {
                TankInfo target = roots.get(i % roots.size());
                Pt tp = pos.get(target.id);
                drawPipe(g, new Pt(pp.x + pumpR, pp.y), new Pt(tp.x - tankW / 2, tp.y), flowing, null);
            }
        }
        for (ValveInfo v : s.valves) {
            Pt a = pos.get(v.from), b = pos.get(v.to);
            TankInfo ft = s.tank(v.from);
            if (a == null || b == null) continue;
            boolean flowing = v.openPercent > 0 && ft != null && ft.level > 0.5;
            drawPipe(g, new Pt(a.x + tankW / 2, a.y), new Pt(b.x - tankW / 2, b.y), flowing, v);
        }

        // ---- 4. nodes ----
        drawSource(g, source);
        for (PumpInfo p : s.pumps) drawPump(g, p, pos.get(p.id), pumpR);
        for (TankInfo t : s.tanks) drawTank(g, t, pos.get(t.id), tankW, tankH);
        g.dispose();
    }

    /** Elbow pipe a -> b; arrows slide along it when flowing. If v != null a valve is drawn at the middle. */
    private void drawPipe(Graphics2D g, Pt a, Pt b, boolean flowing, ValveInfo v) {
        double mx = (a.x + b.x) / 2;
        Pt[] pts = { a, new Pt(mx, a.y), new Pt(mx, b.y), b };
        Path2D path = new Path2D.Double();
        path.moveTo(pts[0].x, pts[0].y);
        for (int i = 1; i < pts.length; i++) path.lineTo(pts[i].x, pts[i].y);
        g.setStroke(new BasicStroke(9f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.setColor(Theme.PIPE);
        g.draw(path);
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.setColor(flowing ? new Color(0x7FBBEA) : new Color(0xE4E9EE));
        g.draw(path);

        double total = 0;
        double[] seg = new double[pts.length - 1];
        for (int i = 0; i < seg.length; i++) {
            seg[i] = Math.hypot(pts[i + 1].x - pts[i].x, pts[i + 1].y - pts[i].y);
            total += seg[i];
        }
        if (flowing && total > 20) {
            int count = Math.max(1, (int) (total / 40));
            g.setColor(Theme.ACCENT);
            for (int k = 0; k < count; k++) {
                double d = ((k + phase) / count) * total;
                drawArrowAt(g, pts, seg, d);
            }
        }
        if (v != null) {
            double d = total / 2, acc = 0;
            for (int i = 0; i < seg.length; i++) {
                if (d <= acc + seg[i] || i == seg.length - 1) {
                    double f = seg[i] == 0 ? 0 : (d - acc) / seg[i];
                    drawValve(g, v, pts[i].x + (pts[i + 1].x - pts[i].x) * f,
                            pts[i].y + (pts[i + 1].y - pts[i].y) * f);
                    break;
                }
                acc += seg[i];
            }
        }
    }

    /** Draw a small triangle {@code d} pixels along the polyline, pointing along the pipe. */
    private void drawArrowAt(Graphics2D g, Pt[] pts, double[] seg, double d) {
        double acc = 0;
        for (int i = 0; i < seg.length; i++) {
            if (d <= acc + seg[i]) {
                if (seg[i] == 0) return;
                double f = (d - acc) / seg[i];
                double x = pts[i].x + (pts[i + 1].x - pts[i].x) * f;
                double y = pts[i].y + (pts[i + 1].y - pts[i].y) * f;
                double ang = Math.atan2(pts[i + 1].y - pts[i].y, pts[i + 1].x - pts[i].x);
                AffineTransform old = g.getTransform();
                g.translate(x, y);
                g.rotate(ang);
                Path2D tri = new Path2D.Double();
                tri.moveTo(5, 0); tri.lineTo(-4, -4); tri.lineTo(-4, 4); tri.closePath();
                g.fill(tri);
                g.setTransform(old);
                return;
            }
            acc += seg[i];
        }
    }

    private void drawValve(Graphics2D g, ValveInfo v, double x, double y) {
        double r = 13;
        Path2D bow = new Path2D.Double();   // classic "bow-tie" valve symbol
        bow.moveTo(x - r, y - r * 0.7); bow.lineTo(x + r, y + r * 0.7);
        bow.lineTo(x + r, y - r * 0.7); bow.lineTo(x - r, y + r * 0.7); bow.closePath();
        Color c = v.openPercent == 0 ? Theme.BAD : (v.openPercent < 100 ? Theme.S_HIGH : Theme.GOOD);
        g.setColor(c);
        g.fill(bow);
        g.setColor(Theme.TEXT);
        g.setStroke(new BasicStroke(1.2f));
        g.draw(bow);
        g.setFont(Theme.font(java.awt.Font.BOLD, 11f));
        FontMetrics fm = g.getFontMetrics();
        String label = v.openPercent == 0 ? "CLOSED" : v.openPercent + "%";
        g.setColor(Theme.TEXT);
        g.drawString(label, (float) (x - fm.stringWidth(label) / 2.0), (float) (y - r - 3));
    }

    private void drawSource(Graphics2D g, Pt c) {
        g.setColor(new Color(0xD6EBFA));
        g.fill(new Ellipse2D.Double(c.x - 28, c.y - 28, 56, 56));
        g.setColor(Theme.ACCENT);
        g.setStroke(new BasicStroke(2f));
        g.draw(new Ellipse2D.Double(c.x - 28, c.y - 28, 56, 56));
        g.setFont(Theme.font(java.awt.Font.BOLD, 11f));
        FontMetrics fm = g.getFontMetrics();
        g.drawString("SOURCE", (float) (c.x - fm.stringWidth("SOURCE") / 2.0), (float) (c.y + 4));
    }

    private void drawPump(Graphics2D g, PumpInfo p, Pt c, double r) {
        Color col = p.on ? Theme.GOOD : Theme.BAD;
        g.setColor(col);
        g.fill(new Ellipse2D.Double(c.x - r, c.y - r, 2 * r, 2 * r));
        g.setColor(Color.WHITE);
        g.setFont(Theme.font(java.awt.Font.BOLD, 12f));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(p.id, (float) (c.x - fm.stringWidth(p.id) / 2.0), (float) (c.y + 4));
        g.setFont(Theme.font(java.awt.Font.PLAIN, 10f));
        g.setColor(col.darker());
        String st = p.on ? String.format("ON %.1f L/s", p.flowRate) : "OFF";
        fm = g.getFontMetrics();
        g.drawString(st, (float) (c.x - fm.stringWidth(st) / 2.0), (float) (c.y + r + 13));
    }

    private void drawTank(Graphics2D g, TankInfo t, Pt c, double w, double h) {
        RoundRectangle2D box = new RoundRectangle2D.Double(c.x - w / 2, c.y - h / 2, w, h, 12, 12);
        g.setColor(new Color(0xF4F7FA));
        g.fill(box);
        Color sc = Theme.statusColor(t.status());
        double frac = Math.max(0, Math.min(1, t.capacity <= 0 ? 0 : t.level / t.capacity));
        java.awt.Shape old = g.getClip();
        g.clip(box);
        g.setColor(sc);
        g.fill(new java.awt.geom.Rectangle2D.Double(c.x - w / 2, c.y + h / 2 - h * frac, w, h * frac));
        g.setClip(old);
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(0x56616D));
        g.draw(box);
        g.setFont(Theme.font(java.awt.Font.BOLD, 12f));
        FontMetrics fm = g.getFontMetrics();
        String l1 = t.id, l2 = String.format("%.0f%%", t.percent());
        while (l1.length() > 3 && fm.stringWidth(l1) > w - 6) l1 = l1.substring(0, l1.length() - 1);   // keep the name inside the box
        g.setColor(Theme.TEXT);
        g.drawString(l1, (float) (c.x - fm.stringWidth(l1) / 2.0), (float) (c.y - 2));
        g.drawString(l2, (float) (c.x - fm.stringWidth(l2) / 2.0), (float) (c.y + 13));
        if (t.leaking) {
            g.setColor(Theme.BAD);
            g.setFont(Theme.font(java.awt.Font.BOLD, 10f));
            g.drawString("LEAK", (float) (c.x - w / 2 + 4), (float) (c.y - h / 2 - 4));
        }
    }
}
