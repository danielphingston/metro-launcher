#!/usr/bin/env bash
# Turns captures/<id>.mp4 into promo clips in the style of the first promo video: accent glow on
# black, a light title and accent subtitle, the screen in a rounded phone frame, "metro launcher"
# at the bottom; then joins them with a logo card at the end into out/new_features.mp4.
#
#   tools/store/promo/compose.sh
#   FONT=/path/SegoeUI-Light.ttf FONT2=/path/SegoeUI.ttf MUSIC=track.mp3 tools/store/promo/compose.sh
#
# Join it to the old video afterwards (same size and rate), e.g.:
#   ffmpeg -i old.mp4 -i out/new_features.mp4 -filter_complex \
#     "[0:v][1:v]concat=n=2:v=1[v]" -map "[v]" -c:v libx264 -crf 18 joined.mp4
set -eu
cd "$(dirname "$0")"
W=1080; H=1920; FPS=30
SCREEN_W=696; SCREEN_MAX_H=1540; TOP=268; BEZEL=12; R_SCREEN=46
FADE=0.3
OUT=out; TMP=out/tmp
mkdir -p "$TMP"

pick_font() {
  for f in "$@"; do [ -f "$f" ] && { echo "$f"; return; }; done
  fc-match -f '%{file}' sans 2>/dev/null || true
}
FONT=${FONT:-$(pick_font /c/Windows/Fonts/segoeuil.ttf /mnt/c/Windows/Fonts/segoeuil.ttf \
  /usr/share/fonts/truetype/open-sans/OpenSans-Light.ttf /System/Library/Fonts/Supplemental/Arial.ttf \
  /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf)}
FONT2=${FONT2:-$(pick_font /c/Windows/Fonts/segoeui.ttf /mnt/c/Windows/Fonts/segoeui.ttf "$FONT")}
echo "fonts: $FONT / $FONT2"

hex() { printf '%d %d %d' "0x${1:1:2}" "0x${1:3:2}" "0x${1:5:2}"; }

# Accent glow from the top-left corner (and a faint one bottom-right) over near-black.
background() { # accent png
  read -r r g b <<<"$(hex "$1")"
  local f="pow(max(0\,1-hypot(X-150\,Y-90)/1700)\,1.8)*0.62+pow(max(0\,1-hypot(X-1080\,Y-1920)/900)\,2)*0.22"
  ffmpeg -v error -y -f lavfi -i "color=black:s=${W}x${H}:d=1" -frames:v 1 \
    -vf "format=rgb24,geq=r='8+($r-8)*($f)':g='8+($g-8)*($f)':b='11+($b-11)*($f)'" "$2"
}

# White-on-black mask of a w x h rounded rectangle with corner radius rad.
mask() { # w h rad png
  local w=$1 h=$2 rad=$3
  local e="if(gt(abs(X-$w/2)\,$w/2-$rad)*gt(abs(Y-$h/2)\,$h/2-$rad)\,if(lte(hypot(abs(X-$w/2)-($w/2-$rad)\,abs(Y-$h/2)-($h/2-$rad))\,$rad)\,255\,0)\,255)"
  ffmpeg -v error -y -f lavfi -i "color=black:s=${w}x${h}:d=1" -frames:v 1 \
    -vf "format=gray,geq=lum='$e'" "$4"
}

footer() {
  echo "drawbox=x=402:y=1856:w=24:h=24:color=0x19A1E0:t=fill,drawtext=fontfile='$FONT':text='metro launcher':fontsize=40:fontcolor=0xD8DCE0:x=440:y=1846"
}

