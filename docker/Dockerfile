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
#     git submodule update --init service/src/main/webui
#     (cd service/src/main/webui && npm ci && npm run build)
#     docker build -t qits/artifacts:latest -f docker/Dockerfile .
#
# The first two lines are not optional and are not a convenience: this image carries the artifacts
# client (service/src/main/webui = the qits-spa-artifacts submodule), and the builder stage below
# packages a bundle that was ALREADY BUILT rather than building one. See the Quinoa flags on the
# mvnw line for why the build cannot happen inside that stage.
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
#
# `localhost:8081/quay/…` is qits-artifacts' pull-through mirror of quay.io: the first build fetches
# the image from upstream and caches it, every later build is served from local disk. The tag is
# upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when quay moves it.
FROM localhost:8081/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
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
# --- the Quinoa flags, and why this stage no longer builds the client -----------------------------
#
# package-manager-install: the Mandrel builder image ships no node at all, so this stage installs its
# own — from nodejs.org, which a docker build can reach. It lives here rather than in
# application.properties on purpose: a developer machine already has node and should keep using its
# own, and only this stage has to be told, so that no build silently downloads a toolchain. 22.22.0
# is the PLATFORM pin, the node every other qits service's builder stage installs and the version a
# developer's host carries. Note it is deliberately NOT the step container's node: node-docker-base
# is node:24-alpine, and the bundle this image consumes was built there. The two do not have to
# agree, because this stage does not build the bundle — the commands below are neutered to
# `--version`, so the only thing node does here is exist and prove its package manager runs.
#
# The three command overrides are the load-bearing part, and the reason this file deviates from the
# platform's Quinoa recipe (docs/project-setup-quinoa-angular.md in the qits monorepo, which has the
# builder run the client's real install and build):
#
#   service/src/main/webui is qits-spa-artifacts, and it depends on @qits/ui-components. That package
#   exists ONLY on the platform's own npm registry — which is THIS SERVICE, and that changes nothing
#   about the problem: a RUN step in a docker build reaches the public internet (Maven Central,
#   npmjs.org, nodejs.org) and can reach the platform registry by NO address, not the qits-net alias,
#   not localhost:8081, not host.docker.internal. Being the registry does not give the build a route
#   to it; the running container is on qits-net and this stage is not. So the install cannot happen
#   here — it hangs on the localhost:8081 the submodule's own .npmrc names and then dies on npm's
#   `Exit handler never called!`, which says nothing about why — and an install that "worked" here
#   would have resolved the @qits scope against npmjs.org, where that name does not exist and never
#   will.
#
# The bundle is therefore built BEFORE this image, by the pipeline step in
# .config/qits/ci-post-receive.yml, which runs on qits-net where the registry answers, and arrives in
# the build context as service/src/main/webui/dist/ (see .dockerignore). `--version` is the null
# command: it proves the package manager is really there and does nothing else.
#
# THIS NO LONGER FAILS QUIETLY, and that is a change in kind. What this comment used to say was that
# a context whose webui submodule is NOT initialised leaves Quinoa with no package.json, so it
# disables itself with a warning and this stage stays GREEN while shipping no client — a service
# that boots, serves /artifacts/ as a 404, and looks deployed. The `test -f` below is the end of
# that: a missing bundle, from a forgotten submodule init or a forgotten `npm run build` in the
# step, is now a red build, stopped before the multi-minute native compile rather than discovered in
# production.
#
# --- and why the bundle is copied onto itself first -----------------------------------------------
# Quinoa does not copy build-dir into target/quinoa/build, it MOVES it, and overlayfs cannot rename a
# directory that still lives in a LOWER image layer. It answers EXDEV, the JDK falls back to its
# cross-file-store path, and that path refuses a non-empty directory — the build dies seconds in with
# `DirectoryNotEmptyException: …/dist/qits-spa-artifacts/browser`, which names the source and
# explains nothing. A builder stage that *creates* dist itself is immune by accident; ours arrives by
# COPY.
#
# One `cp` re-materialises it in the layer that is about to move it. It has to be in THIS `RUN` and
# not a tidier one of its own — a layer boundary is exactly what it is undoing.
RUN d=service/src/main/webui/dist/qits-spa-artifacts/browser; \
    test -f "$d/index.html" || { \
      echo "No built client at $d." >&2; \
      echo "The build context must carry one: run 'npm ci && npm run build'" >&2; \
      echo "in service/src/main/webui first — this stage cannot, see the Quinoa flags above." >&2; \
      exit 1; }; \
    cp -a "$d" "$d.layer" && rm -rf "$d" && mv "$d.layer" "$d" && \
    ./mvnw -B -ntp -pl service -am package -Dnative -DskipTests \
      -Dquarkus.native.native-image-xmx=4g \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=22.22.0 \
      -Dquarkus.quinoa.package-manager-command.install=--version \
      -Dquarkus.quinoa.package-manager-command.ci=--version \
      -Dquarkus.quinoa.package-manager-command.build=--version

# --- runtime --------------------------------------------------------------------------------------
# `localhost:8081/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM localhost:8081/redhat/ubi9/ubi-minimal:9.6
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
