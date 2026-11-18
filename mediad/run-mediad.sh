#!/bin/bash
# Launcher used by the dashboard (started from /home/ftpud/media-server/app).
# Override with env: MEDIAD_BIN, MEDIAD_OUT, MEDIAD_ENCODER, MEDIAD_PORT, MEDIAD_LIVE_FILE
export LD_LIBRARY_PATH=/home/ftpud/ffmpeg-7.1/lib
export MEDIAD_LIVE_FILE=${MEDIAD_LIVE_FILE:-/home/ftpud/media-server/app/ext/live.txt}
exec "${MEDIAD_BIN:-/home/ftpud/VIBE/media-server/mediad/target/release/mediad}" \
  --out "${MEDIAD_OUT:-rtmp://localhost/main}" \
  --encoder "${MEDIAD_ENCODER:-qsv}" \
  --port "${MEDIAD_PORT:-8095}"
