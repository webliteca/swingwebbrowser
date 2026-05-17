@echo off
REM Windows launcher.  Builds (if needed) and runs the demo browser.
REM
REM Requirements:
REM   * Windows 10/11 with the Microsoft Edge WebView2 Runtime
REM     (ships with Windows 11 / current Edge; on older Windows install
REM      the Evergreen runtime from
REM      https://developer.microsoft.com/microsoft-edge/webview2/)
REM   * JDK 21 (build) / 8+ (runtime)

setlocal
pushd "%~dp0"

if not exist "target\swingwebbrowser-1.0-SNAPSHOT.jar" goto :build
if not exist "target\lib" goto :build
goto :run

:build
echo [run-windows] Building...
call mvn -q package
if errorlevel 1 exit /b 1

:run
java -cp "target\swingwebbrowser-1.0-SNAPSHOT.jar;target\lib\*" ca.weblite.swingwebbrowser.SwingWebBrowser %*

popd
endlocal
