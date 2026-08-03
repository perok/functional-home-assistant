# FH Dashboard add-on image. Build context = repo root; both jars must be
# staged first:
#   sbt 'fh-datastar-view/assembly ; pkl-lsp-dist/stagePklLsp'
#   docker buildx build -f home-addon/Dockerfile -t ghcr.io/perok/fh-dashboard:dev .
# The jars are arch-independent JVM bytecode; the multi-arch alpine temurin
# builder + hassio base yield linux/amd64 + linux/arm64 without
# cross-compiling Scala (only the jlink stage runs per-arch).
# https://hub.docker.com/_/eclipse-temurin#creating-a-jre-using-jlink
#
# JDK 25, not 21, because pkl-lsp is compiled to class file 67 (Java 23+) and
# the editor's LSP subprocess runs on this same runtime — on 21 it died with
# UnsupportedClassVersionError, so /edit had no completion in the add-on at all.
FROM eclipse-temurin:25-alpine AS jre-build

# Custom Java runtime: the module list is jdeps output for the assembled jar
# (jdeps --multi-release 25 --print-module-deps --ignore-missing-deps
# target/addon/fh-dashboard.jar) + jdk.crypto.ec, which jdeps cannot see
# statically but TLS needs for the HTTPS theme-asset fetches, + jdk.zipfs,
# which jdeps likewise cannot see statically but pkl-core needs at runtime to
# open its cached packages via a "jar:" NIO FileSystemProvider, + java.logging
# and java.desktop for the pkl-lsp child (lsp4j logs via java.util.logging).
RUN $JAVA_HOME/bin/jlink \
         --add-modules java.base,java.compiler,java.logging,java.management,java.naming,java.net.http,java.sql,java.desktop,jdk.unsupported,jdk.crypto.ec,jdk.zipfs \
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
