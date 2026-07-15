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

# Dashboard seed — everything `run.sh` copies into the user's editable
# `/homeassistant/fh-dashboards` on first start. Exactly two sources, split by
# who owns the file:
#
#   home-addon/dashboards-seed/  the USER's workspace: the starter entry and the
#                                project manifests that bind @fh-dashboard and
#                                @fh-home. Written for the end user and kept
#                                there, so this directory IS the product
#                                surface — read it to see what a user gets. It
#                                deliberately does not borrow the dev checkout's
#                                own PklProject, whose docs describe the demo
#                                entries and dev workspace.
#   lib/                         the authoring library: fh's code, versioned
#                                with the image and replaced wholesale on
#                                upgrade. Not duplicated into the seed dir —
#                                it is a dependency, not user scaffolding.
#
# Never shipped: the demo entries (they reference dev-instance entities), any
# dump (that is the user's own live data, written into home/ at every start),
# and PklProject.deps.json (the generated lockfile, resolved in-process at
# startup). The seed's `home/` therefore arrives holding only its manifest.
#
# PklBuildSuite's seed-layout test mirrors these two COPYs, so the manifests
# here cannot drift out of agreement with lib/ unnoticed (ADR 0010, Track B).
COPY modules/fh-datastar-view/src/main/resources/dashboards/lib /opt/dashboards-seed/lib
COPY home-addon/dashboards-seed/ /opt/dashboards-seed/

COPY home-addon/run.sh /run.sh
RUN chmod +x /run.sh

LABEL io.hass.type=addon
CMD ["/run.sh"]
