# qits-artifacts

qits' **byte plane**: the metadata-rich blob store, the in-process git smart-HTTP host workspace
containers clone from and push to, and the OCI registry they pull images from.

Three things, one repo, because all three are "qits serves bytes over HTTP against on-disk state it
owns" and none has an inbound edge from the rest of qits. The registry is the third use of the byte
plane and the first the code predicted — `RepositoryType` already reserved the seam — and it needed
no new storage layer, because a content-addressed SHA-256 blob store *is* the OCI blob model. The
git host landed here rather than in a
`qits-repositories` service because that name collides with `domain.repository` — see
`migration-plan.md` §3.4 in the home repo.

    mvn verify        # a clone of this repo alone builds and tests green — no monorepo, no docker

## Layout

| Module | What |
|---|---|
| `artifacts/` | `eu.wohlben.qits.artifacts.*` — entity, persistence, dto, mapper, control, error. The blob store proper. No web, no JAX-RS. |
| `service/` | `eu.wohlben.qits.artifacts.api` (the JAX-RS boundary), `eu.wohlben.qits.githost` (the Vert.x + JGit smart-HTTP host) and `eu.wohlben.qits.registry` (the Vert.x OCI Distribution API). |

`artifacts/` is a library jar. **`service/` is the application** — it carries
`<packaging>quarkus</packaging>` and produces a process, as a JVM fast-jar or as a native binary:

    ./mvnw verify
    java -jar service/target/quarkus-app/quarkus-run.jar   # :8080 — blobs /artifacts/api/**, git /artifacts/git/**, images /v2/**

    ./mvnw verify -Dnative
    ./service/target/qits-artifacts                        # same routes, ~35ms to listening

**Native is the shipping form.** `.sdkmanrc` names a GraalVM (`25.0.2-graalce`) so `sdk env` alone
is enough toolchain: the build wants a `native-image` on `GRAALVM_HOME`, `JAVA_HOME` or `PATH`, and
finding none it does not fail — it falls back to pulling a 1.8 GB Mandrel image and compiling under
docker. That fallback still works and is what a CI without a GraalVM gets; it is just not the
intended path, and it is worth recognising by name when a build that normally takes a minute starts
downloading a container image.

`-Dnative` also flips `skipITs`, so it runs `PackagedProcessIT` against the binary it just built —
openapi, swagger-ui, a blob round trip, a real `git clone` + `push`, and an image push/pull. That
suite is the only thing in this repo that exercises **JGit compiled ahead of time**, which is the
part of this service most likely to break in a native image (see "The git host" below), and the only
thing that exercises the registry's zero-copy `sendFile` blob serving in a binary.

It was extracted as a library, on the reasoning that packaging it would need an auth variant, a
webui and a main class. All three have lapsed: authentication terminates at `qits-gateway` and this
service reads a header, the webui stays in the monorepo, and Quarkus supplies the main class.

Everything is served under the `/artifacts` gateway segment, because `qits-gateway` routes
verbatim by prefix and rewrites nothing — there is no unprefixed form, on `qits-net` either.

Note the two route stacks resolve differently: `/artifacts/api/**` is JAX-RS and moves with
`quarkus.rest.path`; `/artifacts/git/**` is registered straight onto the Vert.x router with the
segment as a literal, and does not. A `git clone http://<host>/artifacts/git/<repoId>` against the
packaged process is the check that matters, because nothing in the JAX-RS configuration can prove
it.

`artifacts/` owns its **own datasource, persistence unit and Flyway lineage**
(`db/artifacts/migration`, a separate H2 under `~/.qits/data/artifacts`). It always did — that
lineage was never part of the monorepo's shared `db/migration`, so unlike every other extraction
target nothing here had to be squashed or rebased. `V1__init.sql` is the original file, unchanged.

