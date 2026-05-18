#!/usr/bin/env bash
# macOS launcher.  Builds (if needed) and runs the demo browser.
#
# Requirements:
#   * macOS 10.13+
#   * JDK 21 (build) / 8+ (runtime)
#
# The native libs (libwebview.dylib, both Intel and Apple Silicon) ship
# inside swingwebview-1.0.3.jar -- no separate install needed.
#
# Rendering mode: the default on macOS is heavyweight (WKWebView is
# embedded as a real NSView via a CALayer-hosting peer).  Pass
# `--lightweight` to use the offscreen path instead -- WKWebView renders
# into a bitmap that gets blitted into a Swing component each frame.
# Useful for comparing the two paths against each other.

set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -d target/lib ]] || [[ ! -f target/swingwebbrowser-1.0-SNAPSHOT.jar ]]; then
    echo "[run-mac] Building..." >&2
    mvn -q package
fi

JAVA_OPTS=()
ARGS=()
for arg in "$@"; do
    case "$arg" in
        --heavyweight) JAVA_OPTS+=(-Dca.weblite.webview.mode=heavyweight);;
        --lightweight) JAVA_OPTS+=(-Dca.weblite.webview.mode=lightweight);;
        *)             ARGS+=("$arg");;
    esac
done

# AWT on macOS needs the main thread for the WebView's CALayer host;
# -XstartOnFirstThread is added automatically by JDK 9+ when needed.
exec java "${JAVA_OPTS[@]}" \
    -Dapple.awt.application.name="Swing Web Browser" \
    -Xdock:name="Swing Web Browser" \
    -cp "target/swingwebbrowser-1.0-SNAPSHOT.jar:target/lib/*" \
    ca.weblite.swingwebbrowser.SwingWebBrowser "${ARGS[@]}"
