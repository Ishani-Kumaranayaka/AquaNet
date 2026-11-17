package aquanet.client;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.ActionListener;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;

/**
 * Alert log (colour coded by level, with timestamps) and a chat box.
 * Pure view: all methods must be called on the EDT.
 */
class AlertChatPanel extends JPanel {
    /** One line in the alert log. */
    private static final class Entry {
        final String time, level, text;
        Entry(String level, String text) {
            this.time = new SimpleDateFormat("HH:mm:ss").format(new Date());
            this.level = level; this.text = text;
        }
    }

    private static final int MAX_ENTRIES = 500;
    private final DefaultListModel<Entry> model = new DefaultListModel<Entry>();
    private final JList<Entry> list = new JList<Entry>(model);
    private final JTextArea chat = new JTextArea();
    private final JTextField input = new JTextField();

    AlertChatPanel(final java.util.function.Consumer<String> chatSender) {
        setLayout(new BorderLayout());
        setOpaque(false);

        list.setCellRenderer(new ListCellRenderer<Entry>() {
            @Override public Component getListCellRendererComponent(JList<? extends Entry> l, Entry e, int i,
                                                                    boolean sel, boolean focus) {
                JLabel lab = new JLabel(e.time + "  [" + e.level + "]  " + e.text);
                lab.setOpaque(true);
                lab.setFont(Theme.font(java.awt.Font.PLAIN, 12f));
                lab.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
                lab.setForeground(colorFor(e.level));
                lab.setBackground(sel ? new Color(0xE3EEFB) : (i % 2 == 0 ? Color.WHITE : new Color(0xF7F9FB)));
                if ("CRIT".equals(e.level)) lab.setFont(lab.getFont().deriveFont(java.awt.Font.BOLD));
                return lab;
            }
        });
        JScrollPane alertScroll = new JScrollPane(list);
        alertScroll.setBorder(titled("Alerts & events"));

        chat.setEditable(false);
        chat.setLineWrap(true);
        chat.setWrapStyleWord(true);
        chat.setFont(Theme.font(java.awt.Font.PLAIN, 12f));
        JPanel chatPanel = new JPanel(new BorderLayout(4, 4));
        chatPanel.setOpaque(false);
        chatPanel.setBorder(titled("Chat"));
        chatPanel.add(new JScrollPane(chat), BorderLayout.CENTER);
        JPanel sendRow = new JPanel(new BorderLayout(4, 0));
        sendRow.setOpaque(false);
        JButton send = new JButton("Send");
        sendRow.add(input, BorderLayout.CENTER);
        sendRow.add(send, BorderLayout.EAST);
        chatPanel.add(sendRow, BorderLayout.SOUTH);
        ActionListener doSend = e -> {
            String t = input.getText().trim();
            if (!t.isEmpty()) { chatSender.accept(t); input.setText(""); }
        };
        send.addActionListener(doSend);
        input.addActionListener(doSend);   // Enter key

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, alertScroll, chatPanel);
        split.setResizeWeight(0.6);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
    }

    private static javax.swing.border.Border titled(String t) {
        return BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Theme.BORDER), t);
    }

    static Color colorFor(String level) {
        if ("CRIT".equals(level) || "ERR".equals(level)) return Theme.BAD;
        if ("WARN".equals(level)) return new Color(0xC76A00);
        if ("ACK".equals(level)) return Theme.GOOD;
        return new Color(0x3B5B7D);   // INFO and anything unknown
    }

    void addAlert(String level, String text) {
        model.addElement(new Entry(level, text));
        while (model.size() > MAX_ENTRIES) model.remove(0);
        list.ensureIndexIsVisible(model.size() - 1);
    }

    void addChat(String from, String text) {
        chat.append(new SimpleDateFormat("HH:mm").format(new Date()) + " " + from + ": " + text + "\n");
        chat.setCaretPosition(chat.getDocument().getLength());
    }
}