That H2 is **embedded only** — the url no longer carries `AUTO_SERVER=TRUE`. Automatic mixed mode
made sense while this was a library inside the monolith and a second JVM might open the same file;
as its own process on its own volume a second writer would be a bug, and the option is fatal to the
native binary, which has no `org.h2.server.TcpServer` to start.

## The blob store

A **repository** is a name plus a type (`ci-screenshots`, `ci-videos`); the type selects the
validation profile and the size cap. A **record** is one immutable upload: the content id is the
SHA-256 of the bytes, so many records may share one blob and stay distinct rows.

Metadata is a flat `String -> String` map, delivered as `X-Artifacts-Meta-*` request headers and
queried as exact-match `?meta.<key>=<value>` predicates, with a `?latest` collapse. That is the
whole coupling model: **blobs reference qits branches and flows by string metadata, never by
foreign key.** Nothing here breaks when qits' schema moves, and nothing here needs a JPA relation
into another context's tables.

    PUT  /artifacts/api/repositories/{repo}                 ensure a repository (token-guarded)
    POST /artifacts/api/repositories/{repo}/blobs           upload, raw body + X-Artifacts-Meta-*
    GET  /artifacts/api/repositories/{repo}/blobs/{id}      serve — open, cacheable, immutable
    GET  /artifacts/api/repositories/{repo}/blobs?meta.…    query

A **repository** here is a named bucket of artifacts — the Maven/npm sense of the word, not
`domain.repository`. The resource keeps that name; the `artifacts` the path used to repeat is gone,
because the segment already says it.

Writes are guarded by a single static token (`X-Artifacts-Token`, config `qits.artifacts.token`;
blank in dev/test disables the guard). Reads are never guarded — a blob must be usable directly as
an `<img>`/`<video>` src.

## The git host

`GitHostRoutes` mounts JGit's `UploadPack`/`ReceivePack` on plain Vert.x routes at `/artifacts/git/*` —
deliberately **not** as a servlet, because `quarkus-undertow`'s presence breaks Quinoa's production
static serving in the consuming app. JGit speaks the wire protocol and nothing else; the git CLI
remains the only thing that mutates a repository.

JGit is **not** a Quarkus extension, so nothing tells the native compiler about it and the whole git
host is the part of this service that a GraalVM build breaks silently. Three things had to be
declared, all of them in `service/src/main/resources/application.properties` and
`githost/JGitReflection.java`, and each failed only in the binary while `mvn verify` stayed green:
JGit's statics that cannot be frozen into an image (a `Random`, a started thread pool), and the enum
`values()` methods `Config.getEnum` recovers reflectively — without which *every* repository fails
to open and the host answers 404 to everything, indistinguishable from an unknown repo id.

`git` is a second-level segment beside `api`, not `/artifacts/api/git/*`: it is a wire protocol
spoken by `git`, not a JSON API, and it appears in no OpenAPI document. Git treats the url as an
opaque base and appends the suffixes itself, so a base of any depth works — the segment is a
routing decision, not a protocol one.

