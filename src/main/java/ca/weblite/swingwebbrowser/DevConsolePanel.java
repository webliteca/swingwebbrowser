package ca.weblite.swingwebbrowser;

import ca.weblite.webview.ConsoleMessage;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.JTextPane;

/**
 * A simple developer console panel.  Renders console output from the
 * currently-active {@link BrowserTab} in the upper pane and exposes a
 * one-line JS eval prompt at the bottom.
 *
 * <p>The {@link BrowserFrame} owns one of these for the whole window
 * and re-points it at whichever tab is currently active.
 */
public final class DevConsolePanel extends JPanel {

    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    private final JTextPane output;
    private final StyledDocument doc;
    private final JTextField evalField;
    private final JLabel statusLabel;

    private BrowserTab activeTab;

    public DevConsolePanel() {
        super(new BorderLayout(0, 0));
        setBorder(BorderFactory.createTitledBorder("Developer console"));

        output = new JTextPane();
        output.setEditable(false);
        output.setFont(MONO);
        output.setBackground(new Color(0x1e, 0x1e, 0x1e));
        output.setForeground(new Color(0xd4, 0xd4, 0xd4));
        output.setCaretColor(new Color(0xd4, 0xd4, 0xd4));
        doc = output.getStyledDocument();

        evalField = new JTextField();
        evalField.setFont(MONO);
        evalField.setToolTipText("Evaluate JS on the current page (Enter to run).  " +
            "Wrap the expression with window.swingPrint(...) to print the result here.");
        evalField.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    runEval();
                }
            }
        });

        JButton runBtn = new JButton("Run");
        runBtn.addActionListener(e -> runEval());

        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> clearConsole());

        JButton inspectorBtn = new JButton("Open DevTools");
        inspectorBtn.setToolTipText("Open the platform's native web inspector " +
            "(WebKitGTK on Linux, Chromium DevTools on Windows).  macOS does " +
            "not expose a programmatic open; use right-click → Inspect.");
        inspectorBtn.addActionListener(e -> {
            if (activeTab != null) activeTab.openDevTools();
        });

        statusLabel = new JLabel(" ");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));

        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        JPanel evalRow = new JPanel(new BorderLayout(4, 0));
        evalRow.add(new JLabel(" > "), BorderLayout.WEST);
        evalRow.add(evalField, BorderLayout.CENTER);
        evalRow.add(runBtn, BorderLayout.EAST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.add(inspectorBtn);
        buttons.add(clearBtn);
        bottom.add(evalRow, BorderLayout.CENTER);
        bottom.add(buttons, BorderLayout.EAST);
        bottom.add(statusLabel, BorderLayout.SOUTH);

        add(new JScrollPane(output), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
    }

    /** Repoint the panel at a different tab; replays its buffered console. */
    public void setActiveTab(BrowserTab tab) {
        this.activeTab = tab;
        output.setText("");
        if (tab != null) {
            for (ConsoleMessage m : tab.consoleBuffer()) appendLine(m);
            statusLabel.setText("Showing console for: " + tab.currentTitle());
        } else {
            statusLabel.setText(" ");
        }
    }

    /** Called by {@link BrowserFrame} when a tab the user is currently
     *  looking at gets a new console message. */
    public void onConsoleMessage(BrowserTab tab, ConsoleMessage msg) {
        if (tab != activeTab) return;
        appendLine(msg);
    }

    private void runEval() {
        if (activeTab == null) return;
        String js = evalField.getText();
        if (js.isEmpty()) return;
        echo("> " + js);
        activeTab.evalJs(js);
        evalField.setText("");
    }

    private void clearConsole() {
        if (activeTab != null) activeTab.clearConsole();
        output.setText("");
    }

    // --- rendering ---

    private void appendLine(ConsoleMessage msg) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        Color c = colorFor(msg.getLevel());
        StyleConstants.setForeground(a, c);
        StyleConstants.setFontFamily(a, Font.MONOSPACED);
        write(msg.toString() + "\n", a);
        scrollToBottom();
    }

    private void echo(String line) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, new Color(0x9c, 0xdc, 0xfe));
        StyleConstants.setFontFamily(a, Font.MONOSPACED);
        write(line + "\n", a);
        scrollToBottom();
    }

    private void write(String text, SimpleAttributeSet a) {
        try {
            doc.insertString(doc.getLength(), text, a);
        } catch (BadLocationException ignored) {
            // The end of the document is always a valid offset.
        }
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> output.setCaretPosition(doc.getLength()));
    }

    private static Color colorFor(ConsoleMessage.Level level) {
        switch (level) {
            case ERROR: return new Color(0xf4, 0x84, 0x71);
            case WARN:  return new Color(0xdc, 0xdc, 0xaa);
            case INFO:  return new Color(0x9c, 0xdc, 0xfe);
            case DEBUG: return new Color(0xa0, 0xa0, 0xa0);
            case LOG:
            default:    return new Color(0xd4, 0xd4, 0xd4);
        }
    }
}
