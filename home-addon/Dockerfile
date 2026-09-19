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
# The version is read from the same file `build.sbt` reads, so the library and
# the polyglot jars cannot drift apart. They must not — a mismatched pair runs
# with no warning, no error and correct output, and is simply a different
# GraalJS than the build declares.
#
# `TARGETARCH` is buildx's spelling (`amd64`/`arm64`) and GraalVM says
# `aarch64`; that mapping is the entire per-architecture difference. curl is
# installed because the temurin images ship neither curl nor wget.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS isolate
ARG TARGETARCH
COPY home-addon/graalvm-js.version /tmp/
RUN set -eu; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl; \
    rm -rf /var/lib/apt/lists/*; \
    V="$(tr -d '[:space:]' < /tmp/graalvm-js.version)"; \
    case "$TARGETARCH" in \
      amd64) ARCH=amd64 ;; \
      arm64) ARCH=aarch64 ;; \
      *) echo "no GraalVM JS isolate is published for $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    A="js-isolate-linux-$ARCH"; \
    curl -fsSL -o /tmp/isolate.jar \
      "https://repo1.maven.org/maven2/org/graalvm/js/$A/$V/$A-$V.jar"; \
    mkdir /isolate; \
    cd /isolate; \
    jar xf /tmp/isolate.jar \
      "META-INF/resources/engine/$A/libvm/libpolyglotisolate.so"; \
    mv "META-INF/resources/engine/$A/libvm/libpolyglotisolate.so" .; \
    rm -rf META-INF /tmp/isolate.jar

# hassio base: s6-overlay init (config.yaml sets init: false), bashio + jq
# already included (run.sh only needs jq, and the s6 container_environment
# re-import). The DEBIAN variant, for the glibc reason above; it is the same
# single multi-arch manifest as the alpine one, so buildx still builds both
# platforms from this one Dockerfile.
FROM ghcr.io/hassio-addons/debian-base:9.4.0

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
COPY --from=jre-build /javaruntime $JAVA_HOME

# GraalJS. The path is named to the app rather than discovered, and carries no
# version, so `FH_JS_ISOLATE_LIBRARY` is a constant: the alternative is letting
# Truffle extract the library out of a jar on first use, which writes 159 MB
# into a cache keyed by a hash of the library — once per GraalVM version, with
# nothing pruning the copy the update replaced.
#
# The library needs libz.so.1 alongside glibc; the base image has both.
COPY --from=isolate /isolate/libpolyglotisolate.so /opt/fh/
ENV FH_JS_ISOLATE_LIBRARY=/opt/fh/libpolyglotisolate.so

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
