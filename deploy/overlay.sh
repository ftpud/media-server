#!/bin/bash
# Overlay renderer (started by the dashboard from the app directory; output goes to logs/overlay.log)
cd "$(dirname "$0")"
exec ./ext/overlay --target 127.0.0.1:8096 --mediad 127.0.0.1:8095 --media 127.0.0.1:8084 --size 1000x440
