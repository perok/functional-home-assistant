# FH Dashboard add-on image. Build context = repo root; both jars must be
# staged first:
#   sbt 'fh-datastar-view/assembly ; pkl-lsp-dist/stagePklLsp'
#   docker buildx build -f home-addon/Dockerfile -t ghcr.io/perok/fh-dashboard:dev .
# The app and pkl-lsp jars are arch-independent JVM bytecode; the multi-arch
# temurin builder + hassio base yield linux/amd64 + linux/arm64 without
# cross-compiling Scala. jlink is the only stage that runs emulated per-arch;
# the one architecture-specific artifact, the GraalJS isolate jar, is selected
# by TARGETARCH and merely copied.
#
# GLIBC, not musl, and that is a requirement rather than a preference: the
# GraalJS polyglot isolate library staged below links libc.so.6 with versioned
# GLIBC_2.x symbols and names musl nowhere. Both stages have to agree, so the
# alpine builder AND the alpine runtime base moved together — a glibc JRE on a
# musl base does not load.
#
# The JDK floor is 23, not 21: pkl-lsp is compiled to class file 67 and the
# editor's LSP subprocess runs on this same runtime, so on 21 it died with
# UnsupportedClassVersionError and /edit had no completion in the add-on at all.
# https://hub.docker.com/_/eclipse-temurin#creating-a-jre-using-jlink
FROM eclipse-temurin:25-jdk AS jre-build

# Custom Java runtime: the module list is jdeps output for the assembled jar
# (jdeps --multi-release 25 --print-module-deps --ignore-missing-deps
# target/addon/fh-dashboard.jar) + jdk.crypto.ec, which jdeps cannot see
# statically but TLS needs for the HTTPS theme-asset fetches, + jdk.zipfs,
# which jdeps likewise cannot see statically but pkl-core needs at runtime to
# open its cached packages via a "jar:" NIO FileSystemProvider, + java.logging
# and java.desktop, which the pkl-lsp CHILD needs and jdeps cannot see at all
# because it is a separate jar run as a subprocess (lsp4j logs via
# java.util.logging).
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
         --add-modules java.base,java.compiler,java.logging,java.management,java.naming,java.net.http,java.sql,java.desktop,jdk.unsupported,jdk.crypto.ec,jdk.zipfs \
         --add-modules jdk.jcmd,jdk.attach,jdk.management,jdk.jfr,jdk.management.jfr \
         --strip-debug \
         --no-man-pages \
         --no-header-files \
         --compress=zip-6 \
         --output /javaruntime

# hassio base: s6-overlay init (config.yaml sets init: false), bashio + jq
# already included (run.sh only needs jq, and the s6 container_environment
# re-import). The DEBIAN variant, for the glibc reason above; it is the same
# single multi-arch manifest as the alpine one, so buildx still builds both
# platforms from this one Dockerfile.
FROM ghcr.io/hassio-addons/debian-base:9.4.0

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=jre-build /javaruntime $JAVA_HOME

# GraalJS: one jar, staged per architecture by sbt under buildx's own
# spelling, so there is no version, coordinate or architecture mapping here.
# It is what REGISTERS that a JS isolate exists — an engine without it reports
# "No native isolate library is available" — and it carries the 159 MB library
# as an internal resource.
#
# Nothing unpacks it here. Truffle extracts its own resources on first use,
# which is how the thing is meant to work; `run.sh` only chooses WHERE, and
# picks /data so the unpack survives add-on updates. Doing it at build time
# instead costs a per-architecture stage that has to run the isolate under
# emulation, and 161 MB of image, to save a few seconds once per GraalVM bump.
#
# The library needs libz.so.1 alongside glibc; the base image has both.
ARG TARGETARCH
COPY target/addon/js-isolate-$TARGETARCH.jar /opt/fh/js-isolate.jar

# How this image launches a JVM against the app, defined ONCE. `run.sh` reads
# both, and so does the CI step that runs JsIsolateCheck in the finished
# image — otherwise those two spell out the same classpath separately and can
# drift, and the JVM SILENTLY IGNORES a classpath entry that does not exist:
# the add-on would start normally and fail at the first chart.
ENV FH_APP_CLASSPATH=/opt/fh-dashboard.jar:/opt/fh/js-isolate.jar
ENV FH_GRAAL_CACHE=/data/graal-cache

# The sbt-assembly fat jar (sbt fh-datastar-view/assembly).
COPY target/addon/fh-dashboard.jar /opt/fh-dashboard.jar

# The pkl-lsp CLI jar backing /edit's language server, resolved by the build
# (pkl-lsp-dist/stagePklLsp) rather than downloaded at runtime. It stays a
# SUBPROCESS and a separate file on purpose: pkl-lsp's `exit` notification —
# which every LSP client sends on disconnect — calls exitProcess(0), so
# embedding it would let a closed editor tab kill the dashboard. Its shaded jar
# also bundles an unrelocated JNA 5.14.0 that collides with appdirs' 5.18.1 on
# a shared classpath.
COPY target/addon/pkl-lsp.jar /opt/pkl-lsp.jar
ENV PKL_LSP_JAR=/opt/pkl-lsp.jar

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
