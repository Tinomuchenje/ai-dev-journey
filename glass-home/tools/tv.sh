#!/usr/bin/env bash
# Helper for setting up the TV over ADB from a computer on the same network.
#
#   tools/tv.sh connect 192.168.1.50   connect to the TV
#   tools/tv.sh info                   Android version, RAM, screen, and which app is Home
#   tools/tv.sh backup                 save the lists of installed and disabled packages
#   tools/tv.sh install                install apk/glass-home.apk and open it
#   tools/tv.sh set-home               make Glass Home the default home screen
#   tools/tv.sh list                   every package with a launcher icon
#   tools/tv.sh disable <package>...   switch a preinstalled app off (never a home screen)
#   tools/tv.sh restore                switch everything this script disabled back on
#   tools/tv.sh enable <package>...    switch one package back on
#
# Nothing is ever uninstalled. For a thorough, tested clean-up use the per-TV lists and
# scripts in https://github.com/Gathin23/cleanyourtv, where the safety rules below come from.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
record="$here/tv-backup/disabled-by-glass-home.txt"
cmd="${1:-help}"
shift || true

home_packages() {
  adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
    | grep / | sed 's#/.*##' | tr -d ' \r' | sort -u
}

case "$cmd" in
  connect)
    adb connect "${1:?usage: tv.sh connect <tv-ip-address>}:5555"
    ;;
  info)
    echo "Android $(adb shell getprop ro.build.version.release) (API $(adb shell getprop ro.build.version.sdk))"
    echo "Model:   $(adb shell getprop ro.product.model) / $(adb shell getprop ro.board.platform)"
    echo "Screen:  $(adb shell wm size | tr -d '\r'), $(adb shell wm density | tr -d '\r')"
    adb shell cat /proc/meminfo | head -n 3
    echo "Home screens installed (never disable the TV's own one):"
    home_packages | sed 's/^/  /'
    echo "Home button sends COMMON_HOME (CVTE firmware): $(adb shell getprop ro.CVT_EN_COMMON_HOME_INTENT | tr -d '\r')"
    ;;
  backup)
    dir="$here/tv-backup/$(date +%Y%m%d-%H%M%S)"
    mkdir -p "$dir"
    adb shell pm list packages -f > "$dir/packages.txt"
    adb shell pm list packages -d > "$dir/disabled.txt"
    adb shell getprop > "$dir/getprop.txt"
    echo "Saved to $dir"
    ;;
  install)
    adb install -r "$here/apk/glass-home.apk"
    adb shell am start -n tv.glasshome/.HomeActivity
    ;;
  set-home)
    adb shell cmd package set-home-activity tv.glasshome/.HomeActivity
    echo "Press Home on the remote. The TV's own home screen stays installed and enabled on purpose:"
    echo "on some firmware it is the only one allowed to start at power-on, and the TV will not"
    echo "finish booting without it."
    ;;
  list)
    adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
      | grep / | sed 's#/.*##' | tr -d ' \r' | sort -u
    ;;
  disable)
    [ "$#" -gt 0 ] || { echo "usage: tv.sh disable <package>..."; exit 1; }
    homes="$(home_packages)"
    mkdir -p "$(dirname "$record")"
    for p in "$@"; do
      if printf '%s\n' "$homes" | grep -qx "$p"; then
        echo "REFUSED $p: it is a home screen. With the TV's own home screen disabled, some TVs"
        echo "        boot to a black screen with the remote dead. Leave it enabled."
        continue
      fi
      case "$p" in
        *.fac.*|*factory*|*.directboot.*)
          echo "REFUSED $p: factory and boot packages are left alone."; continue ;;
      esac
      adb shell pm disable-user --user 0 "$p" && echo "$p" >> "$record"
    done
    echo "Wait about 30 seconds before restarting the TV, so the change is written to its storage."
    ;;
  restore)
    [ -f "$record" ] || { echo "Nothing recorded: this script has not disabled anything."; exit 0; }
    sort -u "$record" | while read -r p; do [ -n "$p" ] && adb shell pm enable "$p"; done
    mv "$record" "$record.restored"
    ;;
  enable)
    [ "$#" -gt 0 ] || { echo "usage: tv.sh enable <package>..."; exit 1; }
    for p in "$@"; do adb shell pm enable "$p"; done
    ;;
  *)
    sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
    ;;
esac
