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

    /** EXPERIMENT: "redirect popups to the current window" mode.  When on,
     *  each new tab injects {@link #POPUP_SUPPRESS_JS} at document-start and
     *  blocks native popups, so {@code window.open} and {@code target="_blank"}
     *  / {@code target="…"} navigations happen IN the current tab.  A
     *  {@code <form method="post" target="…">} rewritten to {@code _self}
     *  submits in-page, so the browser performs the POST natively and the body
     *  is preserved -- no popup, no new tab, no adoption.  Applies to tabs
     *  created while enabled (a document-start script can't be removed after a
     *  tab is built).  Default from the {@code swingwebbrowser.suppressPopups}
     *  system property. */
    private static volatile boolean suppressPopups =
        Boolean.getBoolean("swingwebbrowser.suppressPopups");

    /** @return whether "redirect popups to current window" mode is on. */
    public static boolean isSuppressPopups() { return suppressPopups; }

    /** Toggle "redirect popups to current window" mode for tabs created from
     *  now on.  Existing tabs keep the behaviour they were built with. */
    public static void setSuppressPopups(boolean on) { suppressPopups = on; }

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

    /** EXPERIMENT: client-side user-agent spoof.  When on, each new tab
     *  injects {@link #UA_SPOOF_JS} at document-start, overriding the
     *  JS-visible {@code navigator.userAgent} (and friends) with
     *  {@link #SAFARI_UA}.  This ONLY affects client-side (JavaScript) UA
     *  sniffing — it does NOT change the {@code User-Agent} HTTP request
     *  header the server sees.  For server-side sniffing use the real
     *  engine-level {@code WebViewComponent.setUserAgent(...)} once it ships
     *  in the swingwebview dependency.  Default from the
     *  {@code swingwebbrowser.spoofUa} system property. */
    private static volatile boolean spoofSafariUa =
        Boolean.getBoolean("swingwebbrowser.spoofUa");

    /** @return whether client-side Safari UA spoofing is on. */
    public static boolean isSpoofSafariUa() { return spoofSafariUa; }

    /** Toggle client-side Safari UA spoofing for tabs created from now on. */
    public static void setSpoofSafariUa(boolean on) { spoofSafariUa = on; }

    /** Document-start shim for {@link #spoofSafariUa}.  Shadows the
     *  navigator UA-related getters with Safari values.  Client-side only —
     *  the HTTP User-Agent header is unchanged. */
    static final String UA_SPOOF_JS =
        "(function(){try{"
      + "  var ua=" + jsString(SAFARI_UA) + ";"
      + "  var av=ua.replace(/^Mozilla\\//,'');"
      + "  Object.defineProperty(navigator,'userAgent',{get:function(){return ua;},configurable:true});"
      + "  Object.defineProperty(navigator,'appVersion',{get:function(){return av;},configurable:true});"
      + "  Object.defineProperty(navigator,'vendor',{get:function(){return 'Apple Computer, Inc.';},configurable:true});"
      + "  Object.defineProperty(navigator,'platform',{get:function(){return 'MacIntel';},configurable:true});"
      + "}catch(e){}})();";

    /** Encode a Java string as a JS string literal (double-quoted, escaped). */
    private static String jsString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else b.append(c);
        }
        return b.append('"').toString();
    }

    /** EXPERIMENT: real engine-level Safari User-Agent (changes the HTTP
     *  User-Agent header, not just navigator.userAgent).  Calls
     *  {@code WebViewComponent.setUserAgent(String)} reflectively, so this
     *  compiles against the published swingwebview and activates only once
     *  the dependency is bumped to a build that has the method (see
     *  swingwebview PR #44 / Canvas 21).  Default from the
     *  {@code swingwebbrowser.realUa} system property. */
    private static volatile boolean realSafariUa =
        Boolean.getBoolean("swingwebbrowser.realUa");

    /** @return whether the real (HTTP-header) Safari UA override is on. */
    public static boolean isRealSafariUa() { return realSafariUa; }

    /** Toggle the real Safari UA override for tabs created from now on. */
    public static void setRealSafariUa(boolean on) { realSafariUa = on; }

    /** Reflectively invoke {@code WebViewComponent.setUserAgent(String)}.
     *  @return {@code true} if the method exists and was invoked;
     *          {@code false} when the swingwebview dependency predates it. */
    static boolean setEngineUserAgent(WebViewComponent wv, String ua) {
        try {
            java.lang.reflect.Method m =
                wv.getClass().getMethod("setUserAgent", String.class);
            m.invoke(wv, ua);
            return true;
        } catch (NoSuchMethodException e) {
            return false; // dependency predates Canvas 21 setUserAgent
        } catch (Exception e) {
            return false;
        }
    }

    /** Apply (or clear) the real Safari UA on this tab's engine and reload so
     *  the change takes effect.
     *  @return {@code true} if the engine supports setUserAgent. */
    public boolean applyRealUserAgent(boolean on) {
        boolean ok = setEngineUserAgent(webView, on ? SAFARI_UA : null);
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

    public BrowserTab(Listener listener) {
        super(new BorderLayout());
        this.listener = listener;
        this.webView = WebViewComponent.create();
        webView.setDebug(true);
        if (spoofSafariUa) {
            // EXPERIMENT: client-side UA spoof.  Injected first so it wins
            // before any page script reads navigator.userAgent.
            webView.addOnBeforeLoad(UA_SPOOF_JS);
        }
        if (realSafariUa) {
            // EXPERIMENT: real engine-level UA (HTTP header).  Set before the
            // first navigation so the initial request carries it.  No-op
            // against a swingwebview that predates setUserAgent.
            setEngineUserAgent(webView, SAFARI_UA);
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
        if (suppressPopups) {
            // EXPERIMENT: keep popups in THIS window.  The document-start shim
            // polyfills window.open (-> location.assign) and rewrites form /
            // anchor targets to _self, so popups never reach the native popup
            // channel; a POST form submits in-page (native, body preserved).
            // Block native popups too, as belt-and-suspenders for anything the
            // shim doesn't catch (e.g. a JS-driven form.submit() with target).
            webView.addOnBeforeLoad(POPUP_SUPPRESS_JS);
            webView.setPopupHandler(null);
        } else {
            // INTERIM popup handling (pending swingwebview STORY-005-004, popup
            // adoption).  The previous tab path blocked the native popup window
            // and re-opened e.targetUrl() in a fresh tab via setUrl(url) -- a
            // GET, so <form method="post" target="..."> popups lost their POST
            // body and opener linkage (window.opener / postMessage broke).
            //
            // Returning true instead lets the native engine own the popup:
            // WebKit drives the ORIGINAL request (POST verb + body) into its
            // opener-linked child, so form-POST and OAuth "sign-in with popup"
            // flows work correctly.  The trade-off is that popups open in a
            // SEPARATE NATIVE WINDOW rather than a tab.
            //
            // Once popup adoption lands in the swingwebview dependency, replace
            // this with popupDisposition() -> ADOPT + popupAdoptable() ->
            // WebViewComponent.adoptPopup(popupId) to host that same
            // opener-linked child (POST intact) inside a real tab.  See the
            // swingwebview README "Adopting popups into a component".
            webView.setPopupHandler(new WebViewPopupHandler() {
                @Override public boolean popupRequested(WebViewPopupEvent e) {
                    return true; // native opener-linked window; preserves POST
                }
            });
        }
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
