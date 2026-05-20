#!/usr/bin/env bash
# macOS launcher.  Builds (if needed) and runs the demo browser.
#
# Requirements:
#   * macOS 10.13+
#   * JDK 21 (build) / 8+ (runtime)
#
# The native libs (libwebview.dylib, both Intel and Apple Silicon) ship
# inside swingwebview-1.0.5.jar -- no separate install needed.

set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -d target/lib ]] || [[ ! -f target/swingwebbrowser-1.0-SNAPSHOT.jar ]]; then
    echo "[run-mac] Building..." >&2
    mvn -q package
fi

# AWT on macOS needs the main thread for the WebView's CALayer host;
# -XstartOnFirstThread is added automatically by JDK 9+ when needed.
exec java -Dapple.awt.application.name="Swing Web Browser" \
    -Xdock:name="Swing Web Browser" \
    -cp "target/swingwebbrowser-1.0-SNAPSHOT.jar:target/lib/*" \
    ca.weblite.swingwebbrowser.SwingWebBrowser "$@"
