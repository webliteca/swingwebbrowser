package ca.weblite.swingwebbrowser;

import ca.weblite.webview.ConsoleListener;
import ca.weblite.webview.ConsoleMessage;
import ca.weblite.webview.PopupDisposition;
import ca.weblite.webview.WebView;
import ca.weblite.webview.WebViewPopupEvent;
import ca.weblite.webview.WebViewPopupHandler;
import ca.weblite.webview.swing.WebViewComponent;

import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * One browser tab.  Owns:
 * <ul>
 *   <li>a {@link WebViewComponent} (the actual rendering),</li>
 *   <li>a back/forward URL stack,</li>
 *   <li>the captured console buffer for the developer console,</li>
 *   <li>the current page title (kept in sync via a JS hook).</li>
 * </ul>
 *
 * <p>Tabs notify their owner via the {@link Listener} interface whenever
 * something the UI cares about changes (URL, title, back/forward
 * availability, new console line).
 */
public final class BrowserTab extends JPanel {

    /** Notifications the toolbar / tab strip / dev console listen for. */
    public interface Listener {
        void onUrlChanged(BrowserTab tab, String url);
        void onTitleChanged(BrowserTab tab, String title);
        void onNavStateChanged(BrowserTab tab);
        void onConsoleMessage(BrowserTab tab, ConsoleMessage msg);

        /** A page in {@code source} opened a popup ({@code window.open}, or a
         *  {@code target="_blank"} / {@code target="…"} link or form) that the
         *  engine retained as an opener-linked child — POST verb + body and
         *  {@code window.opener} intact.  {@code adoptedView} is the {@link
         *  WebViewComponent} that adopted that child; host it in a new tab.
         *  {@code targetUrl} is the popup's target, used only to seed the URL
         *  bar / tab title.  Always invoked on the Swing EDT. */
        void onPopupAdopted(BrowserTab source, WebViewComponent adoptedView, String targetUrl);
    }

    /** JS shim injected into every navigated document.  It calls back
     *  into Java via {@code window.__swb_nav} on every URL change so the
     *  URL bar and history reflect link clicks and {@code pushState}
     *  navigation, not just URL-bar typing.  Wrapping pushState /
     *  replaceState is what makes SPA frameworks observable from
     *  outside.
     *
     *  <p>The payload is base64-encoded ({@code urltitle}) before
     *  going across so the bind-shim's JSON wrapper has no special
     *  characters in the args array -- a cheap substring extractor on
     *  the Java side is then sufficient and we don't need a JSON parser. */
    private static final String NAV_SHIM_JS =
        "(function(){"
      + "  if(window.__swb_nav_installed__)return;"
      + "  window.__swb_nav_installed__=true;"
      + "  function b64(s){ try{ return btoa(unescape(encodeURIComponent(s))); }catch(e){ return ''; } }"
      + "  function notify(){"
      + "    try{ if(window.__swb_nav) window.__swb_nav(b64(location.href +"
      + "         '\\u0001' + (document.title||''))); }catch(e){}"
      + "  }"
      + "  var _push = history.pushState;"
      + "  history.pushState = function(){ var r=_push.apply(this, arguments); notify(); return r; };"
      + "  var _replace = history.replaceState;"
      + "  history.replaceState = function(){ var r=_replace.apply(this, arguments); notify(); return r; };"
      + "  window.addEventListener('popstate', notify);"
      + "  window.addEventListener('hashchange', notify);"
      + "  if (document.readyState !== 'loading') { notify(); }"
      + "  else { document.addEventListener('DOMContentLoaded', notify); }"
      + "  window.addEventListener('load', notify);"
      // Title changes don't fire DOM events; observe <title> mutations
      // and re-notify so the tab strip / window title track late
      // updates (most SPAs set the title after their first paint).
      + "  try{ var t=document.querySelector('title'); if(t){ new MutationObserver(notify).observe(t,{childList:true}); } }catch(e){}"
      + "  try{ new MutationObserver(function(muts){"
      + "    for(var i=0;i<muts.length;i++){"
      + "      var add=muts[i].addedNodes;"
      + "      for(var j=0;j<add.length;j++){"
      + "        if(add[j].nodeName==='TITLE'){ new MutationObserver(notify).observe(add[j],{childList:true}); notify(); }"
      + "      }"
      + "    }"
      + "  }).observe(document.documentElement,{childList:true,subtree:true}); }catch(e){}"
      + "})();";

