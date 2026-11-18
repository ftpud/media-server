#!/bin/bash
# End-to-end A/V sync test: plays a clip with a 1-frame white flash + a 50 ms beep at the same
# moment every second through mediad (several tracks back to back with crossfades) and measures
# the beep-minus-flash offset of every pair in the output. Expect one constant value (~23 ms,
# the AAC encoder delay; plain ffmpeg gives the same).
#   usage: mediad/tests/sync_test.sh [tracks=6]
set -eu
cd "$(dirname "$0")/.."
source ./build-env.sh
N=${1:-6}
W=$(mktemp -d)
F=$FFMPEG_PREFIX/bin/ffmpeg
$F -hide_banner -v error -y -f lavfi -i "color=c=black:s=1280x720:r=30,drawbox=t=fill:c=white:enable='between(mod(t,1),0.5,0.53)'" \
   -f lavfi -i "aevalsrc='0.8*sin(2*PI*1000*t)*between(mod(t,1),0.5,0.55)':s=48000:c=stereo" -t 10 \
   -c:v libx264 -pix_fmt yuv420p -c:a aac -ar 48000 "$W/clip.mp4"
clips=(); for _ in $(seq 1 "$N"); do clips+=("$W/clip.mp4"); done
MEDIAD_W=1280 MEDIAD_H=720 MEDIAD_XFADE_MS=1500 timeout $((N * 9 + 8)) ./target/release/mediad --out "$W/out.ts" \
   --encoder x264 --port 18999 --play "${clips[@]}" > "$W/mediad.log" 2>&1 || true
FFMPEG_PREFIX=$FFMPEG_PREFIX python3 - "$W/out.ts" <<'PY'
import subprocess, re, os, sys
f = sys.argv[1]; F = os.environ['FFMPEG_PREFIX'] + '/bin/ffmpeg'
out = subprocess.run([F, '-v', 'info', '-i', f, '-an', '-vf', 'signalstats,metadata=print:key=lavfi.signalstats.YAVG', '-f', 'null', '-'], capture_output=True, text=True).stderr
fl, t = [], None
for l in out.splitlines():
    m = re.search(r'pts_time:([0-9.]+)', l)
    if m: t = float(m.group(1))
    m = re.search(r'YAVG=([0-9.]+)', l)
    if m and t is not None and float(m.group(1)) > 60 and (not fl or t - fl[-1] > 0.5): fl.append(t)
out = subprocess.run([F, '-v', 'info', '-i', f, '-vn', '-af', 'silencedetect=n=-30dB:d=0.2', '-f', 'null', '-'], capture_output=True, text=True).stderr
bp = [float(x) for x in re.findall(r'silence_end: ([0-9.]+)', out)]
offs = [round((min(bp, key=lambda b: abs(b - x)) - x) * 1000, 1) for x in fl if bp and abs(min(bp, key=lambda b: abs(b - x)) - x) < 0.4]
spread = max(offs) - min(offs) if offs else 999
print(f"pairs={len(offs)} offset_ms min={min(offs) if offs else None} max={max(offs) if offs else None} spread={spread}")
sys.exit(0 if offs and spread <= 2.0 else 1)
PY
echo "PASS"
