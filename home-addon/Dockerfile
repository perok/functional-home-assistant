# FH Dashboard add-on image. Build context = repo root; the fat jar must be
# assembled first:
#   sbt fh-datastar-view/assembly
#   docker buildx build -f home-addon/Dockerfile -t ghcr.io/perok/fh-dashboard:dev .
# The jar is arch-independent JVM bytecode; the multi-arch alpine temurin
# builder + hassio base yield linux/amd64 + linux/arm64 without
# cross-compiling Scala (only the jlink stage runs per-arch).
# https://hub.docker.com/_/eclipse-temurin#creating-a-jre-using-jlink
FROM eclipse-temurin:21-alpine AS jre-build

# Custom Java runtime: the module list is jdeps output for the assembled jar
# (jdeps --multi-release 21 --print-module-deps --ignore-missing-deps
# target/addon/fh-dashboard.jar) + jdk.crypto.ec, which jdeps cannot see
# statically but TLS needs for the HTTPS theme-asset fetches.
RUN $JAVA_HOME/bin/jlink \
         --add-modules java.base,java.compiler,java.management,java.naming,java.net.http,java.sql,jdk.unsupported,jdk.crypto.ec \
         --strip-debug \
         --no-man-pages \
         --no-header-files \
         --compress=2 \
         --output /javaruntime

# hassio base: s6-overlay init (config.yaml sets init: false), bashio + jq
# already included (run.sh only needs jq).
FROM ghcr.io/hassio-addons/base:21.0.0

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=jre-build /javaruntime $JAVA_HOME

# The sbt-assembly fat jar (sbt fh-datastar-view/assembly).
COPY target/addon/fh-dashboard.jar /opt/fh-dashboard.jar

# Two artifacts for `AddonBootstrap` (run by the server at startup — run.sh
# only exports the paths), split by who owns the files:
#
#   /opt/fh/lib            the authoring library — fh's code, versioned by its
#                          OWN `lib/PklProject` version, decoupled from the
#                          add-on version (ADR 0010). Never copied into the
#                          user's workspace: at boot it is packaged into the
#                          persistent package cache (/data/pkl-cache) and the
#                          user's manifests depend on it as
#                          `package://fh.invalid/fh-dashboard@<version>`, so a
#                          runtime upgrade never moves the user's pin.
#   /opt/dashboards-seed   starter ENTRIES only, copied into the user's
#                          `/homeassistant/fh-dashboards` on first boot. The
#                          project manifests are NOT files here — AddonBootstrap
#                          generates them against the bundled lib version, and
#                          migrates pre-package-form installs with dated
#                          backups (never deleting user-visible files).
#
# Never shipped: the demo entries (they reference dev-instance entities), any
# dump (the user's own live data, written into home/ at every start), and
# PklProject.deps.json (the generated lockfile, resolved in-process at boot).
#
# AddonBootstrapSuite pins this whole contract — seeding, offline evaluation
# from the pre-seeded cache, upgrade migration — against these same two
# directories.
COPY modules/fh-datastar-view/src/main/resources/dashboards/lib /opt/fh/lib
COPY home-addon/dashboards-seed/ /opt/dashboards-seed/

COPY home-addon/run.sh /run.sh
RUN chmod +x /run.sh

LABEL io.hass.type=addon
CMD ["/run.sh"]
