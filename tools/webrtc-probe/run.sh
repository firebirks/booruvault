#!/bin/sh
# Builds the WebRTC probe and runs it on the phone adb sees: once with no script, where the page
# must find WebRTC in its frames, which proves the probe still finds it, and once with the app's
# own script, where it must find it nowhere and every function the script replaced must look
# native. Exits 0 only if both hold. The probe is a separate app, removed when done: the app on
# the phone, and its vault, are not touched.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
if [ -z "${ANDROID_HOME:-}" ]; then
    ANDROID_HOME=$(sed -n 's/^sdk\.dir=//p' "$root/local.properties")
    export ANDROID_HOME
fi
adb="$ANDROID_HOME/platform-tools/adb"
package=io.github.firebirks.booruvault.probe.webrtc

"$root/gradlew" -p "$here" -q assembleDebug
"$adb" install -r "$here/app/build/outputs/apk/debug/app-debug.apk" > /dev/null
trap '"$adb" uninstall "$package" > /dev/null 2>&1 || true' EXIT

run() {
    "$adb" logcat -c
    # Stopped first and started in a task of its own: a page already up would not run again.
    "$adb" shell am start -S -W --activity-clear-task -n "$package/.ProbeActivity" --es mode "$1" > /dev/null
    # The page sums up five seconds after it has run, which may take a while: waited for, up to a minute.
    tries=0
    while :; do
        out=$("$adb" logcat -d -s WebRtcProbe:I | sed -n 's/.*PROBE //p')
        if echo "$out" | grep -q '^SUMMARY natives ' || [ "$tries" -ge 60 ]; then
            break
        fi
        sleep 1
        tries=$((tries + 1))
    done
    echo "$out"
}

bare=$(run none)
guarded=$(run app)
"$adb" shell am force-stop "$package"

echo "$bare" | grep '^webview ' || true
echo "Without the script:"
echo "$bare" | grep '^SUMMARY leaks' || echo "no summary"
echo "With the app's script:"
echo "$guarded" | grep -E '^script |^SUMMARY' || echo "no summary"

status=0
if ! echo "$bare" | grep -q '^script none$' || ! echo "$guarded" | grep -q '^script '; then
    echo "FAIL: the probe did not run as asked." >&2
    status=1
fi
if echo "$bare" | grep -q '^SUMMARY leaks none$'; then
    echo "FAIL: without the script the page found no WebRTC in its frames: the probe no longer finds what it looks for." >&2
    status=1
fi
if ! echo "$guarded" | grep -q '^script app$'; then
    echo "FAIL: the WebView runs no document-start script." >&2
    status=1
fi
if ! echo "$guarded" | grep -q '^SUMMARY leaks none$'; then
    echo "FAIL: with the app's script the page still reached WebRTC." >&2
    status=1
fi
if ! echo "$guarded" | grep -q '^SUMMARY natives ok$'; then
    echo "FAIL: a function the script replaced does not look native." >&2
    status=1
fi
[ "$status" -eq 0 ] && echo "OK: the app's script keeps WebRTC out of every frame the page reached."
exit "$status"
