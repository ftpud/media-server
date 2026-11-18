#!/bin/bash
# mediad supervisor: restarts mediad if it ever exits (crash => ~2 s of downtime, not an outage).
# Install as /home/ftpud/media-server/app/mediad.sh, start with:
#   cd /home/ftpud/media-server/app && nohup ./mediad.sh >> logs/mediad.log 2>&1 &
# Stop: kill the supervisor first (pkill -f mediad.sh), then mediad (pkill -f "mediad --out").
# Override with env: MEDIAD_BIN, MEDIAD_OUT, MEDIAD_ENCODER, MEDIAD_PORT, MEDIAD_LIVE_FILE, MEDIAD_XFADE_MS
export LD_LIBRARY_PATH=/home/ftpud/ffmpeg-7.1/lib
export MEDIAD_LIVE_FILE=${MEDIAD_LIVE_FILE:-/home/ftpud/media-server/app/ext/live.txt}
export MEDIAD_XFADE_MS=${MEDIAD_XFADE_MS:-1500}
BIN=${MEDIAD_BIN:-/home/ftpud/media-server/app/ext/mediad}
while true; do
  echo "$(date '+%F %T') supervisor: starting mediad"
  "$BIN" --out "${MEDIAD_OUT:-rtmp://localhost/main}" --encoder "${MEDIAD_ENCODER:-qsv}" --port "${MEDIAD_PORT:-8095}"
  echo "$(date '+%F %T') supervisor: mediad exited with $?, restarting in 2 s"
  sleep 2
done