    /** How a browser-initiated popup ({@code window.open}, or a
     *  {@code target="_blank"} / {@code target="…"} link or form) is handled.
     *  Chosen from the View ▸ Popup Behavior menu; applies to tabs created
     *  from now on (the popup handler / document-start shim is wired when a tab
     *  is built and can't be swapped afterwards). */
    public enum PopupMode {
        /** Host each popup in an engine-owned native top-level window — the
         *  engine's default.  The POST verb + body and {@code window.opener}
         *  survive, but the popup is a separate OS window, not a tab. */
        NATIVE("Native"),
        /** Adopt each popup's opener-linked child into a new tab via the adopt
         *  strategy (swingwebview Canvas 18): the engine retains the child it
         *  already drove the request into, so the POST body and
         *  {@code window.opener} survive, now inside a tab. */
        TAB("Tab"),
        /** Suppress popups: block the native popup channel and inject
         *  {@link #POPUP_SUPPRESS_JS} so {@code window.open} and
         *  {@code target="…"} navigations happen in the current window (a POST
         *  form rewritten to {@code _self} submits in-page, body preserved). */
        NONE("None");

        /** Menu label. */
        public final String label;
        PopupMode(String label) { this.label = label; }
    }

    /** The popup strategy applied to tabs created from now on.  Defaults to
     *  {@link PopupMode#NATIVE}. */
    private static volatile PopupMode popupMode = PopupMode.NATIVE;

    /** @return the popup strategy new tabs are built with. */
    public static PopupMode getPopupMode() { return popupMode; }

    /** Select the popup strategy for tabs created from now on.  Existing tabs
     *  keep the behaviour they were built with. */
    public static void setPopupMode(PopupMode mode) {
        if (mode != null) popupMode = mode;
    }

    /** Document-start shim for {@link #suppressPopups} mode.  Polyfills
     *  {@code window.open} to navigate the current window, and rewrites
     *  non-{@code _self} form / anchor targets to {@code _self} on submit /
     *  click so popups land in this tab (a POST form submits in-page, native,
     *  with its body intact).  Idempotent per document. */
    static final String POPUP_SUPPRESS_JS =
        "(function(){"
      + "  if(window.__swb_popup_suppress__)return;"
      + "  window.__swb_popup_suppress__=true;"
      + "  window.open=function(url,name,features){"
      + "    if(url){try{window.location.assign(url);}catch(e){"
      + "      try{window.location.href=url;}catch(e2){}}}"
      + "    return window;"          // truthy: callers don't see 'popup blocked'
      + "  };"
      + "  document.addEventListener('submit',function(e){"
      + "    var f=e.target;"
      + "    if(f&&f.tagName==='FORM'){var t=f.getAttribute('target');"
      + "      if(t&&t!=='_self'){f.setAttribute('target','_self');}}"
      + "  },true);"
      + "  document.addEventListener('click',function(e){"
      + "    var n=e.target;"
      + "    var a=(n&&n.closest)?n.closest('a[target]'):null;"
      + "    if(a){var t=a.getAttribute('target');"
      + "      if(t&&t!=='_self'){a.setAttribute('target','_self');}}"
      + "  },true);"
      + "})();";

    /** A current desktop Safari user-agent string.  Safari reports the frozen
     *  "Intel Mac OS X 10_15_7" platform token even on Apple Silicon / newer
     *  macOS, so this is a faithful value. */
    public static final String SAFARI_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
      + "AppleWebKit/605.1.15 (KHTML, like Gecko) "
      + "Version/18.3 Safari/605.1.15";

    /** A current desktop Chrome (Windows) user-agent string. */
    public static final String CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
      + "AppleWebKit/537.36 (KHTML, like Gecko) "
      + "Chrome/133.0.0.0 Safari/537.36";

    /** A current desktop Edge (Windows) user-agent string — Chrome's UA with
     *  the trailing {@code Edg/…} token. */
    public static final String EDGE_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
      + "AppleWebKit/537.36 (KHTML, like Gecko) "
      + "Chrome/133.0.0.0 Safari/537.36 Edg/133.0.0.0";

