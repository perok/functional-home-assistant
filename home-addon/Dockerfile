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
# statically but TLS needs for the HTTPS theme-asset fetches, + jdk.zipfs,
# which jdeps likewise cannot see statically but pkl-core needs at runtime to
# open its cached packages via a "jar:" NIO FileSystemProvider.
#
# The second line is the DIAGNOSTIC set, which jdeps will never name because
# nothing in the jar calls it: it exists so the running add-on can be asked
# what it is doing on the machine it is slow on. Without it there is no jcmd,
# no jmap, no jstat, no Flight Recorder and no heap dump on the target, which
# is how "the add-on uses 10% of the Pi's RAM" stayed unanswerable.
#   jdk.jcmd           jcmd/jmap/jstat/jstack
#   jdk.attach         the attach mechanism jcmd reaches the process through
#   jdk.management     com.sun.management — HotSpotDiagnosticMXBean (heap
#                      dumps, flag reads) and process CPU
#   jdk.jfr            Flight Recorder itself
#   jdk.management.jfr the MXBean otel4s/runtime-telemetry drives it through
#                      (issue #75)
# Not included: jdk.management.agent, which is only for REMOTE JMX. Attaching
# jconsole to a Pi behind ingress is not the workflow; `jcmd` over
# `docker exec` and a JFR file out of /data is.
#
# The whole set costs ~2 MB of runtime image.
RUN $JAVA_HOME/bin/jlink \
         --add-modules java.base,java.compiler,java.management,java.naming,java.net.http,java.sql,jdk.unsupported,jdk.crypto.ec,jdk.zipfs \
         --add-modules jdk.jcmd,jdk.attach,jdk.management,jdk.jfr,jdk.management.jfr \
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

# The starter ENTRY and the authoring LIBRARY for `AddonBootstrap` (run by the
# server at startup) both ride inside the fat jar as classpath resources
# (BundledLib.artifacts / AddonBootstrap.defaultDashboard) and are streamed
# straight into the persistent package cache / the user's workspace at boot —
# so the jar is the single source of both, and there is no seed dir or
# `/opt/fh/lib` COPY to keep in sync with the code. The project manifests
# aren't files here either — AddonBootstrap generates them against the
# bundled lib version, and migrates pre-package-form installs with dated
# backups (never deleting user-visible files).
#
# AddonBootstrapSuite pins the seeding + offline-evaluation contract.

COPY home-addon/run.sh /run.sh
RUN chmod +x /run.sh

LABEL io.hass.type=addon
CMD ["/run.sh"]
