#!/usr/bin/env bash
# Linux launcher.  Builds (if needed) and runs the demo browser.
#
# Requirements (system packages):
#   * libgtk-3-0
#   * libwebkit2gtk-4.1-0 (or libwebkit2gtk-4.0-37 on older distros)
#   * libxt6
#
# On Debian / Ubuntu:
#   sudo apt install libgtk-3-0 libwebkit2gtk-4.1-0 libxt6
#
# The native libs ship inside the swingwebview-1.0.3.jar -- no separate
# install of the swingwebview library is needed.

set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -d target/lib ]] || [[ ! -f target/swingwebbrowser-1.0-SNAPSHOT.jar ]]; then
    echo "[run-linux] Building..." >&2
    mvn -q package
fi

# The lightweight WebView (default on Linux) renders WebKit into an
# offscreen GTK window and blits the pixels into a Swing component.
# It composites cleanly with all Swing widgets.  To force the
# heavyweight path instead, pass `--heavyweight`.
JAVA_OPTS=()
for arg in "$@"; do
    case "$arg" in
        --heavyweight) JAVA_OPTS+=(-Dca.weblite.webview.mode=heavyweight); shift;;
        --lightweight) JAVA_OPTS+=(-Dca.weblite.webview.mode=lightweight); shift;;
    esac
done

exec java "${JAVA_OPTS[@]}" \
    -cp "target/swingwebbrowser-1.0-SNAPSHOT.jar:target/lib/*" \
    ca.weblite.swingwebbrowser.SwingWebBrowser "$@"