There is **no authentication** on `/artifacts/git/*`. Repo ids are capability UUIDs and the callers
are workspace containers, which cannot hold a user session. A deployment must therefore allow-list
`/artifacts/git/*` (in the monorepo that is `auth/core`'s `PublicPaths`).

Two addressing schemes, told apart by path length — which the fixed prefix preserves, since it adds
one segment to both:

- `/artifacts/git/:repoId` — the opaque UUID, resolving to `<data-dir>/<repoId>/origin`.
- `/artifacts/git/:projectId/:repoName` — a project's repositories served as siblings, so committed
  relative submodule urls (`../<name>.git`) resolve natively. Needs the `RepositoryNameResolver`
  port.

This base is a **cross-repo contract**: qits-ci fetches pipeline config from it and
qits-workspace-daemon's `Provisioner` clones from it, both against the literal `/artifacts/git`.

After a successful push, `CiPostReceiveNotifier` POSTs `{repoId, branch, oldSha, newSha}` per
updated branch ref to `qits.ci.intake-url`. That was already an HTTP call inside the monolith, which
is exactly why the artifacts→ci seam survived the split untouched: only the url moves when
[qits-ci](https://github.com/QuicklyIterateTheSoftware/qits-ci) becomes its own deployable. It is
fire-and-forget — a failed delivery is a missed advisory run, never a failed push.

## The OCI registry

`RegistryRoutes` serves the [OCI Distribution
API](https://github.com/opencontainers/distribution-spec) at the literal `/v2`, so an image pushed
here runs anywhere with one standard command:

```
docker run -it <host>/qits/alpine:latest
podman run     <host>/qits/alpine:latest
```

The mount point is not a choice. Docker and podman resolve a reference against `<host>/v2/` and
accept no path prefix, so this cannot live under `quarkus.rest.path` the way the JSON API does — it
is raw Vert.x at the host root, a literal in the code exactly as `/artifacts/git` is, and nothing in
the JAX-RS configuration moves it. `RegistryTest` is the only thing that would notice if it drifted.

Storage is the blob store, unchanged: OCI addresses every layer, config and manifest as
`sha256:<hex>`, which *is* `BlobStore`'s model, so layers dedupe globally with everything else. Two
small tables carry what content addressing cannot: `oci_manifest` scopes a manifest to a
`(repository, image)` — without it the globally-deduped store would serve one repository's manifest
out of another's namespace, and an index's untagged children would be unresolvable — and `oci_tag`
holds the one piece of mutable state in the registry, a movable pointer at a manifest digest.

### Creating a repository

Repositories are **not** created implicitly. The first segment of an image name is an
`oci-images`-typed row in `artifact_repository`, created with the ordinary ensure endpoint:

```
curl -X PUT http://<host>/artifacts/api/repositories/qits \
  -H 'Content-Type: application/json' -H "X-Artifacts-Token: $QITS_ARTIFACTS_TOKEN" \
  -d '{"type":"oci-images"}'
```

Then `qits/alpine:latest` works, and `qits/build-images/ci-base:latest` is repository `qits`, image
`build-images/ci-base`. A push to an unknown first segment is `404 NAME_UNKNOWN` with a message
naming this command; a single-segment reference (`docker push <host>/alpine`) is `400 NAME_INVALID`,
because it has no repository/image split at all.

### Reaching it from a client

Over plain HTTP a client needs a one-time opt-in — this is deployment configuration, not something
the registry can do for you. It disappears entirely behind a TLS-terminating gateway.

```jsonc
// docker: /etc/docker/daemon.json, then `sudo systemctl restart docker`
{ "insecure-registries": ["qits-host:8080"] }
```

```toml
# podman: /etc/containers/registries.conf.d/qits.conf
[[registry]]
location = "qits-host:8080"
insecure = true
```

`localhost` is special-cased as insecure by both, so a same-host smoke test needs neither — and a
test that only ever uses `localhost` proves nothing about a real deployment.

Through qits-gateway the registry is reached at the same `/v2` on the gateway's port. The gateway
routes it from the **artifacts** `proxy-hosts` entry (there is no `…_V2` key) and allow-lists
`/v2/*`, which is required: a registry client sends none of the headers the gateway uses to tell a
navigation from a background request, so an unlisted `/v2` would answer a 302 into the IdP that
docker reads as "not a v2 registry".

### Pushing with a token configured

Reads are anonymous, always — image names are meant to be shared, which is also why `/v2/_catalog`
stays unimplemented and the posture stays private-network rather than capability-URL. Writes are
guarded by the same `qits.artifacts.token` the JSON API uses, presented as an HTTP Basic password
(the username is ignored) because that is what a registry client can send.

`GET /v2/` is unconditionally 200, so an anonymous `docker pull` works with no login at all. **What
that costs depends on the client**, and the two answers were measured rather than reasoned about:

| Client | Push to a token-guarded registry |
|---|---|
| `docker` | **works** — `docker login <host>` once, then `docker push`. Tested on docker 29.6.2: the client retries a `401` with its stored credentials whatever the `/v2/` ping said, so a non-challenging ping costs it nothing. Without a login it fails cleanly with `no basic auth credentials`. |
| `skopeo`, `podman` | **fails.** Both sit on `containers/image`, which picks the auth scheme from the ping: a 200 with no `WWW-Authenticate` is read as "no credentials needed", so `--creds` is never sent and the `401` on the first blob upload is fatal rather than retried. |

```
docker login qits-host:8080 -u qits -p "$QITS_ARTIFACTS_TOKEN"
docker push qits-host:8080/qits/alpine:latest
```

With a **blank** token — the container-network posture, where the registry is unreachable from
outside the deployment — every client works, including `skopeo copy` and `podman push`.

If skopeo or podman ever have to push to a *guarded* deployment, the fix is to make `GET /v2/`
answer `401` + `WWW-Authenticate` when a token is configured. That is a real behaviour change and
should be measured against anonymous `docker pull` first, not assumed: the reasoning that predicted
today's split got both halves of it backwards.

### Deliberately not implemented

`DELETE` (405) and `/v2/_catalog` (404), because there is no garbage collection: the store is
append-only, untagged manifests and orphaned blobs accumulate, and that is acceptable at
private-deployment scale. Nothing should come to depend on deletion semantics before they exist.
`/v2/<name>/referrers/` is also absent; a manifest's `subject` is parsed and ignored rather than
required to resolve.

`sha512` content is rejected: the blob store is SHA-256 throughout — the content id *is* the
sha256 — so a `sha512:` digest answers `400 DIGEST_INVALID`, which is the spec's own SHOULD for an
unsupported algorithm. sha256 is the one algorithm the spec requires.

### Conformance

`RegistryTest` and `PackagedProcessIT` drive a client this repo wrote, so they can only assert the
reading of the spec that went into writing it. `OciConformanceIT` runs
[the upstream suite](https://github.com/opencontainers/distribution-spec/tree/main/conformance)
instead — several hundred assertions nobody here authored — against the packaged process.

It is **opt-in and run on demand**, never in a pipeline and never in a plain `verify`: the suite is
a Go binary with no published release, and a clone of this repo must keep building green with
nothing but a JDK. Build it once, then point the IT at it:

```
git clone https://github.com/opencontainers/distribution-spec.git
cd distribution-spec/conformance && go build -o conformance .    # needs Go >= 1.24

./mvnw verify -DskipITs=false \
    -Doci.conformance-binary=/abs/path/to/distribution-spec/conformance/conformance
```

Add `-Dit.test=OciConformanceIT` to run only this one, or `-Dnative` to exercise the GraalVM binary
instead of the fast-jar. **Without the property the IT skips**, which is what keeps `-Dnative` —
which flips `skipITs` — from starting to need Go.

The IT ensures the `conformance` repository over the REST API first (nothing is created implicitly),
then asserts on the suite's `junit.xml`: zero failures, zero errors. It parses that file rather than
trusting the exit code, because the binary's `main` *returns* — status 0 — when its config fails to
load, having written no report at all. Failures name the failing testcases and the path to
`report.html`, which carries every request and response.

**Three capabilities are declared `false`, and they are design decisions of this service, not
failing tests being hidden.** The suite has no notion of "this registry chose not to implement
that"; every optional API is a flag the operator declares, and leaving one on that the registry
never serves fails tests for an endpoint it states it does not have:

| Flag | Why |
|---|---|
| Flag | Cost of leaving it on | Why it is off |
|---|---|---|
| `OCI_API_BLOBS_DELETE`, `OCI_API_MANIFESTS_DELETE`, `OCI_API_TAGS_DELETE` | **0 failures** (283 tests move from Disabled to Skip) | `DELETE` is 405 by design — the store is append-only and there is no garbage collector, so deletion has no meaning here yet. See "Deliberately not implemented" above. |
| `OCI_API_REFERRER` | **+15 failures** | `/v2/<name>/referrers/` is absent. The spec makes it optional in as many words: a 404 is the defined "referrers API unavailable" signal with a mandated client fallback to the referrers tag schema, and the `OCI-Subject` response header is required only of "a registry implementation that supports the referrers API". |
| `OCI_DATA_SHA512` | **+81 failures** | The blob store is SHA-256 only, and answers `400` to a sha512 digest — the spec's own SHOULD for an unsupported algorithm. sha256, the required one, stays fully exercised. |

Everything the spec makes mandatory is left on. The costs above are measured, one flag at a time,
not estimated — and the first row is the one worth knowing: **the delete flags hide nothing.** The
suite tracks an endpoint that answers with a valid "unsupported" status rather than failing it, and
this registry's `405 UNSUPPORTED` is exactly that, so declaring the three is a statement of intent
and not a shield. Turn them on and the count stays 430 pass / 2 fail; only the label on 283 tests
changes.

#### Conformance status

Against `distribution-spec` `967efdc` (spec 1.1): **586 run, 0 failures, 0 errors, 6 skipped**,
under the capability declarations above.

The suite found two genuine non-conformances on its first run, and both are fixed. Recorded because
each was a deliberate design choice whose consequence was a wrong status code, and each is the kind
of thing a client-side test written from our own reading of the spec cannot catch — `RegistryTest`
and `PackagedProcessIT` drive a client built from the same reading that was wrong:

- **The final `PUT` of a chunked upload did not validate `Content-Range`.** An out-of-order `PATCH`
  was correctly refused with 416, but the final `PUT` skipped that check, appended the bytes anyway,
  and failed the digest comparison with `400 DIGEST_INVALID`. The same rejection with the wrong
  diagnosis: a resumable client was told its content was corrupt when its offset was stale. The spec
  makes 416 a **MUST** here. Fixed in `RegistryRoutes.finishUpload`; covered by
  `RegistryTest.theFinalChunkOfAnUploadMustStartWhereTheSessionStands`.
- **An invalid digest in a manifest reference answered 404.** `sha256:baddigeststring` matched
  neither alternative of the old `REF` regex, so it missed the route and hit the catch-all — telling
  a client the manifest was absent when its request was unusable. The spec asks for 400. `REF` now
  matches any non-slash segment and the handler judges well-formedness, the same stance the upload
  session id already took; covered by
  `RegistryTest.aMalformedReferenceIsRejectedRatherThanReportedAbsent`.

Both fixes are guarded by ordinary unit tests, not by the conformance suite: it is opt-in and needs
Go plus a checkout of the spec, so a regression must be catchable by `mvn verify` alone.

If the suite ever reports a failure, **do not widen the IT's assertion or filter the case out** —
either the registry is wrong, or a capability declaration above is.

## The boundary

Everything this context needs from the rest of qits goes through a port it declares and the
consuming application implements:

| Port | Required? | Absent means |
|---|---|---|
| `RepositoryNameResolver` | no | `/artifacts/git/:projectId/:repoName` is 404; `/artifacts/git/:repoId` — the older scheme and the daemon's own fallback — still serves |

That is the only one. In the monorepo `GitHostRoutes` injected
`domain.repository.persistence.RepositoryNameRepository` directly; that alias table belongs to the
projects/repositories context, and this repo holds no foreign key into another context's schema.
The inline `QuarkusTransaction.requiringNew()` around the lookup moved into the port's contract —
the resolver is called on a Vert.x worker thread with no request context bound.

## Config

Defaults ship from each jar's `META-INF/microprofile-config.properties` (ordinal 100); the consuming
app's `application.properties` overrides them.

| Key | Default | What |
|---|---|---|
| `qits.artifacts.blobs-dir` | `~/.qits/data/artifacts/blobs` | content-addressed blob bytes |
| `qits.artifacts.token` | blank (open) | the write guard |
| `qits.artifacts.startup-seed.enabled` | `true` | self-seed `ci-screenshots` + `ci-videos` |
| `qits.repositories.data-dir` | `~/.qits/data/repositories` | where the git host finds `<repoId>/origin` |
| `qits.ci.intake-url` | `http://localhost:8080/ci/api/events/post-receive` | post-receive delivery |
| `qits.ci.token` | blank | `X-CI-Token` on those events |
| `qits.artifacts.oci.max-layer-size` | `1G` | the registry's per-layer cap, enforced while streaming |
| `qits.artifacts.oci.max-manifest-size` | `4M` | manifests are buffered whole to be digested and parsed |
| `qits.artifacts.oci.upload-session-ttl` | `PT30M` | in-memory upload sessions; lost on restart, by design |
| `qits.artifacts.oci.upload-idle-timeout` | `PT1M` | wait for the *next* chunk, not for the whole upload |
| `qits.repositories.git.max-pack-size` | `64M` | the git host's `BodyHandler` limit |
| `quarkus.http.limits.max-body-size` | `1088M` | **global**; above the largest upload cap |

The last one is a global ceiling, but not the single hard gate this section used to claim. Quarkus
enforces it in two places: a route at order −2 that 413s a declared `Content-Length` over the limit
(nothing bypasses that), and — for a request with **no** `Content-Length` — nowhere at all, beyond
stashing the number for whatever reads the body. A chunked upload to a raw Vert.x route is therefore
bounded only by what that route enforces itself, which is why the registry reads through the same
limit-aware stream RESTEasy uses and caps the layer while streaming.

It is `1088M` = 1 GiB + 64M of slack, deliberately above `qits.artifacts.oci.max-layer-size`: were
they equal the wire 413 would always win, and a client would get an empty-bodied 413 with the
connection reset instead of the spec's error envelope. It must also never drop below 64M or the
`ci-videos` cap breaks silently. Raising it raises the ceiling for every route in this process; the
tradeoff, the mechanism, and what an operator can actually do about it are in
`docs/issues/2026-07-19_artifacts-global-max-body-size-widens-public-ingest-dos.md`.

`qits.repositories.git.max-pack-size` is separate on purpose: a pack goes through a `BodyHandler`
into memory, so it must not inherit a ceiling sized for something that streams to disk.

`quarkus.rest.path=/artifacts/api` and `quarkus.http.non-application-root-path=/artifacts/q` are
**not** shipped from the jar's defaults: they are the deployable's own decision and live in
`service/src/main/resources/application.properties`. The suite inherits that file rather than
carrying a copy, so the absolute paths the tests assert are the ones the process serves.

The intake url's **path** is not ours either — `/ci/api/events/post-receive` is qits-ci's segment,
and only the host part is a deployment decision.

## What is deliberately *not* here

- **The repositories/projects context.** Cloning, branches, commits, submodules, the alias table
  itself. This repo serves bytes out of bare origins someone else creates.
- **CI.** The post-receive event is delivered *to* ci over HTTP; pipelines, runners and the intake
  live in [qits-ci](https://github.com/QuicklyIterateTheSoftware/qits-ci).
- **`QitsGitServlet` / `QitsRepositoryResolver`.** The pre-Vert.x servlet implementation, deleted in
  the monorepo long before the split. Their history is in this repo; their files are not.
- **Building images.** The registry stores and serves them; nothing here produces one. qits-ci step
  containers get no docker socket by design, so `docker build` inside a step fails today and keeps
  failing — consuming this registry works immediately, producing from within a step needs an
  unprivileged builder story of its own. Until then a producer is a host with a docker daemon and
  the push token.
- **Garbage collection.** The registry is append-only; untagged manifests and orphaned blobs
  accumulate. Acceptable at private-deployment scale, and the `DELETE` endpoints stay unimplemented
  so nothing depends on deletion semantics before they exist.
- **maven/npm repository types.** The same seam, different protocols.
- **A deployable.** See "Layout" above.
