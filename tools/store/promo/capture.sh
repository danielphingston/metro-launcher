#!/usr/bin/env bash
# Records one screen clip per new feature from a phone over adb, into captures/<id>.mp4.
#
#   tools/store/promo/capture.sh              # every scene in scenes.tsv
#   tools/store/promo/capture.sh layouts me   # only these
#   NO_DEMO=1 tools/store/promo/capture.sh    # keep the real status bar
#
# For each scene it prints what to do, records while you do it (Enter stops), pulls the
# clip and lets you keep it, retake it or skip it. The layouts scene drives itself.
# Needs: adb, a phone with USB debugging on, Metro Launcher installed and set as home.
set -u
cd "$(dirname "$0")"
PKG=io.github.danielphingston.metro
OUT=captures
mkdir -p "$OUT"

adb get-state >/dev/null 2>&1 || { echo "No device: plug the phone in and allow USB debugging."; exit 1; }
adb shell pm path "$PKG" >/dev/null 2>&1 || { echo "Metro Launcher ($PKG) is not installed."; exit 1; }

sh_() { adb shell "$@" </dev/null >/dev/null 2>&1; }
home() { sh_ am start -n "$PKG/.ui.MainActivity"; sleep 1.5; }
layout() { sh_ am start -a android.intent.action.VIEW -d "metro://layout/$1"; }

# Clean status bar (fixed clock, full battery, no notification icons). Not every phone obeys it.
demo_on() {
  [ -n "${NO_DEMO:-}" ] && return
  sh_ settings put global sysui_demo_allowed 1
  local b="am broadcast -a com.android.systemui.demo -e command"
  sh_ $b enter
  sh_ $b clock -e hhmm 0941
  sh_ $b battery -e level 100 -e plugged false
  sh_ $b network -e wifi show -e level 4 -e mobile show -e level 4 -e datatype none
  sh_ $b notifications -e visible false
}
demo_off() {
  [ -n "${NO_DEMO:-}" ] && return
  sh_ am broadcast -a com.android.systemui.demo -e command exit
}
trap 'adb shell pkill -INT screenrecord >/dev/null 2>&1; demo_off' EXIT

steps() {
  case "$1" in
    customise) cat <<'E'
  1. On start, press and hold a tile, tap the pencil badge (bottom left).
  2. Background: pick a colour, then drag the transparency slider.
  3. Icon: "from icon pack..." and pick a glyph. Then "my photo..." on another tile.
  4. Tap empty space to leave edit mode so the finished tiles show.
E
;;
    undo) cat <<'E'
  1. Press and hold a tile, unpin it with the X. Resize another one.
  2. Tap the floating "undo" pill twice: both come back.
E
;;
    layouts) cat <<'E'
  Needs two or more layouts (settings > start > manage layouts), e.g. "work" and "weekend",
  with different wallpapers. The script switches with metro://layout/next by itself.
  Afterwards (optional, second take): type "layout weekend" in search and tap the row.
E
;;
    gestures) cat <<'E'
  Set them first in settings > gestures (double tap: search, pinch: app list,
  two-finger swipe down: notifications). Then on start: double tap, back, pinch, back,
  two-finger swipe down. Avoid "turn the screen off": it records black.
E
;;
    me) cat <<'E'
  Pin it first ("+" > live tiles > me) with a few real notifications waiting
  (3+ apps). Just watch it: the count flips to each app's newest message and back.
  Tap it once at the end to open one.
E
;;
    feed) cat <<'E'
  1. "+" > metro plus > feed. Choose lemmy (e.g. technology@lemmy.world) or rss/atom,
     add one or two feeds, save.
  2. Resize it to wide/large and let two or three headlines slide in.
  3. Tap a headline: it opens in your Lemmy app or the browser.
E
;;
    tracking) cat <<'E'
  Only while a ride, food order or parcel is on its way (Uber, Swiggy, Zomato, Amazon...).
  Pin "+" > metro plus > tracking, show the ETA, tap the OTP chip, tap the tile.
  Nothing live right now? Press s to skip and come back for it later.
E
;;
    widgets) cat <<'E'
  "+" > widgets, search and pick one (clock, calendar, notes). Press and hold it,
  drag its corner handle bigger, then smaller. Scroll or tap inside it to show it works.
E
;;
    *) echo "  (no notes for $1)";;
  esac
}

# Scenes that run themselves while recording.
auto() {
  case "$1" in
    layouts) home; sleep 2; for _ in 1 2 3; do layout next; sleep 4; done; return 0;;
    *) return 1;;
  esac
}

record() {
  local id=$1 dev="/sdcard/metro_promo_$1.mp4"
  sh_ rm -f "$dev"
  # 180 s is screenrecord's own limit. High bit rate: the clip gets scaled down later anyway.
  adb shell screenrecord --bit-rate 24000000 --time-limit 180 "$dev" </dev/null >/dev/null 2>&1 &
  local pid=$!
  sleep 1
  if auto "$id"; then
    echo "  recorded automatically."
  else
    read -r -p "  Recording... press Enter when done. " _
  fi
  sh_ pkill -INT screenrecord
  wait "$pid" 2>/dev/null
  sleep 1.5                      # let the phone finish writing the file
  adb pull "$dev" "$OUT/$id.mp4" >/dev/null && sh_ rm -f "$dev"
  echo "  saved $OUT/$id.mp4 ($(du -h "$OUT/$id.mp4" | cut -f1))"
}

mapfile -t ALL < <(grep -v '^#' scenes.tsv | cut -f1 | grep .)
if [ $# -gt 0 ]; then SCENES=("$@"); else SCENES=("${ALL[@]}"); fi

demo_on
for id in "${SCENES[@]}"; do
  echo
  echo "== $id =="
  steps "$id"
  while true; do
    read -r -p "  Enter: record   s: skip   q: quit > " a
    case "$a" in s) break;; q) exit 0;; esac
    home
    record "$id"
    read -r -p "  Keep it? (Enter = yes, r = retake) > " a
    [ "$a" = r ] || break
  done
done
echo
echo "Done. Trim times go in scenes.tsv; then run tools/store/promo/compose.sh"
