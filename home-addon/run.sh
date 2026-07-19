#!/bin/sh
# FH Dashboard add-on entrypoint. Wires the supervisor-proxied HA endpoints
# and execs the app; workspace seeding/migration is AddonBootstrap's job
# (in the server, driven by the FH_* exports below).
set -e

# s6-overlay v3 (the hassio base image init) SCRUBS the container environment
# from services and stashes it in /run/s6/container_environment — without
# re-importing it here, SUPERVISOR_TOKEN (and any docker -e overrides) are
# invisible. The dir is absent when the script runs outside /init (standalone
# docker run --entrypoint /run.sh), where the env arrives normally.
if [ -d /run/s6/container_environment ]; then
  for f in /run/s6/container_environment/*; do
    [ -f "$f" ] || continue
    export "$(basename "$f")=$(cat "$f")"
  done
fi

# Under /homeassistant (the homeassistant_config map) so the seeded entries are
# visible in the File editor / Samba homeassistant/ share for editing.
DASH_DIR=/homeassistant/fh-dashboards

# HA core via the supervisor proxy. The WS endpoint is NOT the /api/websocket
# path derived from SERVER, hence the explicit SERVER_WS override. Pre-set
# env wins, so the container can also run standalone against a plain HA
# (docker run -e SERVER=http://ha:8123 -e SECRET=<token> ...).
export SERVER="${SERVER:-http://supervisor/core}"
if [ -z "${SERVER_WS:-}" ]; then
  if [ "$SERVER" = "http://supervisor/core" ]; then
    export SERVER_WS=ws://supervisor/core/websocket
  else
    # Standalone against a plain HA: let the app derive <SERVER>/api/websocket.
    unset SERVER_WS
  fi
fi
export SECRET="${SECRET:-${SUPERVISOR_TOKEN:-}}"
if [ -z "$SECRET" ]; then
  echo "FATAL: no SECRET and no SUPERVISOR_TOKEN — cannot reach Home Assistant" >&2
  exit 1
fi

# Reachable from ingress AND the (optional) direct port mapping.
export HOST=0.0.0.0
export PORT=8080

export DASHBOARDS_DIR="$DASH_DIR"
# /data is add-on private persistent storage; the asset cache needs no user
# editing, unlike the dashboards.
export FH_ASSETS_DIR=/data/assets-cache

# AddonBootstrap inputs (ADR 0010): starter entries seed an empty workspace;
# old copy-if-empty installs are migrated with dated backups. The authoring lib
# is streamed from the jar's own resources (BundledLib) into the persistent pkl
# cache at boot — no FH_BUNDLED_LIB path.
export FH_SEED_DIR=/opt/dashboards-seed
export FH_PKL_CACHE_DIR=/data/pkl-cache

if [ -f /data/options.json ]; then
  DEFAULT_DASHBOARD="$(jq -r '.default_dashboard // empty' /data/options.json)"
  if [ -n "$DEFAULT_DASHBOARD" ]; then
    export DEFAULT_DASHBOARD
  fi
  # Registry-driven dump refresh toggle (on unless the option is set to false).
  if [ "$(jq -r '.watch_registry' /data/options.json)" = "false" ]; then
    export FH_WATCH_REGISTRY=false
  fi
fi

exec java -XX:MaxRAMPercentage=75 -jar /opt/fh-dashboard.jar
