#!/usr/bin/env bash
# Helper for setting up the TV over ADB from a computer on the same network.
# On the TV first: Settings > About > press OK on "Build number" 7 times, then
# Developer options > enable USB / network debugging.
#
#   tools/tv.sh connect 192.168.1.50   connect to the TV (accept the prompt on screen)
#   tools/tv.sh info                   real Android version, RAM, screen density
#   tools/tv.sh install                install apk/glass-home.apk and open it
#   tools/tv.sh set-home               make Glass Home the default home screen
#   tools/tv.sh list                   every package with a launcher icon
#   tools/tv.sh disable <package>...   switch a preinstalled app off (reversible)
#   tools/tv.sh enable <package>...    switch it back on
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
cmd="${1:-help}"
shift || true

case "$cmd" in
  connect)
    adb connect "${1:?usage: tv.sh connect <tv-ip-address>}:5555"
    ;;
  info)
    echo "Android $(adb shell getprop ro.build.version.release) (API $(adb shell getprop ro.build.version.sdk))"
    echo "Model:   $(adb shell getprop ro.product.model) / $(adb shell getprop ro.board.platform)"
    echo "Screen:  $(adb shell wm size | tr -d '\r'), $(adb shell wm density | tr -d '\r')"
    adb shell cat /proc/meminfo | head -n 3
    echo "Current home app:"
    adb shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN | tail -n 1
    ;;
  install)
    adb install -r "$here/apk/glass-home.apk"
    adb shell am start -n tv.glasshome/.HomeActivity
    ;;
  set-home)
    adb shell cmd package set-home-activity tv.glasshome/.HomeActivity
    echo "Press Home on the remote. If the old launcher still appears, disable it:"
    echo "  tools/tv.sh disable <old launcher package>   (find it with: tools/tv.sh info)"
    ;;
  list)
    adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
      | grep / | sed 's#/.*##' | tr -d ' \r' | sort -u
    ;;
  disable)
    [ "$#" -gt 0 ] || { echo "usage: tv.sh disable <package>..."; exit 1; }
    for p in "$@"; do
      if [ "$p" = "tv.glasshome" ]; then echo "refusing to disable Glass Home itself"; continue; fi
      adb shell pm disable-user --user 0 "$p"
    done
    ;;
  enable)
    [ "$#" -gt 0 ] || { echo "usage: tv.sh enable <package>..."; exit 1; }
    for p in "$@"; do adb shell pm enable "$p"; done
    ;;
  *)
    sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
    ;;
esac
