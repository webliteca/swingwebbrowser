package ca.weblite.swingwebbrowser;

import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;

/**
 * Entry point.  Boots the Swing event-dispatch thread and opens a
 * {@link BrowserFrame}.
 *
 * <p>This demo browser is a thin Swing shell around
 * {@link ca.weblite.webview.swing.WebViewComponent}.  Each tab owns its
 * own native WebView; toolbar and menus delegate to whichever tab is
 * currently visible.
 */
public final class SwingWebBrowser {

    private SwingWebBrowser() {}

    public static void main(String[] args) {
        // The heavyweight WebView peer (macOS / Windows) paints above
        // lightweight Swing popups -- flip them to heavyweight so JComboBox
        // dropdowns, JMenu, and tooltips render as real OS windows that sit
        // above the native peer.  Has no observable effect in lightweight
        // mode (Linux).
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Fall back to cross-platform L&F.
        }

        String startUrl = args.length > 0 ? args[0] : "https://example.com";
        SwingUtilities.invokeLater(() -> {
            BrowserFrame frame = new BrowserFrame();
            frame.newTab(startUrl);
            frame.setVisible(true);
        });
    }
}
