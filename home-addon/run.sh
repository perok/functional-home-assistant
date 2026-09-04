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

# AddonBootstrap inputs (ADR 0010): a starter entrypoint seeds a workspace
# that has none; old copy-if-empty installs are migrated with dated backups.
# Both the authoring lib and the starter are streamed from the jar's own
# resources (BundledLib / AddonBootstrap.starterSite) — no seed path, no
# FH_BUNDLED_LIB path.
export FH_PKL_CACHE_DIR=/data/pkl-cache

# The heap ceiling is a NUMBER, not a fraction of the machine.
# `-XX:MaxRAMPercentage` reads the cgroup limit when there is one and the
# HOST's physical RAM when there is not — and the supervisor puts no memory
# limit on an add-on, so the 75% this used to pass resolved to a ~3 GB max
# heap on a 4 GB Pi. A page render allocates 6-12 MB (issue #237), so G1
# grew the heap toward that ceiling instead of collecting, and sized its own
# native structures off it as well: the footprint tracked the hardware
# rather than the workload.
#
# 512M is deliberately generous — comfortably above the live set (dashboard,
# house state, the per-renderer caches) plus a pkl evaluation spike — and is
# a ceiling, not a reservation. Raise it with the `max_heap` option if a
# large house or a big workspace needs more.
JAVA_MAX_HEAP="${JAVA_MAX_HEAP:-512M}"

# The STARTING heap is a fraction of the machine too (InitialRAMPercentage,
# 1.5625%), which is the same bug at the other end: on a big host the JVM
# commits the whole ceiling before serving a request. Pinned small so the
# heap grows into the workload instead of starting at it — with SerialGC's
# 40/70 free-ratio policy it then also gives the memory back.
JAVA_MIN_HEAP="${JAVA_MIN_HEAP:-64M}"

# SerialGC, not the G1 the JVM picks by itself: at this heap size on four
# slow cores, G1's concurrent threads and remembered sets buy nothing, and
# SerialGC RETURNS memory to the OS after a collection where G1 largely does
# not — which is most of what makes the number reported to the supervisor
# follow the workload.
JAVA_GC=-XX:+UseSerialGC

# Native Memory Tracking is the only thing that separates heap from
# metaspace from GC native from code cache, and it cannot be turned on
# without a restart — hence an option rather than a runtime toggle. It costs
# a few percent, so it is off unless asked for.
JAVA_NMT=

# There is no `default_dashboard` option: the slug served at `/` is `default`
# in the workspace's own `site.pkl` (ADR 0021), where the dashboards it
# chooses between are declared.
if [ -f /data/options.json ]; then
  # Registry-driven dump refresh toggle (on unless the option is set to false).
  if [ "$(jq -r '.watch_registry' /data/options.json)" = "false" ]; then
    export FH_WATCH_REGISTRY=false
  fi
  # `// empty` so an unset option yields "" rather than the string "null".
  # Assigned through an `if`, not `[ -n "$x" ] && ...`, because a false test
  # is the last command of that list and `set -e` would take it as failure.
  HEAP_OPT="$(jq -r '.max_heap // empty' /data/options.json)"
  if [ -n "$HEAP_OPT" ]; then
    JAVA_MAX_HEAP="$HEAP_OPT"
  fi
  if [ "$(jq -r '.memory_tracking' /data/options.json)" = "true" ]; then
    JAVA_NMT=-XX:NativeMemoryTracking=summary
  fi
fi

# ExitOnOutOfMemoryError so a heap that is genuinely too small restarts the
# add-on — visible, and recovered by s6 — instead of thrashing the GC
# forever, which is what a bounded heap turns a leak into.
#
# $JAVA_NMT is deliberately unquoted: it is one flag or nothing, and nothing
# must vanish rather than become an empty argument.
# shellcheck disable=SC2086
exec java "-Xms$JAVA_MIN_HEAP" "-Xmx$JAVA_MAX_HEAP" "$JAVA_GC" \
  -XX:+ExitOnOutOfMemoryError $JAVA_NMT -jar /opt/fh-dashboard.jar
