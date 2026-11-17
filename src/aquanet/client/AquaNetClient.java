package aquanet.client;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Entry point of the Swing client.
 * Usage: java aquanet.client.AquaNetClient [host] [port] [name]
 * (the three optional arguments pre-fill the connection fields and connect immediately).
 */
public class AquaNetClient {
    public static void main(final String[] args) {
        // All Swing objects must be created on the EDT, hence invokeLater even here.
        SwingUtilities.invokeLater(new Runnable() {
            @Override public void run() {
                try {
                    for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                        if ("Nimbus".equals(info.getName())) { UIManager.setLookAndFeel(info.getClassName()); break; }
                    }
                } catch (Exception ignore) { /* fall back to the default look and feel */ }
                DashboardFrame f = new DashboardFrame();
                if (args.length >= 1) f.prefill(args[0], args.length > 1 ? args[1] : null, args.length > 2 ? args[2] : null);
                f.setVisible(true);
            }
        });
    }
}
