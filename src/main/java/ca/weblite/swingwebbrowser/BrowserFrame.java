package ca.weblite.swingwebbrowser;

import ca.weblite.webview.ConsoleMessage;
import ca.weblite.webview.swing.WebViewComponent;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The main browser window.  Hosts the toolbar, the tab strip
 * ({@link JTabbedPane}), and the developer console (collapsible split).
 *
 * <p>The frame owns a single {@link BookmarkStore} and
 * {@link HistoryStore} shared across every tab, plus one
 * {@link DevConsolePanel} that retargets itself when the active tab
 * changes.
 */
public final class BrowserFrame extends JFrame implements BrowserTab.Listener {

    private static final String HOME = "https://example.com";

    private final BookmarkStore bookmarks = new BookmarkStore();
    private final HistoryStore history = new HistoryStore();

    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextField urlField = new JTextField();
    private final JButton backBtn = new JButton("◀");
    private final JButton fwdBtn = new JButton("▶");
    private final JButton reloadBtn = new JButton("↻");
    private final JButton homeBtn = new JButton("⌂");
    private final JButton newTabBtn = new JButton("+");
    private final JToggleButton consoleBtn = new JToggleButton("Console");
    private final JButton bookmarkAddBtn = new JButton("☆");
    private final JMenu bookmarksMenu = new JMenu("Bookmarks");

    private final DevConsolePanel devConsole = new DevConsolePanel();
    private final JSplitPane split;

    public BrowserFrame() {
        super("Swing Web Browser");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1100, 800);
        setLocationRelativeTo(null);

        // ---- Toolbar layout ----
        JPanel toolbar = buildToolbar();

        devConsole.setOnClose(() -> {
            consoleBtn.setSelected(false);
            setConsoleVisible(false);
        });

