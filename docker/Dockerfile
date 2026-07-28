# qits-artifacts as a GraalVM-native container IMAGE — the same shape as qits-gateway's and
# qits-workspace-daemon's docker/Dockerfile: a Mandrel builder stage compiles the binary, a minimal
# runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names a
# GraalVM (25.0.2-graalce), so `sdk env && ./mvnw verify -Dnative` produces
# service/target/qits-artifacts locally in about a minute with no container involved. What this adds
# is the image around it — and a way for a machine with no GraalVM (a CI runner, a laptop on a plain
# OpenJDK) to get a binary at all, since the builder stage brings its own native-image.
#
# Build from the repo root:
#
#     docker build -t qits/artifacts:latest -f docker/Dockerfile .
#
# --- this image does NOT run with an empty environment, and that is not a packaging bug ------------
# `docker run --rm qits/artifacts:latest` dies before the HTTP listener with
#
#   FlywaySqlUnableToConnectToDbException: ... A file path that is implicitly relative to the current
#   working directory is not allowed in the database URL "jdbc:h2:file:?/.qits/data/artifacts/h2/artifacts"
#
# That `?` is `${user.home}`. UID 1001 has no /etc/passwd entry in ubi-minimal, and a native image
# resolves user.home through getpwuid rather than $HOME — so setting HOME does not fix it, and the
# three defaults this repo ships (the H2 file, the blobs dir, the git data-dir) have nowhere to land.
# Give the datasource an absolute url and it comes up in ~0.1s, Flyway migration included.
#
# An /etc/passwd line for 1001 would make it boot with no environment at all. It is deliberately not
# here: every one of those paths would then resolve inside the container's writable layer, and this
# service's data is not its own. `qits.repositories.data-dir` is a three-way on-disk contract —
# qits-projects clones bare origins into it and qits-workspaces branches from them, so all three
# must resolve it to the SAME volume or the git host serves an empty tree. A container that came up
# healthy on an ephemeral copy is the failure that hides; one that refuses to start is the failure
# you see. So a deployment mounts a volume at /data (which this image owns, see the runtime stage)
# and names all three:
#
#   docker run -v qits-artifacts-data:/data \
#     -e QUARKUS_DATASOURCE_ARTIFACTS_JDBC_URL=jdbc:h2:file:/data/artifacts/h2/artifacts \
#     -e QITS_ARTIFACTS_BLOBS_DIR=/data/artifacts/blobs \
#     -e QITS_REPOSITORIES_DATA_DIR=/data/repositories \
#     qits/artifacts:latest
#
# One more has no safe default and no error when it is wrong: `qits.ci.intake-url`, where the git
# host's post-receive hook delivers. CiPostReceiveNotifier is fire-and-forget and swallows delivery
# failures at debug, so an unset or wrong host produces no log line anywhere and CI simply never
# runs. Point it at qits-ci on qits-net. See README.md for the whole config surface.
#
# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole reactor, not a module list. `service` depends on `artifacts`, a 1.0.0-SNAPSHOT this repo
# never publishes (no distributionManagement, no <repositories>), so a builder container with an
# empty ~/.m2 has nowhere else to get it — the sources have to be in the context. Copying module by
# module would mean editing this file every time one is added, and forgetting to would fail the
# build with an unresolved dependency rather than anything that names the real cause.
# `.dockerignore` is what keeps this cheap — without it this line drags in the ~250 MB of target/
# already sitting here.
COPY . .

# The whole reactor again, for the same reason: no `-pl service -am`. That would build exactly
# today's dependency graph, so a module added tomorrow would be silently absent from the image
# rather than merely unused. Both modules are on `service`'s path anyway, so selecting costs
# nothing and buys nothing.
#
# -Dnative flips quarkus.native.enabled (the `native` profile lives in service/pom.xml — only that
# module is an application). Cap native-image RAM so the build is deterministic on small machines.
#
# Tests are skipped because they are not skippable HERE: GitHostTest shells `git init`/`git clone
# --bare` to seed its origins, and the `native` profile flips skipITs so PackagedProcessIT would
# launch the freshly built binary as a second process inside this stage. `./mvnw verify -Dnative` on
# a clone is the gate for both.
RUN ./mvnw -B -ntp package -Dnative -DskipTests \
      -Dquarkus.native.native-image-xmx=4g

# --- runtime --------------------------------------------------------------------------------------
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.6
WORKDIR /work

# `quarkus.package.output-name` names the binary `qits-artifacts` (no -runner suffix), so the process
# is identifiable in `ps`/`docker top`. That name is spelled a third time in service/pom.xml as
# failsafe's `native.image.path`; all three move together.
COPY --from=build --chown=1001:root /src/service/target/qits-artifacts /work/qits-artifacts
RUN chmod +x /work/qits-artifacts

# The mount point for everything above, created here so it is OWNED. Docker seeds a fresh named
# volume from whatever the image has at the mount path, ownership included; with no such directory
# it makes one root-owned 0755 and the boot dies on `AccessDeniedException: /data/artifacts` instead
# of the config error, which reads like a bug in this image and is not one. Group-writable and
# group-root because a ubi image is meant to survive being assigned an arbitrary UID with GID 0.
# (A bind mount still takes its ownership from the host — nothing in an image can fix that.)
RUN mkdir -p /data && chown 1001:root /data && chmod 775 /data

# One port for all of it — /artifacts/api/** (JAX-RS), /artifacts/q/** (openapi, swagger-ui) and
# /artifacts/git/** (raw Vert.x) are three route stacks in one process, not three listeners.
EXPOSE 8080
USER 1001

# Quarkus binds 127.0.0.1 outside dev mode, which inside a container means nothing can reach it —
# not the gateway, not a peer on qits-net.
ENTRYPOINT ["/work/qits-artifacts", "-Dquarkus.http.host=0.0.0.0"]
