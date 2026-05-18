# swingwebbrowser

A small, multi-tab demo web browser written in Java 21 + Swing.  Built
as a showcase for the
[swingwebview](https://github.com/webliteca/swingwebview) component
(the JComponent that embeds a native WebView -- WebKit on macOS /
Linux, Chromium WebView2 on Windows).

## What it does

Everything a "basic browser" needs, deliberately in as little Swing
code as possible so the
[`WebViewComponent`](https://github.com/webliteca/swingwebview/blob/main/src/ca/weblite/webview/swing/WebViewComponent.java)
APIs being demonstrated stand out:

* **Address bar** -- type a URL or a search query.  Bare hostnames are
  auto-`https://`'d; anything that doesn't look like a URL is
  forwarded to DuckDuckGo.
* **Back / forward / reload / home** -- per-tab navigation history,
  driven by a JS shim that hooks `pushState` / `replaceState` /
  `popstate` so the back stack reflects link clicks and SPA
  navigation, not only URL-bar input.
* **Tabs** -- new tab, middle-click or ✕ to close.  Each tab owns its
  own `WebViewComponent` and its own console buffer.  The window
  title and tab strip both follow the active page's `<title>`.
* **Bookmarks** -- starable on the toolbar, persisted to
  `~/.swingwebbrowser/bookmarks.tsv`.  Saved bookmarks appear as
  menu items under *Bookmarks*; full management dialog via
  *Bookmarks → Manage Bookmarks…*.
* **History** -- every navigation is appended (with timestamp) to
  `~/.swingwebbrowser/history.tsv`.  *History → Show History…* opens
  a sortable table you can double-click to reopen any URL.
* **Developer console** -- collapsible bottom panel that captures
  every `console.{log,info,warn,error,debug}` call from the active
  tab (colorised by level), plus a one-line JS eval prompt and a
  one-click "Open native DevTools" button (uses the WebView2 / WebKit
  inspector on Windows / Linux).

## Requirements

* JDK 21 (build) / 8 or newer (runtime -- the project compiles to
  Java 21 bytecode but only uses APIs available since 8).
* Maven 3.9+.
* Platform-specific WebView runtime:
  * **macOS** -- nothing extra; uses the system WKWebView.
  * **Linux** -- `libgtk-3-0`, `libwebkit2gtk-4.1-0` (or `-4.0-37`
    on older distros), `libxt6`.
  * **Windows** -- the Microsoft Edge WebView2 Runtime.  Ships
    with Windows 11 and current Edge; on older Windows install the
    Evergreen runtime from
    <https://developer.microsoft.com/microsoft-edge/webview2/>.

The native libraries are bundled inside the
[`ca.weblite:webview:1.0.3`](https://repo1.maven.org/maven2/ca/weblite/webview/1.0.3/)
artifact on Maven Central -- no extra install step for the library
itself.

## Build & run

```bash
# Linux / macOS
./run-linux.sh         # or ./run-mac.sh
# Windows
run-windows.bat
```

The launcher scripts call `mvn package` the first time, then `java`
with the built jar plus its dependencies on the classpath.

Or, manually:

```bash
mvn package
java -cp "target/swingwebbrowser-1.0-SNAPSHOT.jar:target/lib/*" \
     ca.weblite.swingwebbrowser.SwingWebBrowser
# Pass an initial URL:
java -cp "target/swingwebbrowser-1.0-SNAPSHOT.jar:target/lib/*" \
     ca.weblite.swingwebbrowser.SwingWebBrowser https://openjdk.org
```

### Mode override

`WebViewComponent.create()` picks heavyweight (macOS / Windows) or
lightweight (Linux) by default.  To force a mode:

```bash
./run-linux.sh --heavyweight     # GTK reparenting; native input
./run-linux.sh --lightweight     # offscreen + Swing-composited (default)

./run-mac.sh   --heavyweight     # WKWebView embedded as NSView (default)
./run-mac.sh   --lightweight     # WKWebView snapshotted into a BufferedImage
```

Or set the system property directly:
`-Dca.weblite.webview.mode=heavyweight|lightweight`.

The lightweight path is interesting on macOS as a side-by-side
comparison: heavyweight gives the full native WKWebView (proper
hardware-accelerated scrolling, real text input, native context menus)
while lightweight composites cleanly with Swing widgets, popups and
overlays at the cost of a per-frame snapshot/blit and synthesized
input.

## Keyboard shortcuts

| Action | Shortcut |
|---|---|
| Focus URL bar | ⌘L / Ctrl-L |
| Back / Forward | Alt-← / Alt-→ |
| Reload | ⌘R / Ctrl-R |
| New Tab | ⌘T / Ctrl-T |
| Close Tab | ⌘W / Ctrl-W |
| Bookmark this Page | ⌘D / Ctrl-D |
| Toggle Developer Console | ⌘⇧J / Ctrl-Shift-J |
| Quit | ⌘Q / Ctrl-Q |

## How it works

A short tour of the source files (under
`src/main/java/ca/weblite/swingwebbrowser/`):

| File | Responsibility |
|---|---|
| `SwingWebBrowser.java` | `main()` -- installs [FlatLaf](https://www.formdev.com/flatlaf/) and opens a `BrowserFrame`. |
| `BrowserFrame.java` | The JFrame -- toolbar, tab strip, menu bar, and the collapsible dev console. |
| `BrowserTab.java` | One `WebViewComponent` plus its back/forward stacks and console buffer.  Injects a JS shim into every loaded page so `pushState` / `popstate` / `hashchange` navigations and `<title>` changes are observable from Java. |
| `DevConsolePanel.java` | Captures every `console.*` call from the active tab, renders them with per-level colours, runs JS from the eval prompt, opens the native DevTools window. |
| `BookmarkStore.java` | Tab-separated file persistence for bookmarks. |
| `HistoryStore.java` | Append-only TSV log of every navigated URL. |

### Demonstrated `WebViewComponent` features

In rough order of how they show up in the source:

* `WebViewComponent.create()` -- factory that picks heavyweight or
  lightweight per platform.
* `setUrl(String)` -- navigation; all back / forward / bookmark /
  history-replay paths go through this single method.
* `setDebug(true)` -- enables the platform DevTools / inspector.
* `addOnBeforeLoad(String)` -- injects the
  pushState-and-title-tracking JS shim at document-start for every
  navigation.
* `addJavascriptCallback(String, JavascriptCallback)` -- the JS shim
  posts URL/title updates back via a `window.__swb_nav(...)`
  callback.  Demonstrates the Java-side parsing of the bind-shim
  envelope (`{"name":..., "seq":..., "args":["..."]}`).
* `eval(String)` -- the dev console's one-line prompt feeds straight
  into this.
* `addConsoleListener(ConsoleListener)` -- the dev console subscribes
  to every captured `console.*` line.
* `openDevTools()` -- one-click access to the platform's native
  inspector.

## License

MIT.  See `LICENSE`.

## Credits

* WebView library: [swingwebview](https://github.com/webliteca/swingwebview) by Steve Hannah.
* Original webview: [webview](https://github.com/zserge/webview) by Serge Zaitsev.