    /** A current desktop Firefox (Windows) user-agent string. */
    public static final String FIREFOX_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:135.0) "
      + "Gecko/20100101 Firefox/135.0";

    /** The User-Agent sent for requests, chosen from the View ▸ User Agent
     *  menu.  Applies to tabs created from now on and — via
     *  {@link #applyUserAgent} — the current tab when the selection changes. */
    public enum UserAgentOption {
        /** The engine's built-in User-Agent (no override). */
        DEFAULT("Default", null),
        /** Modern desktop Safari. */
        SAFARI("Safari", SAFARI_UA),
        /** Modern desktop Chrome. */
        CHROME("Chrome", CHROME_UA),
        /** Modern desktop Edge. */
        EDGE("Edge", EDGE_UA),
        /** Modern desktop Firefox. */
        FIREFOX("Firefox", FIREFOX_UA);

        /** Menu label. */
        public final String label;
        /** UA string to send, or {@code null} for the engine default. */
        public final String ua;
        UserAgentOption(String label, String ua) { this.label = label; this.ua = ua; }
    }

    /** The User-Agent applied to tabs created from now on.  Defaults to
     *  {@link UserAgentOption#DEFAULT} (the engine's built-in UA). */
    private static volatile UserAgentOption userAgent = UserAgentOption.DEFAULT;

    /** @return the User-Agent new tabs are built with. */
    public static UserAgentOption getUserAgentOption() { return userAgent; }

    /** Select the User-Agent for tabs created from now on.  Existing tabs keep
     *  their UA until reloaded (see {@link #applyUserAgent}). */
    public static void setUserAgentOption(UserAgentOption option) {
        if (option != null) userAgent = option;
    }

    /** Set the engine-level {@code User-Agent} (the real HTTP request header,
     *  not just {@code navigator.userAgent}).  {@code ua == null} clears the
     *  override back to the engine default.  Invoked reflectively so this still
     *  compiles against a swingwebview that predates {@code setUserAgent}.
     *  @return {@code true} if the method exists and was invoked. */
    static boolean setEngineUserAgent(WebViewComponent wv, String ua) {
        try {
            java.lang.reflect.Method m =
                wv.getClass().getMethod("setUserAgent", String.class);
            m.invoke(wv, ua);
            return true;
        } catch (NoSuchMethodException e) {
            return false; // dependency predates setUserAgent
        } catch (Exception e) {
            return false;
        }
    }

    /** Apply {@code ua} (or clear it with {@code null}) on this tab's engine and
     *  reload so the change takes effect.
     *  @return {@code true} if the engine supports setUserAgent. */
    public boolean applyUserAgent(String ua) {
        boolean ok = setEngineUserAgent(webView, ua);
        if (ok) reload();
        return ok;
    }

    private final WebViewComponent webView;
    private final Listener listener;

    /** Stacks of URLs.  {@code currentUrl} is the URL we believe the page
     *  is at right now; not part of either stack. */
    private final List<String> backStack = new ArrayList<>();
    private final List<String> forwardStack = new ArrayList<>();
    private String currentUrl = "";
    private String currentTitle = "";

    /** When the toolbar/back/forward triggers a navigation we set this
     *  to the target URL so the JS shim's callback doesn't push the
     *  outgoing URL onto the back stack a second time. */
    private String suppressNextRecord = null;

    private final List<ConsoleMessage> consoleBuffer = new ArrayList<>();

    /** Create a normal tab backed by a fresh engine view. */
    public BrowserTab(Listener listener) {
        this(listener, WebViewComponent.create());
    }

    /** Create a tab that hosts an ADOPTED popup child — the engine's own
     *  opener-linked view, into which WebKit already drove the original
     *  request (POST verb + body), with {@code window.opener} intact.  Used
     *  by {@link Listener#onPopupAdopted}.  The caller seeds the URL via
     *  {@link #seedInitialUrl} rather than {@link #load}: a {@code load()}
     *  would issue a GET and discard the popup's in-flight POST. */
    static BrowserTab adopting(Listener listener, WebViewComponent adoptedView) {
        return new BrowserTab(listener, adoptedView);
    }

    private BrowserTab(Listener listener, WebViewComponent webView) {
        super(new BorderLayout());
        this.listener = listener;
        this.webView = webView;
        webView.setDebug(true);
        if (userAgent.ua != null) {
            // Real engine-level User-Agent (HTTP request header).  Set before
            // the first navigation so the initial request carries it.  No-op
            // against a swingwebview that predates setUserAgent.
            setEngineUserAgent(webView, userAgent.ua);
        }
        webView.addOnBeforeLoad(NAV_SHIM_JS);
        webView.addJavascriptCallback("__swb_nav", new WebView.JavascriptCallback() {
            @Override public void run(String arg) {
                // arg is the bind-shim wrapper:
                //   {"name":"__swb_nav","seq":N,"args":["<base64payload>"]}
                // The payload is base64 of "<url>\u0001<title>".
                String b64 = extractFirstStringArg(arg);
                if (b64 == null) return;
                String payload = decodeBase64Utf8(b64);
                if (payload == null) return;
                int sep = payload.indexOf('\u0001');
                String url = sep >= 0 ? payload.substring(0, sep) : payload;
                String title = sep >= 0 ? payload.substring(sep + 1) : "";
                SwingUtilities.invokeLater(() -> onNavigated(url, title));
            }
        });
        webView.addConsoleListener(new ConsoleListener() {
            @Override public void onMessage(ConsoleMessage msg) {
                consoleBuffer.add(msg);
                listener.onConsoleMessage(BrowserTab.this, msg);
            }
        });
        switch (popupMode) {
            case NONE:
                // Keep popups in THIS window.  The document-start shim polyfills
                // window.open (-> location.assign) and rewrites form / anchor
                // targets to _self, so popups never reach the native popup
                // channel; a POST form submits in-page (native, body preserved).
                // Block native popups too, as belt-and-suspenders for anything
                // the shim doesn't catch (e.g. a JS-driven form.submit() with a
                // target).
                webView.addOnBeforeLoad(POPUP_SUPPRESS_JS);
                webView.setPopupHandler(null);
                break;
            case NATIVE:
                // Host each popup in an engine-owned native top-level window
                // (the framework default): the POST verb + body and
                // window.opener survive, but the popup is a separate OS window.
                webView.setPopupHandler(WebViewPopupHandler.DEFAULT);
                break;
            case TAB:
            default:
                // Open popups as TABS via the adopt strategy (swingwebview
                // Canvas 18, dependency v1.2.2).  The two alternatives each lose
                // something: NATIVE_WINDOW preserves the POST but spawns a
                // separate OS window (not a tab); blocking and re-opening
                // e.targetUrl() with setUrl() lands in a tab but issues a GET, so
                // a <form method="post" target="..."> popup drops its body and
                // its opener linkage.  ADOPT gives us both -- the engine retains
                // its own opener-linked child (the view WebKit already drove the
                // original POST verb + body into) and hands it to us to host in a
                // WebViewComponent, so window.opener / postMessage and the POST
                // all survive, now inside a tab.  See the swingwebview README
                // "Adopting popups into a component (a tab)".
                webView.setPopupHandler(new WebViewPopupHandler() {
                    // Phase 1: decide on the native UI thread (synchronous, off
                    // the EDT -- must be fast and must not touch Swing).
                    @Override public PopupDisposition popupDisposition(WebViewPopupEvent e) {
                        return PopupDisposition.ADOPT;
                    }
                    // Phase 2: on the EDT, take over the retained opener-linked
                    // child and host it in a new tab.  Realizing that tab (adding
                    // it to the tabbed pane) is what adopts the child.
                    @Override public void popupAdoptable(WebViewPopupEvent e, long popupId) {
                        WebViewComponent child;
                        try {
                            child = WebViewComponent.adoptPopup(popupId);
                        } catch (RuntimeException ex) {
                            // Unknown / already-adopted id, or a backend where
                            // native adoption isn't wired: drop it rather than
                            // throw on the EDT.
                            return;
                        }
                        listener.onPopupAdopted(BrowserTab.this, child, e.targetUrl());
                    }
                });
                break;
        }
        add(webView, BorderLayout.CENTER);
    }

    public WebViewComponent webView() { return webView; }
    public String currentUrl()        { return currentUrl; }
    public String currentTitle()      { return currentTitle.isEmpty() ? currentUrl : currentTitle; }
    public boolean canGoBack()        { return !backStack.isEmpty(); }
    public boolean canGoForward()     { return !forwardStack.isEmpty(); }
    public List<ConsoleMessage> consoleBuffer() { return consoleBuffer; }

    /** Seed the URL bar / tab title for an adopted popup tab.  The engine
     *  child is already navigating (WebKit drove the original request into
     *  it), so we must NOT {@link #load} -- that would issue a GET and drop
     *  the popup's in-flight POST.  The NAV_SHIM keeps these in sync from the
     *  popup's next navigation onward; this just gives the tab an initial
     *  label.  Called on the EDT after the tab is added. */
    void seedInitialUrl(String url) {
        if (url == null || url.isEmpty()) return;
        currentUrl = url;
        listener.onUrlChanged(this, url);
        listener.onTitleChanged(this, url);
        listener.onNavStateChanged(this);
    }

    /** Toolbar entry point.  Treats blank input as "do nothing"; turns
     *  bare words like "openjdk.org" into a real URL. */
    public void load(String input) {
        String url = normalizeUrl(input);
        if (url == null) return;
        if (!currentUrl.isEmpty() && !currentUrl.equals(url)) {
            backStack.add(currentUrl);
            forwardStack.clear();
        }
        suppressNextRecord = url;
        currentUrl = url;
        webView.setUrl(url);
        listener.onUrlChanged(this, url);
        listener.onNavStateChanged(this);
    }

    public void reload() {
        // Re-setting the URL re-navigates; the JS shim will then fire
        // and update title.  We don't push to the back stack here.
        if (currentUrl.isEmpty()) return;
        suppressNextRecord = currentUrl;
        webView.setUrl(currentUrl);
    }

    public void back() {
        if (backStack.isEmpty()) return;
        String prev = backStack.remove(backStack.size() - 1);
        if (!currentUrl.isEmpty()) forwardStack.add(currentUrl);
        suppressNextRecord = prev;
        currentUrl = prev;
        webView.setUrl(prev);
        listener.onUrlChanged(this, prev);
        listener.onNavStateChanged(this);
    }

    public void forward() {
        if (forwardStack.isEmpty()) return;
        String next = forwardStack.remove(forwardStack.size() - 1);
        if (!currentUrl.isEmpty()) backStack.add(currentUrl);
        suppressNextRecord = next;
        currentUrl = next;
        webView.setUrl(next);
        listener.onUrlChanged(this, next);
        listener.onNavStateChanged(this);
    }

    public void evalJs(String js) {
        webView.eval(js);
    }

    public void openDevTools() {
        webView.openDevTools();
    }

    public void clearConsole() {
        consoleBuffer.clear();
    }

    public void dispose() {
        webView.dispose();
    }

    // --- internals ---

    private void onNavigated(String url, String title) {
        if (url == null || url.isEmpty()) return;
        // Suppression: when our own back/forward/load just kicked off a
        // navigation we don't want the shim's first ping to be treated
        // as a fresh user-initiated nav.  Only the *first* matching
        // ping is suppressed; later pings (e.g. SPA pushState after the
        // page loads) still register.
        if (suppressNextRecord != null && suppressNextRecord.equals(url)) {
            suppressNextRecord = null;
        } else if (!url.equals(currentUrl)) {
            // The page (or a link click, or pushState) moved us to a
            // different URL than the one we last set.  That's a normal
            // forward navigation -- record it.
            if (!currentUrl.isEmpty()) {
                backStack.add(currentUrl);
                forwardStack.clear();
            }
            currentUrl = url;
            listener.onNavStateChanged(this);
        }
        // Title can change repeatedly on the same URL (SPAs that update
        // <title> after data fetches) -- always forward.
        if (title != null && !title.equals(currentTitle)) {
            currentTitle = title;
            listener.onTitleChanged(this, title);
        }
        listener.onUrlChanged(this, url);
    }

    /** Turn URL-bar input into a real URL.  Adds {@code https://} when
     *  the user typed a bare hostname; for input that doesn't look like
     *  a URL at all, falls back to a DuckDuckGo search. */
    static String normalizeUrl(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("http://") || s.startsWith("https://")
            || s.startsWith("file://") || s.startsWith("about:")
            || s.startsWith("data:")) {
            return s;
        }
        // Heuristic: looks like a domain or hostname:port/path.
        if (s.matches("(?i)^[a-z0-9][a-z0-9.-]*\\.[a-z]{2,}([:/].*)?$")
            || s.matches("^localhost(:\\d+)?(/.*)?$")
            || s.matches("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$")) {
            return "https://" + s;
        }
        // Anything else: treat as a search query.
        return "https://duckduckgo.com/?q=" + urlEncode(s);
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * The webview's binding callback delivers the full bind-shim JSON
     * wrapper {@code {"name":"...","seq":...,"args":["<value>"]}}.  We
     * pull out the first arg's string value.  The payload is
     * base64-encoded by the JS shim so the value contains no
     * JSON-special characters and a substring lift is enough.
     */
    private static String extractFirstStringArg(String json) {
        if (json == null) return null;
        int argsIdx = json.indexOf("\"args\":[");
        if (argsIdx < 0) return null;
        int quoteStart = json.indexOf('"', argsIdx + 8);
        if (quoteStart < 0) return null;
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;
        return json.substring(quoteStart + 1, quoteEnd);
    }

    private static String decodeBase64Utf8(String b64) {
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(b64);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
