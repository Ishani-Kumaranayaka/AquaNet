package aquanet.client;

import aquanet.common.NetworkSnapshot;
import aquanet.common.TankInfo;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JPanel;

/**
 * Hand-drawn line chart (no chart library) of tank fill percentage over the
 * last {@value #MAX_POINTS} ticks. One history list per tank id, kept in a
 * LinkedHashMap so series keep a stable order and colour. Series are created on
 * demand, so any number of tanks works.
 */
class TrendChartPanel extends JPanel {
    static final int MAX_POINTS = 120;

    private final Map<String, List<Double>> history = new LinkedHashMap<String, List<Double>>();
    private final Map<String, String> names = new LinkedHashMap<String, String>();

    TrendChartPanel() {
        setBackground(Theme.CARD);
        setBorder(javax.swing.BorderFactory.createLineBorder(Theme.BORDER));
    }

    /** Append one sample per tank (call once per received snapshot, on the EDT). */
    void addSnapshot(NetworkSnapshot s) {
        for (TankInfo t : s.tanks) {
            List<Double> l = history.get(t.id);
            if (l == null) { l = new ArrayList<Double>(); history.put(t.id, l); }
            names.put(t.id, t.name);
            l.add(t.percent());
            while (l.size() > MAX_POINTS) l.remove(0);
        }
        repaint();
    }

    void clear() { history.clear(); names.clear(); repaint(); }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        int left = 42, right = 14, top = 44, bottom = 22;
        int pw = w - left - right, ph = h - top - bottom;

        g.setFont(Theme.font(java.awt.Font.BOLD, 13f));
        g.setColor(Theme.TEXT);
        g.drawString("Tank levels (%) - last " + MAX_POINTS + " ticks", 10, 19);
        if (pw < 20 || ph < 20) { g.dispose(); return; }

        // grid + y labels
        g.setFont(Theme.font(java.awt.Font.PLAIN, 10f));
        FontMetrics fm = g.getFontMetrics();
        for (int pct = 0; pct <= 100; pct += 25) {
            int y = top + ph - (int) Math.round(ph * pct / 100.0);
            g.setColor(new Color(0xE3E8ED));
            g.drawLine(left, y, left + pw, y);
            g.setColor(Theme.MUTED);
            String lab = pct + "%";
            g.drawString(lab, left - 6 - fm.stringWidth(lab), y + 4);
        }
        g.setColor(Theme.BORDER);
        g.drawRect(left, top, pw, ph);
        g.setColor(Theme.MUTED);
        g.drawString("older", left, h - 7);
        g.drawString("now", left + pw - fm.stringWidth("now"), h - 7);

        // series
        int idx = 0;
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        List<String> legend = new ArrayList<String>();
        for (Map.Entry<String, List<Double>> e : history.entrySet()) {
            Color c = Theme.SERIES[idx % Theme.SERIES.length];
            List<Double> l = e.getValue();
            Path2D p = new Path2D.Double();
            // x position: newest sample is at the right edge; spacing is fixed per tick
            double dx = pw / (double) (MAX_POINTS - 1);
            int n = l.size();
            for (int i = 0; i < n; i++) {
                double x = left + pw - (n - 1 - i) * dx;
                double y = top + ph - ph * Math.max(0, Math.min(100, l.get(i))) / 100.0;
                if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
            g.setColor(c);
            g.draw(p);
            legend.add(e.getKey());
            idx++;
        }
        // legend: second row under the title, right aligned
        int lx = w - right;
        for (int i = legend.size() - 1; i >= 0; i--) {
            String label = names.get(legend.get(i));
            int tw = fm.stringWidth(label);
            lx -= tw;
            g.setColor(Theme.TEXT);
            g.drawString(label, lx, 37);
            lx -= 16;
            g.setColor(Theme.SERIES[i % Theme.SERIES.length]);
            g.fillRect(lx, 29, 12, 8);
            lx -= 14;
        }
        g.dispose();
    }
}
