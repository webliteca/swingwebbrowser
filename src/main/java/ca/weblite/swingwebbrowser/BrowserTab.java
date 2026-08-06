package ca.weblite.swingwebbrowser;

import ca.weblite.webview.ConsoleListener;
import ca.weblite.webview.ConsoleMessage;
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

        /** A page in {@code source} asked to open a popup
         *  ({@code window.open} or a {@code target="_blank"} link).  The
         *  browser opens {@code url} in a new tab instead of letting the
         *  native engine spawn a separate window.  Always invoked on the
         *  Swing EDT. */
        void onPopupRequested(BrowserTab source, String url);
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

    public BrowserTab(Listener listener) {
        super(new BorderLayout());
        this.listener = listener;
        this.webView = WebViewComponent.create();
        webView.setDebug(true);
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
        // Route browser-initiated popups (window.open, target="_blank") into
        // a new tab rather than a native pop-up window.  Returning false
        // blocks the native engine's separate window; we open the requested
        // URL in a fresh tab instead (mirroring how tabbed browsers handle
        // popups).  popupRequested runs on the native UI thread off the EDT,
        // so we must not touch Swing here -- capture the URL and marshal the
        // tab creation to the EDT.
        webView.setPopupHandler(new WebViewPopupHandler() {
            @Override public boolean popupRequested(WebViewPopupEvent e) {
                final String target = e.targetUrl();
                SwingUtilities.invokeLater(
                    () -> listener.onPopupRequested(BrowserTab.this, target));
                return false;
            }
        });
        add(webView, BorderLayout.CENTER);
    }

    public WebViewComponent webView() { return webView; }
    public String currentUrl()        { return currentUrl; }
    public String currentTitle()      { return currentTitle.isEmpty() ? currentUrl : currentTitle; }
    public boolean canGoBack()        { return !backStack.isEmpty(); }
    public boolean canGoForward()     { return !forwardStack.isEmpty(); }
    public List<ConsoleMessage> consoleBuffer() { return consoleBuffer; }

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
