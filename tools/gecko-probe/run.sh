#!/bin/sh
# Builds the GeckoView prototype and runs each of its checks on the phone adb sees, then removes
# it. The device log is read from the start of each run on, not cleared. Exits 0 only if every
# check holds. Nothing should leave the phone: every connection goes to a fake SOCKS5 listener
# that refuses it, or to the probe's own listeners on 127.0.0.1. The listeners see only what
# reaches them; what the probe sends on the phone's real networks, around them, is counted by
# the phone itself (netstats, which leaves loopback out) and must be nothing.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
if [ -z "${ANDROID_HOME:-}" ]; then
    ANDROID_HOME=$(sed -n 's/^sdk\.dir=//p' "$root/local.properties")
    export ANDROID_HOME
fi
adb="$ANDROID_HOME/platform-tools/adb"
package=io.github.firebirks.booruvault.probe.gecko

# Gecko pauses a page that is not in front: the screen must stay on, and unlocked, throughout.
if ! "$adb" shell dumpsys power | grep -q 'mWakefulness=Awake' || "$adb" shell dumpsys window | grep -q 'isKeyguardShowing=true'; then
    echo "Unlock the phone and keep its screen on while the checks run (about seven minutes)." >&2
    exit 2
fi

"$root/gradlew" -p "$here" -q assembleDebug
"$adb" install -r "$here/app/build/outputs/apk/debug/app-debug.apk" > /dev/null
trap '"$adb" uninstall "$package" > /dev/null 2>&1 || true' EXIT
uid=$("$adb" shell cmd package list packages -U "$package" | tr -d '\r' | sed -n 's/.*uid:\([0-9]*\).*/\1/p' | head -n 1)
if [ -z "$uid" ]; then
    echo "Could not tell the probe's uid: its traffic on the phone's networks cannot be counted." >&2
    exit 2
fi

# The bytes the probe's uid has sent on the phone's networks so far, loopback aside.
sent() {
    "$adb" shell dumpsys netstats --poll > /dev/null
    "$adb" shell dumpsys netstats detail | tr -d '\r' | awk -v uid="$uid" '
        /ident=/ { on = index($0, " uid=" uid " ") > 0 && index($0, "tag=0x0") > 0 }
        on && / tb=/ { for (i = 1; i <= NF; i++) if ($i ~ /^tb=/) { sub(/^tb=/, "", $i); total += $i } }
        END { print total + 0 }'
}

# run <mode> <pattern of its last line>: the PROBE lines of one run, waited for up to 90 seconds.
run() {
    # The run before is stopped first, so that nothing it still logs falls into this one.
    "$adb" shell am force-stop "$package"
    sleep 2
    since=$("$adb" shell date "'+%m-%d %H:%M:%S.000'" | tr -d '\r')
    before=$(sent)
    "$adb" shell am start -S -W --activity-clear-task -n "$package/.GeckoProbeActivity" --es mode "$1" > /dev/null
    tries=0
    while :; do
        out=$("$adb" shell "logcat -d -T '$since'" | tr -d '\r' | sed -n 's/.*PROBE //p')
        if echo "$out" | grep -q "$2" || [ "$tries" -ge 90 ]; then
            break
        fi
        sleep 1
        tries=$((tries + 1))
    done
    after=$(sent)
    echo "$out"
    echo "netstats.sent $((after - before))"
}

status=0
fail() {
    echo "FAIL: $1" >&2
    status=1
}

on=$(run rtc-on '^SUMMARY natives ')
off=$(run rtc-off '^SUMMARY natives ')
proxy=$(run proxy '^SUMMARY web2')
idle=$(run idle-default '^SUMMARY idle')
hardened=$(run idle-hardened '^SUMMARY idle')
closed=$(run idle-closed '^SUMMARY idle')
storage=$(run storage '^SUMMARY web3')
engine=$(run engine '^SUMMARY engine done')
hidden=$(run hidden '^SUMMARY hidden done')
surface=$(run surface '^SUMMARY surface done')
locale=$(run locale '^SUMMARY locale done')
"$adb" shell am force-stop "$package"

