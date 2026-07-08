#!/bin/sh
# FH Dashboard add-on entrypoint. Seeds the user-editable dashboards dir on
# first start, wires the supervisor-proxied HA endpoints, and execs the app.
set -eu

DASH_DIR=/config/dashboards
mkdir -p "$DASH_DIR"
if [ -z "$(ls -A "$DASH_DIR")" ]; then
  echo "Seeding starter dashboards into $DASH_DIR"
  cp -r /opt/dashboards-seed/. "$DASH_DIR"/
fi

# HA core via the supervisor proxy. The WS endpoint is NOT the /api/websocket
# path derived from SERVER, hence the explicit SERVER_WS override. Pre-set
# env wins, so the container can also run standalone against a plain HA
# (SERVER=http://ha:8123 SECRET=<token> SERVER_WS= docker run ...).
export SERVER="${SERVER:-http://supervisor/core}"
if [ -z "${SERVER_WS:-}" ]; then
  if [ "$SERVER" = "http://supervisor/core" ]; then
    export SERVER_WS=ws://supervisor/core/websocket
  else
    # Standalone against a plain HA: let the app derive <SERVER>/api/websocket.
    unset SERVER_WS
  fi
fi
export SECRET="${SECRET:-$SUPERVISOR_TOKEN}"

# Reachable from ingress AND the (optional) direct port mapping.
export HOST=0.0.0.0
export PORT=8080

export DASHBOARDS_DIR="$DASH_DIR"
# /data is add-on private persistent storage; the asset cache needs no user
# editing, unlike the dashboards.
export FH_ASSETS_DIR=/data/assets-cache

DEFAULT_DASHBOARD="$(jq -r '.default_dashboard // empty' /data/options.json)"
if [ -n "$DEFAULT_DASHBOARD" ]; then
  export DEFAULT_DASHBOARD
fi

# LAUNCH_CMD — adjust once the sbt packaging output is integrated.
exec java -XX:MaxRAMPercentage=75 -jar /opt/fh-dashboard.jar
