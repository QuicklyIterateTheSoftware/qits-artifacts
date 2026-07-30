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
# you see.
#
# The three paths are NOT the same kind of state, and this image treats them differently:
#
#   the git tree      `qits.repositories.data-dir` is settled, and nothing in it is stored in SQL —
#                     it is bare origins on disk. The image names it itself (see the runtime stage)
#                     so it is correct out of the box, and it names the SAME literal qits-projects
#                     and qits-workspaces ship, so one volume satisfies the three-way contract
#                     without a deployment having to get one string right in three places.
#
#   the database      the prototype. Deliberately unnamed here; the service will not start until a
#                     deployment says where it goes.
#
#   the blob dir      files on disk, but every one of them is indexed by a row in that database —
#                     blobs whose index did not survive are not data, they are orphans. So it stays
#                     with the database rather than with the git tree, and is named alongside it.
#
# So a deployment mounts a volume at /data (which this image owns, see the runtime stage) and names
# the two that belong to the prototype:
#
#   docker run -v qits-artifacts-data:/data \
#     -e QUARKUS_DATASOURCE_ARTIFACTS_JDBC_URL=jdbc:h2:file:/data/artifacts/h2/artifacts \
#     -e QITS_ARTIFACTS_BLOBS_DIR=/data/artifacts/blobs \
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

#
# -Dnative flips quarkus.native.enabled (the `native` profile lives in service/pom.xml — only that
# module is an application). Cap native-image RAM so the build is deterministic on small machines.
#
# Tests are skipped because they are not skippable HERE: GitHostTest shells `git init`/`git clone
# --bare` to seed its origins, and the `native` profile flips skipITs so PackagedProcessIT would
# launch the freshly built binary as a second process inside this stage. `./mvnw verify -Dnative` on
# a clone is the gate for both.
#
# `-pl service -am` builds the app and its dependency closure, not the whole reactor. That is not a
# module list — it names the one module this image ships, which the COPY below already names anyway,
# so nothing is gained by leaving it out. What it buys: this repo will publish libraries as well as
# an image, and a reactor then holds modules that exist for consumers rather than for the binary. On
# a whole-reactor build a compile error in one of those would block deploying this service, and build
# time would grow with the published surface instead of with the app. The image build should depend
# on exactly what the image contains.
# The two `package-manager-install` flags are how the Angular client gets built HERE. Quinoa shells
# out to npm and a Mandrel builder image has no node, so without them this stage fails the moment the
# webui submodule is present in the context. They are on the command line rather than in
# application.properties on purpose: a developer machine already has node and should keep using its
# own, and only this stage has to be told. The version is pinned so the image is reproducible rather
# than built against "whatever nodejs.org calls current".
#
# With the submodule NOT initialised in the build context, Quinoa finds no package.json, disables
# itself with a warning, and this stage stays green while shipping no client.
RUN ./mvnw -B -ntp -pl service -am package -Dnative -DskipTests \
      -Dquarkus.native.native-image-xmx=4g \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=22.22.0

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
RUN mkdir -p /data/repositories && chown -R 1001:root /data && chmod -R 775 /data

# The git tree, named here rather than left to a deployment: it is not the prototype, and nothing in
# it is stored in SQL. `/data/repositories` is a literal qits-projects and qits-workspaces also ship
# — the three-way contract in the header only holds if all three resolve it to the same volume, and
# three services each told the same string separately is three chances to get it wrong.
#
# Note what is NOT set beside it: no QUARKUS_DATASOURCE_* and no QITS_ARTIFACTS_BLOBS_DIR. That
# asymmetry is the point — the git host works out of the box, the prototype's storage refuses to.
ENV QITS_REPOSITORIES_DATA_DIR=/data/repositories

# One port for all of it — /artifacts/api/** (JAX-RS), /artifacts/q/** (openapi, swagger-ui) and
# /artifacts/git/** (raw Vert.x) are three route stacks in one process, not three listeners.
EXPOSE 8080
USER 1001

# Quarkus binds 127.0.0.1 outside dev mode, which inside a container means nothing can reach it —
# not the gateway, not a peer on qits-net.
ENTRYPOINT ["/work/qits-artifacts", "-Dquarkus.http.host=0.0.0.0"]
