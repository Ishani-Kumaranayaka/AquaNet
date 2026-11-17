package aquanet.web;

import aquanet.common.NetworkSnapshot;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Holds (1) problem reports written by users and handled by admins, and (2) an audit trail
 * of who did what on the website. Everything is in memory and thread safe.
 */
public class ReportBoard {
    private static final int MAX_AUDIT = 200;

    private static final class Report {
        final int id; final String user; final String tank; final String text; final String time;
        String status = "OPEN"; String note = "";
        Report(int id, String user, String tank, String text) {
            this.id = id; this.user = user; this.tank = tank; this.text = text; this.time = now();
        }
        String json() {
            return "{\"id\":" + id + ",\"user\":" + q(user) + ",\"tank\":" + q(tank) + ",\"text\":" + q(text)
                    + ",\"time\":" + q(time) + ",\"status\":" + q(status) + ",\"note\":" + q(note) + "}";
        }
    }

    private final List<Report> reports = new ArrayList<Report>();
    private final ArrayDeque<String> audit = new ArrayDeque<String>();
    private int nextId = 1;

    private static String now() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()); }
    private static String q(String s) { return NetworkSnapshot.jsonQuote(s); }

    public synchronized int add(String user, String tank, String text) {
        Report r = new Report(nextId++, user, tank, text);
        reports.add(r);
        audit(user, "reported a problem (#" + r.id + ")");
        return r.id;
    }

    /** @return false if no such report. */
    public synchronized boolean resolve(int id, String admin, String note) {
        for (Report r : reports) {
            if (r.id == id) {
                r.status = "RESOLVED"; r.note = note;
                audit(admin, "resolved report #" + id);
                return true;
            }
        }
        return false;
    }

    /** JSON array; an admin sees all reports, a normal user only their own. Newest first. */
    public synchronized String json(String user, boolean all) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (int i = reports.size() - 1; i >= 0; i--) {
            Report r = reports.get(i);
            if (!all && !r.user.equalsIgnoreCase(user)) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append(r.json());
        }
        return sb.append(']').toString();
    }

    public synchronized int openCount() {
        int n = 0;
        for (Report r : reports) if ("OPEN".equals(r.status)) n++;
        return n;
    }

    public synchronized void audit(String user, String action) {
        audit.addLast("{\"time\":" + q(now()) + ",\"user\":" + q(user) + ",\"action\":" + q(action) + "}");
        while (audit.size() > MAX_AUDIT) audit.removeFirst();
    }

    public synchronized String auditJson() {
        StringBuilder sb = new StringBuilder("[");
        List<String> l = new ArrayList<String>(audit);
        for (int i = l.size() - 1; i >= 0; i--) { sb.append(l.get(i)); if (i > 0) sb.append(','); }
        return sb.append(']').toString();
    }
}
