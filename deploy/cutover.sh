#!/bin/bash
# Cutover from the old pipeline (stream-service ffmpeg -> balance-service -> publisher concat)
# to mediad. Run as root. Rolls back automatically if the new pipeline does not come up.
#   STAGING must contain: media-service.jar stream-service.jar mediad mediad.sh rollback.sh
set -u
APP=${APP:-/home/ftpud/media-server/app}
STAGING=${STAGING:-/home/ftpud/deploy-staging}
LOG=$APP/logs
TS=$(date +%Y%m%d-%H%M%S)
BK=$APP/backup-$TS
log() { echo "$(date '+%F %T') cutover: $*"; }
pids() { pgrep -f "$1" | grep -vx "$$" ; }
stop() { # graceful, then forced
  local p; p=$(pids "$1"); [ -z "$p" ] && return 0
  log "stopping $1 ($p)"; kill $p 2>/dev/null
  for _ in $(seq 1 20); do [ -z "$(pids "$1")" ] && return 0; sleep 0.5; done
  kill -9 $(pids "$1") 2>/dev/null; sleep 1
}
start_java() { # name
  (cd $APP && nohup java -jar $1.jar --spring.config.location=classpath:/application.properties,config.properties >> $LOG/$1.log 2>&1 &)
}
wait_http() { # url seconds
  for _ in $(seq 1 $2); do curl -s -m 2 "$1" > /dev/null 2>&1 && return 0; sleep 1; done; return 1
}

for f in media-service.jar stream-service.jar mediad mediad.sh rollback.sh; do
  [ -f "$STAGING/$f" ] || { log "missing $STAGING/$f"; exit 1; }
done
mkdir -p "$BK" && cp -a $APP/media-service.jar $APP/stream-service.jar $APP/config.properties "$BK/" || exit 1
echo "$BK" > $APP/.last-backup
log "backup in $BK"

install -m 755 $STAGING/mediad $APP/ext/mediad
install -m 755 $STAGING/mediad.sh $APP/mediad.sh
install -m 755 $STAGING/rollback.sh $APP/rollback.sh

# 1) media-service first: the old pipeline keeps streaming the current track meanwhile
stop "java -jar media-service.jar"
cp $STAGING/media-service.jar $APP/media-service.jar
start_java media-service
wait_http http://localhost:8084/health 180 || { log "media-service did not come up"; exec $APP/rollback.sh; }
log "media-service up"

# 2) enable mediad in stream-service
grep -q '^media.mediad.enabled' $APP/config.properties || printf '\n#mediad\nmedia.mediad.enabled=true\nmedia.mediad.url=http://localhost:8095\n' >> $APP/config.properties
sed -i 's/^media.mediad.enabled=.*/media.mediad.enabled=true/' $APP/config.properties

# 3) switch: stop the old pipeline (stream-service + its ffmpeg, publisher, balancer, concat ffmpeg)
stop "java -jar stream-service.jar"
stop "java -jar publisher-service.jar"
stop "java -jar balance-service.jar"
stop "ffmpeg_stream"
(cd $APP && nohup ./mediad.sh >> $LOG/mediad.log 2>&1 &)
wait_http http://localhost:8095/health 20 || { log "mediad did not start"; exec $APP/rollback.sh; }
log "mediad up (black filler until stream-service loads the current track)"
cp $STAGING/stream-service.jar $APP/stream-service.jar
start_java stream-service

# 4) verify: mediad is connected and playing, and the public RTMP output carries video+audio
for _ in $(seq 1 120); do
  st=$(curl -s -m 2 http://localhost:8095/status)
  echo "$st" | grep -q '"connected":true' && echo "$st" | grep -q '"current":{' && break
  sleep 1
done
echo "$st" | grep -q '"current":{' || { log "mediad is not playing anything: $st"; exec $APP/rollback.sh; }
streams=$(timeout 20 /usr/bin/ffprobe -v error -show_entries stream=codec_type -of csv=p=0 rtmp://localhost/main 2>/dev/null | sort -u | tr '\n' ' ')
echo "$streams" | grep -q video && echo "$streams" | grep -q audio || { log "rtmp://localhost/main not healthy: '$streams'"; exec $APP/rollback.sh; }
log "OK: mediad is live ($streams). Rollback: $APP/rollback.sh"
