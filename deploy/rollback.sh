#!/bin/bash
# Back to the old pipeline using the backup made by cutover.sh. Run as root.
set -u
APP=${APP:-/home/ftpud/media-server/app}
LOG=$APP/logs
BK=$(cat $APP/.last-backup 2>/dev/null)
log() { echo "$(date '+%F %T') rollback: $*"; }
pids() { pgrep -f "$1" | grep -vx "$$" ; }
stop() {
  local p; p=$(pids "$1"); [ -z "$p" ] && return 0
  log "stopping $1"; kill $p 2>/dev/null
  for _ in $(seq 1 20); do [ -z "$(pids "$1")" ] && return 0; sleep 0.5; done
  kill -9 $(pids "$1") 2>/dev/null; sleep 1
}
start_java() { (cd $APP && nohup java -jar $1.jar --spring.config.location=classpath:/application.properties,config.properties >> $LOG/$1.log 2>&1 &); }
[ -n "$BK" ] && [ -d "$BK" ] || { log "no backup found"; exit 1; }
stop "mediad.sh"
stop "mediad --out"
stop "java -jar stream-service.jar"
cp -a "$BK/stream-service.jar" "$BK/config.properties" $APP/
if ! cmp -s "$BK/media-service.jar" $APP/media-service.jar; then
  stop "java -jar media-service.jar"
  cp -a "$BK/media-service.jar" $APP/
  start_java media-service
  for _ in $(seq 1 180); do curl -s -m 2 http://localhost:8084/health > /dev/null && break; sleep 1; done
fi
start_java stream-service
[ -z "$(pids "java -jar publisher-service.jar")" ] && start_java publisher-service   # it starts balance-service + the concat ffmpeg itself
log "old pipeline restarted from $BK"