        // ---- Tab strip ----
        tabs.addChangeListener(new ChangeListener() {
            @Override public void stateChanged(ChangeEvent e) {
                onActiveTabChanged();
            }
        });
        // Middle-click closes a tab.
        tabs.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON2) {
                    int i = tabs.indexAtLocation(e.getX(), e.getY());
                    if (i >= 0) closeTab(i);
                }
            }
        });

        split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, devConsole);
        split.setResizeWeight(1.0);
        split.setContinuousLayout(true);
        // Console hidden until the user opens it.
        devConsole.setVisible(false);
        split.setDividerSize(0);

        setLayout(new BorderLayout());
        add(toolbar, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        setJMenuBar(buildMenuBar());
        bindGlobalShortcuts();
    }

    // ------------------------------------------------------------------
    // Toolbar
    // ------------------------------------------------------------------

    private JPanel buildToolbar() {
        backBtn.setToolTipText("Back (Alt+Left)");
        fwdBtn.setToolTipText("Forward (Alt+Right)");
        reloadBtn.setToolTipText("Reload (Ctrl+R)");
        homeBtn.setToolTipText("Home");
        newTabBtn.setToolTipText("New tab (Ctrl+T)");
        consoleBtn.setToolTipText("Toggle developer console (Ctrl+Shift+J)");
        bookmarkAddBtn.setToolTipText("Bookmark this page");

        backBtn.addActionListener(e -> { BrowserTab t = activeTab(); if (t != null) t.back(); });
        fwdBtn.addActionListener(e -> { BrowserTab t = activeTab(); if (t != null) t.forward(); });
        reloadBtn.addActionListener(e -> { BrowserTab t = activeTab(); if (t != null) t.reload(); });
        homeBtn.addActionListener(e -> { BrowserTab t = activeTab(); if (t != null) t.load(HOME); });
        newTabBtn.addActionListener(e -> newTab(HOME));
        consoleBtn.addActionListener(e -> setConsoleVisible(consoleBtn.isSelected()));
        bookmarkAddBtn.addActionListener(e -> toggleBookmarkForActive());

        urlField.setToolTipText("Enter a URL or search query (Enter to navigate)");
        urlField.addActionListener(e -> {
            BrowserTab t = activeTab();
            if (t != null) t.load(urlField.getText());
        });
        // Keep the bookmark-star in sync while the user types.
        urlField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { refreshBookmarkStar(); }
            @Override public void removeUpdate(DocumentEvent e)  { refreshBookmarkStar(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshBookmarkStar(); }
        });

        JPanel nav = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        nav.add(backBtn);
        nav.add(fwdBtn);
        nav.add(reloadBtn);
        nav.add(homeBtn);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 2));
        right.add(bookmarkAddBtn);
        right.add(consoleBtn);
        right.add(newTabBtn);

        JPanel toolbar = new JPanel(new BorderLayout(4, 0));
        toolbar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        toolbar.add(nav, BorderLayout.WEST);
        toolbar.add(urlField, BorderLayout.CENTER);
        toolbar.add(right, BorderLayout.EAST);
        return toolbar;
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.add(menuItem("New Tab", KeyEvent.VK_T, () -> newTab(HOME)));
        file.add(menuItem("Close Tab", KeyEvent.VK_W, () -> closeTab(tabs.getSelectedIndex())));
        file.addSeparator();
        file.add(menuItem("Quit", KeyEvent.VK_Q, () -> dispatchEvent(
            new java.awt.event.WindowEvent(this, java.awt.event.WindowEvent.WINDOW_CLOSING))));
        bar.add(file);

        JMenu view = new JMenu("View");
        view.add(menuItem("Reload", KeyEvent.VK_R, () -> {
            BrowserTab t = activeTab(); if (t != null) t.reload();
        }));
        JMenuItem console = new JMenuItem("Toggle Developer Console");
        console.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_J,
            java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | KeyEvent.SHIFT_DOWN_MASK));
        console.addActionListener(e -> {
            consoleBtn.setSelected(!consoleBtn.isSelected());
            setConsoleVisible(consoleBtn.isSelected());
        });
        view.add(console);
        view.addSeparator();
        view.add(buildUserAgentMenu());
        view.add(buildPopupBehaviorMenu());
        view.addSeparator();
        view.add(menuItem("View Headers…", 0, this::showHeadersDialog));
        bar.add(view);

        JMenu history = new JMenu("History");
        history.add(menuItem("Show History…", 0, this::showHistoryDialog));
        history.add(menuItem("Clear History", 0, () -> {
            int r = JOptionPane.showConfirmDialog(this,
                "Clear all browsing history?", "Clear history",
                JOptionPane.OK_CANCEL_OPTION);
            if (r == JOptionPane.OK_OPTION) this.history.clear();
        }));
        bar.add(history);

        rebuildBookmarksMenu();
        bar.add(bookmarksMenu);

        JMenu help = new JMenu("Help");
        help.add(menuItem("About…", 0, this::showAboutDialog));
        bar.add(help);

        return bar;
    }

    /** View ▸ User Agent: a radio group choosing the {@code User-Agent} sent
     *  for requests (the real HTTP header).  "Default" uses the engine's
     *  built-in UA; the others send a modern desktop UA for that browser.
     *  Selecting an option updates new tabs and reloads the current tab so the
     *  new UA takes effect immediately. */
    private JMenu buildUserAgentMenu() {
        JMenu menu = new JMenu("User Agent");
        menu.setToolTipText("User-Agent header sent for requests "
            + "(applies to new tabs; reloads the current tab)");
        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        for (BrowserTab.UserAgentOption opt : BrowserTab.UserAgentOption.values()) {
            javax.swing.JRadioButtonMenuItem item =
                new javax.swing.JRadioButtonMenuItem(opt.label);
            item.setSelected(BrowserTab.getUserAgentOption() == opt);
            item.addActionListener(e -> {
                BrowserTab.setUserAgentOption(opt);
                BrowserTab t = activeTab();
                if (t != null) t.applyUserAgent(opt.ua);
            });
            group.add(item);
            menu.add(item);
        }
        return menu;
    }

    /** View ▸ Popup Behavior: a radio group choosing how popups are handled for
     *  tabs opened from now on.  "Native" hosts each popup in a native window
     *  (the default), "Tab" adopts it into a new tab, and "None" suppresses
     *  popups so they open in the current window. */
    private JMenu buildPopupBehaviorMenu() {
        JMenu menu = new JMenu("Popup Behavior");
        menu.setToolTipText("How popups open (applies to tabs opened from now on)");
        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        for (BrowserTab.PopupMode mode : BrowserTab.PopupMode.values()) {
            javax.swing.JRadioButtonMenuItem item =
                new javax.swing.JRadioButtonMenuItem(mode.label);
            item.setSelected(BrowserTab.getPopupMode() == mode);
            item.addActionListener(e -> BrowserTab.setPopupMode(mode));
            group.add(item);
            menu.add(item);
        }
        return menu;
    }

    // ------------------------------------------------------------------
    // Header inspector
    // ------------------------------------------------------------------

    /** View ▸ View Headers…: a diagnostic dialog with two views of the current
     *  page's headers, useful when a site blocks the browser and you need to
     *  see what it's reacting to:
     *  <ul>
     *    <li><b>Engine response</b> — a second {@code fetch(location.href)} run
     *        inside the page, so it reuses the engine's real session (cookies,
     *        the User-Agent actually sent, real TLS).  These response headers
     *        are authoritative, but JavaScript can't read the outgoing request
     *        headers.</li>
     *    <li><b>HTTP probe</b> — a {@link java.net.http.HttpClient} request from
     *        Java that shows the full request headers sent <i>and</i> the
     *        response status/headers/body, re-runnable per User-Agent.  It's a
     *        separate session with a different TLS fingerprint, so treat it as
     *        an approximation of the real request.</li>
     *  </ul>
     */
    private void showHeadersDialog() {
        BrowserTab tab = activeTab();
        if (tab == null || tab.currentUrl() == null || tab.currentUrl().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Open a page first, then view its headers.",
                "View Headers", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        javax.swing.JTextArea engineArea = monospaceArea();
        javax.swing.JTextArea probeArea = monospaceArea();

        JLabel urlLabel = new JLabel();
        javax.swing.JComboBox<BrowserTab.UserAgentOption> uaCombo =
            new javax.swing.JComboBox<>(BrowserTab.UserAgentOption.values());
        uaCombo.setSelectedItem(BrowserTab.getUserAgentOption());
        uaCombo.setToolTipText("User-Agent the HTTP probe sends "
            + "(\"Default\" mirrors the engine's own navigator.userAgent)");
        JButton refresh = new JButton("Refresh");

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        controls.add(new JLabel("Probe User-Agent:"));
        controls.add(uaCombo);
        controls.add(refresh);

        JPanel urlRow = new JPanel(new BorderLayout());
        urlRow.setBorder(BorderFactory.createEmptyBorder(6, 8, 0, 8));
        urlRow.add(urlLabel, BorderLayout.CENTER);

        JTabbedPane tabsPane = new JTabbedPane();
        tabsPane.addTab("Engine response (real session)", new JScrollPane(engineArea));
        tabsPane.addTab("HTTP probe (request + response)", new JScrollPane(probeArea));

        JPanel north = new JPanel(new BorderLayout());
        north.add(urlRow, BorderLayout.NORTH);
        north.add(controls, BorderLayout.SOUTH);

        JButton close = new JButton("Close");
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(close);

        JPanel root = new JPanel(new BorderLayout(4, 4));
        root.add(north, BorderLayout.NORTH);
        root.add(tabsPane, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        javax.swing.JDialog dialog =
            new javax.swing.JDialog(this, "Request / Response Headers", false);
        dialog.getContentPane().add(root);
        dialog.setSize(780, 580);
        dialog.setLocationRelativeTo(this);

        Runnable run = () -> {
            String url = tab.currentUrl();
            urlLabel.setText("<html><b>URL:</b> " + escape(url) + "</html>");
            engineArea.setText("Loading response headers from the engine session…");
            probeArea.setText("Running HTTP probe…");
            loadEngineHeaders(tab, engineArea);
            BrowserTab.UserAgentOption sel =
                (BrowserTab.UserAgentOption) uaCombo.getSelectedItem();
            if (sel != null && sel.ua != null) {
                runHttpProbe(url, sel.ua, probeArea);
            } else {
                // "Default": resolve the engine's real UA (navigator.userAgent)
                // so the probe mirrors what the browser sends, then probe.
                tab.webView().evalAsync(
                        "btoa(unescape(encodeURIComponent(navigator.userAgent)))")
                    .orTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                    .whenComplete((res, err) -> SwingUtilities.invokeLater(() ->
                        runHttpProbe(url, err == null ? decodeEvalBase64(res) : null,
                                     probeArea)));
            }
        };
        refresh.addActionListener(e -> run.run());
        close.addActionListener(e -> dialog.dispose());
        dialog.setVisible(true);
        run.run();
    }

    /** Fetch the current page again from inside the engine and render its
     *  response status + headers into {@code area}.  Reuses the engine's real
     *  session; response headers only (JS can't read request headers). */
    private void loadEngineHeaders(BrowserTab tab, javax.swing.JTextArea area) {
        final String js =
            "(function(){"
          + "  return fetch(location.href,{credentials:'include',cache:'no-store'})"
          + "    .then(function(r){"
          + "      var out=r.status+' '+r.statusText+'\\n';"
          + "      try{ r.headers.forEach(function(v,k){ out+=k+': '+v+'\\n'; }); }"
          + "      catch(e){ out+='(headers not enumerable: '+e+')\\n'; }"
          + "      return out;"
          + "    })"
          + "    .catch(function(e){ return 'fetch failed: '+((e&&e.message)||e); })"
          + "    .then(function(s){ return btoa(unescape(encodeURIComponent(s))); });"
          + "})()";
        tab.webView().evalAsync(js)
            .orTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .whenComplete((res, err) -> SwingUtilities.invokeLater(() -> {
                if (err != null) {
                    area.setText("Engine fetch failed or timed out:\n" + err
                        + "\n\n(The engine may not support evalAsync, or the page\n"
                        + "blocked the fetch.  Try the HTTP probe tab.)");
                } else {
                    area.setText(
                        "# Response headers as seen by the engine's own session\n"
                      + "# (real WebKit/Edge cookies, User-Agent, and TLS — a second\n"
                      + "# GET of this URL).  JavaScript cannot read the outgoing\n"
                      + "# request headers; see the HTTP probe tab for those.\n\n"
                      + decodeEvalBase64(res));
                }
            }));
    }

    /** Issue a Java-side HTTP GET for {@code url} with {@code ua} (null → JDK
     *  default) and render request + response headers and a body snippet into
     *  {@code area}.  Runs on a background thread; updates the area on the EDT. */
    private void runHttpProbe(String url, String ua, javax.swing.JTextArea area) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            area.setText("The HTTP probe only supports http/https URLs.\n"
                + "Current URL: " + url);
            return;
        }
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .connectTimeout(java.time.Duration.ofSeconds(15))
                    .build();

                java.net.http.HttpRequest.Builder rb = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(20))
                    .GET()
                    .header("Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9");
                if (ua != null && !ua.isEmpty()) rb.header("User-Agent", ua);
                java.net.http.HttpRequest req = rb.build();

                sb.append("### REQUEST\n");
                sb.append("GET ").append(url).append('\n');
                req.headers().map().forEach((k, vs) ->
                    vs.forEach(v -> sb.append(k).append(": ").append(v).append('\n')));
                if (ua == null || ua.isEmpty()) {
                    sb.append("User-Agent: (JDK default — could not resolve engine UA)\n");
                }
                sb.append("# Note: the JDK adds Host and Connection at send time.\n");
                sb.append("# This probe is a separate HTTP client — different TLS\n");
                sb.append("# fingerprint and no engine cookies — so a fingerprint- or\n");
                sb.append("# cookie-based blocker may respond differently than the real\n");
                sb.append("# browser.  Compare against the Engine response tab.\n\n");

                java.net.http.HttpResponse<String> resp =
                    client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());

                sb.append("### RESPONSE\n");
                sb.append("HTTP ").append(resp.version()).append(' ')
                  .append(resp.statusCode()).append('\n');
                resp.headers().map().forEach((k, vs) ->
                    vs.forEach(v -> sb.append(k).append(": ").append(v).append('\n')));

                String body = resp.body();
                if (body != null && !body.isEmpty()) {
                    String snippet = body.length() > 2000
                        ? body.substring(0, 2000) + "\n… (truncated)"
                        : body;
                    sb.append("\n### BODY (first 2 KB)\n").append(snippet).append('\n');
                }
            } catch (Exception ex) {
                sb.append("\nProbe failed: ").append(ex).append('\n');
            }
            final String text = sb.toString();
            SwingUtilities.invokeLater(() -> {
                area.setText(text);
                area.setCaretPosition(0);
            });
        }, "header-probe").start();
    }

    private static javax.swing.JTextArea monospaceArea() {
        javax.swing.JTextArea a = new javax.swing.JTextArea();
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(false);
        a.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        a.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        return a;
    }

    /** Decode the JSON-string result of {@code evalAsync} whose value is a
     *  base64 payload (our header snippets are base64-wrapped on the JS side to
     *  dodge JSON escaping).  Falls back to the raw text if it isn't base64. */
    private static String decodeEvalBase64(String jsonResult) {
        if (jsonResult == null) return "(no result)";
        String s = jsonResult.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1);
        }
        if (s.isEmpty() || s.equals("null")) return "(empty)";
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(s);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return jsonResult; // not base64 — show raw
        }
    }

    private JMenuItem menuItem(String label, int keyCode, Runnable action) {
        JMenuItem mi = new JMenuItem(label);
        if (keyCode != 0) {
            mi.setAccelerator(KeyStroke.getKeyStroke(keyCode,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        }
        mi.addActionListener(e -> action.run());
        return mi;
    }

    private void bindGlobalShortcuts() {
        JComponent root = (JComponent) getContentPane();
        bindKey(root, KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, KeyEvent.ALT_DOWN_MASK), "back",
            () -> { BrowserTab t = activeTab(); if (t != null) t.back(); });
        bindKey(root, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, KeyEvent.ALT_DOWN_MASK), "forward",
            () -> { BrowserTab t = activeTab(); if (t != null) t.forward(); });
        bindKey(root, KeyStroke.getKeyStroke(KeyEvent.VK_L,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "focus-url",
            () -> { urlField.requestFocusInWindow(); urlField.selectAll(); });
    }

    private static void bindKey(JComponent comp, KeyStroke ks, String name, Runnable action) {
        comp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, name);
        comp.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    // ------------------------------------------------------------------
    // Tab management
    // ------------------------------------------------------------------

    public BrowserTab newTab(String url) {
        BrowserTab tab = new BrowserTab(this);
        tabs.addTab("New Tab", tab);
        int i = tabs.getTabCount() - 1;
        tabs.setTabComponentAt(i, buildTabHeader(tab));
        tabs.setSelectedComponent(tab);
        tab.load(url);
        return tab;
    }

    private Component buildTabHeader(BrowserTab tab) {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        header.setOpaque(false);
        JLabel title = new JLabel("Loading…");
        title.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
        JButton close = new JButton("×");        // ×
        close.setMargin(new java.awt.Insets(0, 4, 0, 4));
        close.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        close.setContentAreaFilled(false);
        close.setFocusable(false);
        close.setToolTipText("Close tab");
        close.addActionListener(e -> closeTab(tabs.indexOfComponent(tab)));
        // Swing does not translate clicks on a custom tab component into a
        // tab selection (mouse events don't bubble to the JTabbedPane's UI
        // handler), so clicking the header's label/background would leave
        // the selection unchanged.  Select the tab explicitly on press.
        MouseAdapter selectOnPress = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                int i = tabs.indexOfComponent(tab);
                if (i >= 0) tabs.setSelectedIndex(i);
            }
        };
        header.addMouseListener(selectOnPress);
        title.addMouseListener(selectOnPress);
        header.add(title);
        header.add(close);
        header.putClientProperty("titleLabel", title);
        return header;
    }

    private void setTabTitle(BrowserTab tab, String text) {
        int i = tabs.indexOfComponent(tab);
        if (i < 0) return;
        Component c = tabs.getTabComponentAt(i);
        if (c instanceof JPanel) {
            JLabel lbl = (JLabel) ((JPanel) c).getClientProperty("titleLabel");
            if (lbl != null) {
                String shown = text.length() > 30 ? text.substring(0, 28) + "…" : text;
                lbl.setText(shown);
                lbl.setToolTipText(text);
            }
        }
        // Keep the plain title in sync too -- screen readers etc. read this.
        tabs.setTitleAt(i, text);
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.getTabCount()) return;
        BrowserTab tab = (BrowserTab) tabs.getComponentAt(index);
        tabs.removeTabAt(index);
        tab.dispose();
        if (tabs.getTabCount() == 0) {
            // Closing the last tab quits the app, like Chrome on Linux/Win.
            dispatchEvent(new java.awt.event.WindowEvent(this,
                java.awt.event.WindowEvent.WINDOW_CLOSING));
        }
    }

    private BrowserTab activeTab() {
        Component c = tabs.getSelectedComponent();
        return c instanceof BrowserTab ? (BrowserTab) c : null;
    }

    private void onActiveTabChanged() {
        BrowserTab t = activeTab();
        if (t == null) {
            urlField.setText("");
            backBtn.setEnabled(false);
            fwdBtn.setEnabled(false);
            devConsole.setActiveTab(null);
            setTitle("Swing Web Browser");
            return;
        }
        urlField.setText(t.currentUrl());
        backBtn.setEnabled(t.canGoBack());
        fwdBtn.setEnabled(t.canGoForward());
        devConsole.setActiveTab(t);
        setTitle(t.currentTitle().isEmpty()
            ? "Swing Web Browser"
            : t.currentTitle() + " - Swing Web Browser");
        refreshBookmarkStar();
    }

    // ------------------------------------------------------------------
    // BrowserTab.Listener
    // ------------------------------------------------------------------

    @Override public void onUrlChanged(BrowserTab tab, String url) {
        history.record(url, tab.currentTitle());
        if (tab == activeTab()) {
            urlField.setText(url);
            refreshBookmarkStar();
        }
    }

    @Override public void onTitleChanged(BrowserTab tab, String title) {
        String shown = (title == null || title.isEmpty()) ? tab.currentUrl() : title;
        setTabTitle(tab, shown);
        if (tab == activeTab()) {
            setTitle(shown + " - Swing Web Browser");
        }
    }

    @Override public void onNavStateChanged(BrowserTab tab) {
        if (tab == activeTab()) {
            backBtn.setEnabled(tab.canGoBack());
            fwdBtn.setEnabled(tab.canGoForward());
        }
    }

    @Override public void onConsoleMessage(BrowserTab tab, ConsoleMessage msg) {
        devConsole.onConsoleMessage(tab, msg);
    }

    @Override public void onPopupAdopted(BrowserTab source, WebViewComponent adoptedView,
                                         String targetUrl) {
        // Host the engine's opener-linked popup child (POST verb + body and
        // window.opener preserved) as a new tab, next to the opener.  Adding
        // the tab and selecting it realizes the component, which adopts the
        // retained child.  We seed the URL/title from the popup target rather
        // than load() -- a GET would discard the in-flight POST.
        BrowserTab tab = BrowserTab.adopting(this, adoptedView);
        int at = tabs.indexOfComponent(source);
        int insert = at >= 0 ? at + 1 : tabs.getTabCount();
        tabs.insertTab("New Tab", null, tab, null, insert);
        tabs.setTabComponentAt(insert, buildTabHeader(tab));
        tabs.setSelectedComponent(tab);
        tab.seedInitialUrl(targetUrl);
    }

    // ------------------------------------------------------------------
    // Bookmarks
    // ------------------------------------------------------------------

    /** Rebuilds the Bookmarks menu in place: two fixed actions
     *  ("Bookmark This Page", "Manage Bookmarks…") followed by one
     *  menu item per saved bookmark.  Called after every bookmark add
     *  / remove so the menu reflects the current store. */
    private void rebuildBookmarksMenu() {
        bookmarksMenu.removeAll();
        bookmarksMenu.add(menuItem("Bookmark This Page", KeyEvent.VK_D, this::toggleBookmarkForActive));
        bookmarksMenu.add(menuItem("Manage Bookmarks…", 0, this::showBookmarkManager));
        if (!bookmarks.bookmarks().isEmpty()) {
            bookmarksMenu.addSeparator();
            for (BookmarkStore.Bookmark b : bookmarks.bookmarks()) {
                JMenuItem mi = new JMenuItem(b.title);
                mi.setToolTipText(b.url);
                mi.addActionListener(e -> {
                    BrowserTab t = activeTab();
                    if (t != null) t.load(b.url);
                });
                bookmarksMenu.add(mi);
            }
        }
    }

    private void toggleBookmarkForActive() {
        BrowserTab t = activeTab();
        if (t == null) return;
        String url = t.currentUrl();
        if (url == null || url.isEmpty()) return;
        if (bookmarks.contains(url)) {
            bookmarks.remove(url);
        } else {
            bookmarks.add(t.currentTitle(), url);
        }
        rebuildBookmarksMenu();
        refreshBookmarkStar();
    }

    private void refreshBookmarkStar() {
        BrowserTab t = activeTab();
        boolean starred = t != null && bookmarks.contains(t.currentUrl());
        bookmarkAddBtn.setText(starred ? "★" : "☆");   // ★ / ☆
        bookmarkAddBtn.setToolTipText(starred
            ? "Remove bookmark for this page"
            : "Bookmark this page");
    }

    private void showBookmarkManager() {
        JList<BookmarkStore.Bookmark> list = new JList<>(
            bookmarks.bookmarks().toArray(new BookmarkStore.Bookmark[0]));
        list.setCellRenderer(new ListCellRenderer<BookmarkStore.Bookmark>() {
            @Override public Component getListCellRendererComponent(
                    JList<? extends BookmarkStore.Bookmark> list,
                    BookmarkStore.Bookmark value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                JLabel l = new JLabel("<html><b>" + escape(value.title)
                    + "</b><br><span style='color:#888'>"
                    + escape(value.url) + "</span></html>");
                l.setOpaque(true);
                if (isSelected) {
                    l.setBackground(list.getSelectionBackground());
                    l.setForeground(list.getSelectionForeground());
                }
                l.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
                return l;
            }
        });
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(400, 320));

        JButton open = new JButton("Open");
        JButton openNew = new JButton("Open in New Tab");
        JButton remove = new JButton("Remove");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(open);
        buttons.add(openNew);
        buttons.add(remove);

        JPanel root = new JPanel(new BorderLayout(4, 4));
        root.add(sp, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);

        javax.swing.JDialog dialog = new javax.swing.JDialog(this, "Bookmarks", true);
        dialog.getContentPane().add(root);
        dialog.pack();
        dialog.setLocationRelativeTo(this);

        open.addActionListener(e -> {
            BookmarkStore.Bookmark sel = list.getSelectedValue();
            if (sel != null) {
                BrowserTab t = activeTab();
                if (t != null) t.load(sel.url);
                dialog.dispose();
            }
        });
        openNew.addActionListener(e -> {
            BookmarkStore.Bookmark sel = list.getSelectedValue();
            if (sel != null) {
                newTab(sel.url);
                dialog.dispose();
            }
        });
        remove.addActionListener(e -> {
            BookmarkStore.Bookmark sel = list.getSelectedValue();
            if (sel != null) {
                bookmarks.remove(sel.url);
                rebuildBookmarksMenu();
                refreshBookmarkStar();
                list.setListData(bookmarks.bookmarks()
                    .toArray(new BookmarkStore.Bookmark[0]));
            }
        });

        dialog.setVisible(true);
    }

    // ------------------------------------------------------------------
    // History dialog
    // ------------------------------------------------------------------

    private void showHistoryDialog() {
        java.util.List<HistoryStore.Entry> list = new java.util.ArrayList<>(history.entries());
        java.util.Collections.reverse(list);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        Object[][] rows = new Object[list.size()][3];
        for (int i = 0; i < list.size(); i++) {
            HistoryStore.Entry e = list.get(i);
            rows[i][0] = fmt.format(new Date(e.when.toEpochMilli()));
            rows[i][1] = e.title;
            rows[i][2] = e.url;
        }
        javax.swing.JTable table = new javax.swing.JTable(rows,
            new String[]{"When", "Title", "URL"}) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        table.setAutoResizeMode(javax.swing.JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getColumnModel().getColumn(0).setPreferredWidth(140);
        table.getColumnModel().getColumn(1).setPreferredWidth(260);
        table.getColumnModel().getColumn(2).setPreferredWidth(420);
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = table.getSelectedRow();
                    if (row >= 0) {
                        String url = (String) table.getValueAt(row, 2);
                        newTab(url);
                        SwingUtilities.getWindowAncestor(table).dispose();
                    }
                }
            }
        });
        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(820, 440));
        javax.swing.JDialog dialog = new javax.swing.JDialog(this, "History", true);
        dialog.getContentPane().add(sp);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    // ------------------------------------------------------------------
    // Console pane toggle
    // ------------------------------------------------------------------

    private void setConsoleVisible(boolean visible) {
        devConsole.setVisible(visible);
        split.setDividerSize(visible ? 6 : 0);
        if (visible) {
            // Give the console a reasonable initial height.
            split.setDividerLocation(Math.max(getHeight() - 260, 200));
            devConsole.setActiveTab(activeTab());
        }
        split.revalidate();
    }

    private void showAboutDialog() {
        JOptionPane.showMessageDialog(this,
            "<html><h2>Swing Web Browser</h2>" +
            "<p>A demo browser built on the " +
            "<a href='https://github.com/webliteca/swingwebview'>swingwebview</a> " +
            "Swing component.</p>" +
            "<p>Java " + System.getProperty("java.version") +
            " on " + System.getProperty("os.name") + "</p></html>",
            "About", JOptionPane.INFORMATION_MESSAGE);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