echo "$on" | grep '^gecko ' | head -1 || true
echo "WebRTC on:  $(echo "$on" | grep '^SUMMARY leaks' || echo 'no summary')"
echo "WebRTC off: $(echo "$off" | grep '^SUMMARY leaks' || echo 'no summary')"
echo "Through the proxy:"
echo "$proxy" | grep -E '^socks\.connect|^loopback\.hit' | sort | uniq -c || true
echo "Connections Gecko made by itself in a minute, with its own preferences:"
echo "$idle" | sed -n 's/^socks\.connect atyp=[0-9]* //p' | sort | uniq -c || true
echo "The same with the hardening preferences:"
contacted=$(echo "$hardened" | sed -n 's/^socks\.connect atyp=[0-9]* //p' | sort | uniq -c)
echo "${contacted:-      none}"
echo "Bytes sent on the phone's networks, around the probe's listeners:"
for pair in "WebRTC on:$on" "WebRTC off:$off" "proxy:$proxy" "own preferences:$idle" "hardening:$hardened" "app's, Orbot down:$closed" "storage:$storage" "engine:$engine" "hidden:$hidden"; do
    echo "  ${pair%%:*}: $(echo "${pair#*:}" | sed -n 's/^netstats\.sent //p')"
done
echo "Storage in a later private session:"
echo "$storage" | grep '^web3\.after' || echo "  no result"

echo "$on" | grep -q '^SUMMARY leaks none$' && fail "with WebRTC on the page found it nowhere: the probe no longer finds what it looks for."
echo "$off" | grep -q '^SUMMARY leaks none$' || fail "with WebRTC off the page still reached it."
echo "$proxy" | grep -q '^socks\.connect atyp=3 127\.0\.0\.1:' || fail "a request to 127.0.0.1 did not go to the proxy."
echo "$proxy" | grep -q '^socks\.connect atyp=3 site\.invalid:443' || fail "a name did not reach the proxy unresolved."
echo "$proxy" | grep -q '^loopback\.hit' && fail "a request reached 127.0.0.1 around the proxy."
echo "$hardened" | grep -q '^socks\.connect' && fail "with the hardening preferences Gecko still connected somewhere by itself."
# Only the modes with the app's own preferences must send nothing: the others run Gecko with its
# own, or with the prototype's shorter list, and are there to show what the app's list holds back.
for pair in "app's, Orbot down:$closed" "engine:$engine" "hidden:$hidden"; do
    echo "${pair#*:}" | grep -q '^netstats\.sent 0$' || fail "${pair%%:*}: with the app's preferences the probe sent something on the phone's networks, around its proxy."
done
echo "$storage" | grep -q '^web3\.after\.localStorage null$' || fail "localStorage outlived the private session."
echo "$storage" | grep -q '^web3\.after\.cache null$' || fail "Cache Storage outlived the private session."
echo "$storage" | grep -q '^web3\.after\.idb undefined$' || fail "IndexedDB outlived the private session."
echo "The app's own preferences and extension:"
echo "$engine" | grep -E '^engine (route|extension|languages)|^socks\.connect|^page\.hit' || echo "  no result"
echo "$engine" | grep -q 'FAILED' && fail "the app's preferences or extension failed on the engine."
echo "$engine" | grep -q '^socks\.connect atyp=3 through-tor\.invalid' || fail "with the app's route set, a name did not reach the proxy."
echo "$engine" | grep -q '^socks\.connect atyp=3 127\.0\.0\.1:' || fail "with the app's route set, the phone's own address did not go to the proxy."
echo "$engine" | grep -q '^page\.hit /cookie\.html' || fail "with the direct route set, the page was not reached directly."
echo "$engine" | grep -q '^engine extension cookies: \[served, js, given\]' || fail "the app's extension did not read or set a private session's cookies."
echo "$engine" | grep -q '^socks\.connect atyp=3 back-on-tor\.invalid' || fail "the route did not go back to the proxy."
echo "$engine" | grep -q '^socks\.connect atyp=3 from-okhttp\.invalid:443 credential=booruvault/booruvault$' || fail "the app's client did not give the proxy the app's credential."
echo "$engine" | grep -q '^socks\.connect atyp=3 through-tor\.invalid:80 credential=booruvault/booruvault$' || fail "the engine did not give the proxy the app's credential."
echo "$engine" | grep -q '^socks\.connect atyp=3 back-on-tor\.invalid:80 credential=booruvault/booruvault$' || fail "back on the proxy, the engine did not give it the app's credential."
echo "$engine" | grep -q '^engine languages on TOR: en-US, en$' || fail "through Tor the engine does not ask pages in English."
echo "$engine" | grep -q '^page\.hit /cookie\.html asked in eo,tlh' || fail "directly, the engine did not ask pages in the languages it was given back."
echo "A page read in a session on no view, as the hidden browser reads it:"
echo "$hidden" | grep -E '^hidden (page|images|setCookies)|^page\.hit' || echo "  no result"
echo "$hidden" | grep -q 'FAILED' && fail "the hidden session failed on the engine."
echo "$hidden" | grep -q '^hidden page (no images): state=complete first=true late=true cookie=true' || fail "the page's HTML, with what its script added and the cookie handed over, was not read from a session on no view."
echo "$hidden" | sed '/^hidden images on/,$d' | grep -q '^page\.hit /pixel\.png' && fail "an image was asked for with images off."
echo "$hidden" | sed -n '/^hidden images on/,$p' | grep -q '^page\.hit /pixel\.png' || fail "no image was asked for with images back on: the check no longer tells the two apart."
echo "What a page finds of the phone, with the app's preferences:"
echo "$surface" | grep '^surface\.' || echo "  no result"
echo "$surface" | grep -q '^surface\.secure true$' || fail "the surface page was not a secure context: what it found says nothing."
echo "$surface" | grep -q '^surface\.PublicKeyCredential undefined$' || fail "a page can reach WebAuthn, which ends an app without Google Play services."
echo "$surface" | grep -q '^surface\.wakeLock false$' || fail "a page can hold the screen awake."
echo "$surface" | grep -q '^surface\.speechSynthesis false$' || fail "a page can hand text to the phone's speech app."
echo "$surface" | grep -q '^surface\.eme ' || fail "the surface page did not ask for DRM."
echo "$surface" | grep -q '^surface\.eme granted$' && fail "a page can get DRM, whose keys are fetched around the proxy."
echo "$surface" | grep -q '^surface\.RTCPeerConnection undefined$' || fail "a page can reach WebRTC with the app's preferences."
echo "What a page's scripts read of the language and the time zone, as the app sets them through Tor:"
echo "$locale" | grep -E '^locale\.|^page\.hit' || echo "  no result"
echo "$locale" | grep -q '^page\.hit /locale\.html asked in en-US,en;q=0\.9$' || fail "through Tor a page is not asked in English alone."
echo "$locale" | grep -q '^locale\.navigator\.languages en-US,en$' || fail "through Tor a page's scripts read another language."
echo "$locale" | grep -q '^locale\.intl en-US en-US$' || fail "through Tor a page's dates and numbers are not in English."
echo "$locale" | grep -q '^locale\.timeZone .* offset=0$' || fail "through Tor a page reads the phone's time zone."
[ "$status" -eq 0 ] && echo "OK: every check holds on GeckoView."
exit "$status"
