# FH Dashboard add-on image. Build context = repo root; the fat jar must be
# assembled first:
#   sbt fh-datastar-view/assembly
#   docker buildx build -f home-addon/Dockerfile -t ghcr.io/perok/fh-dashboard:dev .
# The jar is arch-independent JVM bytecode; the multi-arch temurin builder +
# hassio base yield linux/amd64 + linux/arm64 without cross-compiling Scala.
# Only jlink runs emulated per-arch — the GraalJS stage picks its library by
# TARGETARCH but runs on the BUILD platform.
#
# GLIBC, not musl, and that is a requirement rather than a preference: the
# GraalJS polyglot isolate library staged below links libc.so.6 with versioned
# GLIBC_2.x symbols and names musl nowhere. Both stages have to agree, so the
# alpine builder AND the alpine runtime base moved together — a glibc JRE on a
# musl base does not load.
# https://hub.docker.com/_/eclipse-temurin#creating-a-jre-using-jlink
FROM eclipse-temurin:25-jdk AS jre-build

# Custom Java runtime: the module list is jdeps output for the assembled jar
# (jdeps --multi-release 25 --print-module-deps --ignore-missing-deps
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
         --compress=zip-6 \
         --output /javaruntime

# The GraalJS polyglot isolate library — the JavaScript engine itself, as a
# 159 MB native library the JVM dlopens. Everything the app links against is
# in the fat jar (org.graalvm.polyglot, ~1 MB of pure bytecode); this is the
# only architecture-specific byte in the image, which is what keeps that jar
# the same file on both.
#
# --platform=$BUILDPLATFORM: this stage only DOWNLOADS and unpacks. Nothing it
# runs has to be the target architecture, and the target-architecture path is
# QEMU, so pinning it to the builder keeps the whole step off the emulator.
# `TARGETARCH` still selects which library is fetched.
#
# Fetched rather than copied out of the build context because the context here
# is the ci-built jar, shipped between jobs as an artifact: staging both
# platforms' libraries through it means moving 300 MB per run to land 159 MB.
#
# GraalJS bootstraps itself here. Nothing in this stage names a version, a
# URL, an archive layout or an architecture mapping: sbt resolved both
# platforms' isolate jars as ordinary dependencies and staged them beside the
# app jar under buildx's own spelling, and unpacking is simply what booting a
# Truffle engine does when its resource cache is cold. Pointing
# `userResourceCache` at a staging directory turns that into a build step, so
# the add-on never extracts 161 MB at runtime.
#
# `JsIsolateCheck` and not a bootstrap of our own, because it already boots an
# engine and runs JavaScript — which means the unpack and the proof that the
# unpacked thing works are one command, and the build cannot stage a library
# it could not run.
#
# NOT `--platform=$BUILDPLATFORM`: this runs the isolate, so it has to be the
# target architecture. The arm64 half is emulated, and that is also the point
# at which a broken arm64 build fails.
FROM eclipse-temurin:25-jdk AS isolate
ARG TARGETARCH
COPY target/addon/ /staging/
RUN set -eu; \
    java -cp "/staging/fh-dashboard.jar:/staging/js-isolate-$TARGETARCH.jar" \
      -Dpolyglot.engine.userResourceCache=/graal-resources \
      --enable-native-access=ALL-UNNAMED \
      fh.view.runtime.JsIsolateCheck; \
    cp "/staging/js-isolate-$TARGETARCH.jar" /js-isolate.jar

# hassio base: s6-overlay init (config.yaml sets init: false), bashio + jq
# already included (run.sh only needs jq, and the s6 container_environment
# re-import). The DEBIAN variant, for the glibc reason above; it is the same
# single multi-arch manifest as the alpine one, so buildx still builds both
# platforms from this one Dockerfile.
FROM ghcr.io/hassio-addons/debian-base:9.4.0

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=jre-build /javaruntime $JAVA_HOME

# GraalJS: the provider jar, and the resource cache the build above unpacked.
# Both are needed and neither is redundant — the jar is what REGISTERS that a
# JS isolate exists (an engine without it reports "No native isolate library
# is available" however the resources are staged), and the cache is what it
# reads instead of extracting 161 MB on first use.
#
# The library needs libz.so.1 alongside glibc; the base image has both.
COPY --from=isolate /js-isolate.jar /opt/fh/js-isolate.jar
COPY --from=isolate /graal-resources /opt/fh/graal-resources

# How this image launches a JVM against the app, defined ONCE. `run.sh` reads
# both, and so does the CI step that re-runs JsIsolateCheck in the finished
# image — otherwise those two spell out the same classpath separately and can
# drift, and the JVM SILENTLY IGNORES a classpath entry that does not exist:
# the add-on would start normally and fail at the first chart.
ENV FH_APP_CLASSPATH=/opt/fh-dashboard.jar:/opt/fh/js-isolate.jar
ENV FH_GRAAL_RESOURCES=/opt/fh/graal-resources

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
