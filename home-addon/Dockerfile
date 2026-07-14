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

# Dashboard seed: the shared Pkl lib + the generic starter entry. Never ships
# lib/dump.pkl (user-specific, regenerated at every startup) nor the demo
# entries (they reference dev-instance entities). The `lib/` copy includes its
# `PklProject` (the `@fh-dashboard` package manifest); the consumer `PklProject`
# below binds the `@fh-dashboard` alias to it so seeded entries resolve their
# imports (ADR 0010, Track B). `PklProject.deps.json` is NOT shipped — it is the
# generated lockfile, resolved in-process at startup.
COPY modules/fh-datastar-view/src/main/resources/dashboards/lib /opt/dashboards-seed/lib
RUN rm -f /opt/dashboards-seed/lib/dump.pkl
COPY modules/fh-datastar-view/src/main/resources/dashboards/PklProject /opt/dashboards-seed/PklProject
COPY home-addon/dashboards-seed/ /opt/dashboards-seed/

COPY home-addon/run.sh /run.sh
RUN chmod +x /run.sh

LABEL io.hass.type=addon
CMD ["/run.sh"]