clip() { # id title subtitle accent start end
  local id=$1 title=$2 sub=$3 acc=$4 ss=$5 to=$6 src="captures/$1.mp4"
  [ -f "$src" ] || { echo "skip $id (no $src)"; return; }
  read -r sw sh <<<"$(ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0 "$src" | tr , ' ')"
  local w=$SCREEN_W h=$(( (SCREEN_W * sh / sw) / 2 * 2 ))
  if [ "$h" -gt "$SCREEN_MAX_H" ]; then h=$SCREEN_MAX_H; w=$(( (SCREEN_MAX_H * sw / sh) / 2 * 2 )); fi
  local bw=$((w + 2*BEZEL)) bh=$((h + 2*BEZEL)) x=$(( (W - w) / 2 ))
  printf '%s' "$title" >"$TMP/$id.title.txt"; printf '%s' "$sub" >"$TMP/$id.sub.txt"
  background "$acc" "$TMP/$id.bg.png"
  mask "$w" "$h" "$R_SCREEN" "$TMP/$id.mask.png"
  mask "$bw" "$bh" $((R_SCREEN + BEZEL)) "$TMP/$id.bezel.png"
  local trim=()
  [ -n "$ss" ] && trim+=(-ss "$ss")
  [ -n "$to" ] && trim+=(-to "$to")
  local dur
  dur=$(ffmpeg -v error "${trim[@]}" -i "$src" -map 0:v:0 -f null - -progress pipe:1 2>/dev/null |
        awk -F= '/^out_time_us=/{t=$2} END{printf "%.2f", t/1e6}')
  local out=$(awk -v d="$dur" -v f=$FADE 'BEGIN{printf "%.2f", d-f}')
  ffmpeg -v error -y "${trim[@]}" -i "$src" -loop 1 -i "$TMP/$id.bg.png" \
    -loop 1 -i "$TMP/$id.mask.png" -loop 1 -i "$TMP/$id.bezel.png" -filter_complex "
      [0:v]fps=$FPS,scale=$w:$h:flags=lanczos,format=rgba[s0];
      [2:v]format=gray[m];[s0][m]alphamerge[scr];
      [3:v]format=gray[bm1];
      color=c=0x17181B:s=${bw}x${bh},format=rgba[bc];[bc][bm1]alphamerge[bez];
      [1:v]format=rgba[bg];
      [bg][bez]overlay=$((x - BEZEL)):$((TOP - BEZEL)):shortest=0[b1];
      [b1][scr]overlay=$x:$TOP:shortest=1,
      drawtext=fontfile='$FONT':textfile='$TMP/$id.title.txt':fontsize=86:fontcolor=white:x=82:y=72,
      drawtext=fontfile='$FONT2':textfile='$TMP/$id.sub.txt':fontsize=40:fontcolor=0x${acc:1}:x=86:y=176,
      $(footer),
      fade=t=in:d=$FADE,fade=t=out:st=$out:d=$FADE,format=yuv420p[v]" \
    -map "[v]" -t "$dur" -r $FPS -c:v libx264 -preset medium -crf 18 -an "$TMP/$id.clip.mp4"
  echo "$TMP/$id.clip.mp4" >>"$TMP/list"
  echo "made $id (${dur}s)"
}

outro() {
  background "#1BA1E2" "$TMP/outro.bg.png"
  # The tile logo of the first video: one large, two small, one wide, one small, one wide.
  local t="drawbox=x=285:y=560:w=251:h=251:color=0x1BA1E2:t=fill,drawbox=x=545:y=560:w=121:h=121:color=0xD80073:t=fill"
  t+=",drawbox=x=675:y=560:w=121:h=121:color=0x8CBF26:t=fill,drawbox=x=545:y=690:w=251:h=121:color=0xF09609:t=fill"
  t+=",drawbox=x=285:y=820:w=121:h=121:color=0xA200FF:t=fill,drawbox=x=415:y=820:w=381:h=121:color=0x00ABA9:t=fill"
  ffmpeg -v error -y -loop 1 -t 4 -i "$TMP/outro.bg.png" -vf "$t,
    drawtext=fontfile='$FONT':text='metro launcher':fontsize=118:fontcolor=white:x=(w-tw)/2:y=1040,
    drawtext=fontfile='$FONT2':text='live tiles · smart search · almost no battery':fontsize=46:fontcolor=0x1BA1E2:x=(w-tw)/2:y=1180,
    fade=t=in:d=$FADE,format=yuv420p" -r $FPS -c:v libx264 -crf 18 "$TMP/outro.clip.mp4"
  echo "$TMP/outro.clip.mp4" >>"$TMP/list"
}

: >"$TMP/list"
while IFS=$'\t' read -r -u 3 id title sub acc ss to; do
  case "$id" in ''|'#'*) continue;; esac
  clip "$id" "$title" "$sub" "$acc" "${ss:-}" "${to:-}"
done 3<scenes.tsv
outro

sed 's|^out/tmp/|file |' "$TMP/list" >"$TMP/concat.txt"
FINAL=$OUT/new_features.mp4
if [ -n "${MUSIC:-}" ]; then
  ffmpeg -v error -y -f concat -safe 0 -i "$TMP/concat.txt" -c copy "$TMP/joined.mp4"
  len=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$TMP/joined.mp4")
  ffmpeg -v error -y -i "$TMP/joined.mp4" -stream_loop -1 -i "$MUSIC" -map 0:v -map 1:a -c:v copy \
    -af "afade=t=in:d=1,afade=t=out:st=$(awk -v l="$len" 'BEGIN{print l-2}'):d=2" -c:a aac -b:a 192k -shortest "$FINAL"
else
  ffmpeg -v error -y -f concat -safe 0 -i "$TMP/concat.txt" -c copy "$FINAL"
fi
echo "wrote $FINAL"
