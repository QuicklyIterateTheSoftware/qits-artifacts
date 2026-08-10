# qits-artifacts

qits' **hosted byte plane**, env-scoped: the hosted OCI registry the platform pushes its images to,
the hosted npm registry and maven repository its own libraries publish to, the daemon binaries every
CI step downloads and executes, the documentation bundles qits-docs serves back, the CI media the
golden-diff loop produces — and the pin-based garbage collection over all of them.

    mvn verify        # a clone of this repo alone builds and tests green — no monorepo, no docker

## What is NOT here, and where it went

This repository was `qits-platform-artifacts` and held three things it no longer does
(`byte-plane-split-plan.md` in the home repo, phase 4):

| Left | Went to | Why |
|---|---|---|
| the pull-through caches — `npm-proxy`, `maven-proxy`, `oci-mirror`, their upstreams and the access-tracked eviction GC | **qits-platform-mirror** | a cache of somebody else's registry is shared across every environment, and holding it was the *only* reason this service was platform-scoped. Without it, this one is an env service again. |
| the git smart-HTTP host — the `git-storage` module, `eu.wohlben.qits.githost*`, the post-receive fan-out | **qits-githost** | a repository is not an artifact. It only ever shared the storage layout, and every consumer of it (qits-ci, qits-deployments, qits-workspaces, the daemons) is an env service already. |
| the content-addressed blob store and the three protocol implementations | the libraries **qits-blobstore** and **qits-registries-{common,npm,maven,oci}** | the mirror needs the same store and the same wire code; a library is what stops that being a fork. |

So the two-endpoint topology a client sees: `@qits`-scoped npm packages, qits images and qits jars
come from **this** service at the env's own address; everything third-party comes from the mirror.
Splitting that is client configuration — npm scoped registries, dockerd `registry-mirrors`, the
maven repositories list — because the route prefixes are literals in the shared jars and both
services answer the same paths.

**The schema did not move.** The Flyway lineage under `artifacts/src/main/resources/db/artifacts/`
is carried through the split byte for byte: the libraries ship no migrations by design, each
consuming service owns its own, and this one's is the original chain. That leaves orphaned tables
behind — the git host's `git_pack`/`git_pack_file`/`git_repository_protection` (V4/V5) and the proxy
tables — which is a cutover data question rather than a reason to rewrite history. The one visible
consequence: V7 still **prefills three `oci-mirror` repository rows**, and rows of a type this
service registers no profile for are omitted from the explorer listing and from the GC plan rather
than reported with numbers nothing here can compute.

## Layout

| Module | What |
|---|---|
| `artifacts/` | What did not move out: the docs and daemon registries (entities, persistence, services), the store explorer, the live blob census, the repository seeder, the two repository-type **profile beans** this service contributes (`DAEMON_BINARIES`, `DOCS`) — and the Flyway lineage. A library jar: no web, no JAX-RS. |
| `gc/` | `eu.wohlben.qits.artifacts.gc` (+ `.dto`) — garbage collection: the pin-based own-artifacts engine, the per-type adapters, the planner, the reconciliation and the sweep, plus the two pin ports (`CdDeploymentPins`, `CiDaemonPins`) and their HTTP adapters. A *process* modelled from within this service, not artifacts domain. Depends on `artifacts` and, one way, never back. |
| `service/` | `eu.wohlben.qits.artifacts.api` (the JAX-RS boundary), `eu.wohlben.qits.docs` and `eu.wohlben.qits.daemon` (two raw Vert.x wires), and the SPA. The npm, maven and OCI wires are **not** written here any more — they arrive as beans on the qits-registries jars and register their own routes. |
| `service/src/main/webui/` | The SPA submodule — an Angular app, built into the app by Quinoa and served at `/artifacts`. Not Java, and not a Maven module. |

`artifacts/` and `gc/` are library jars; **`service/` is the application** — it carries
`<packaging>quarkus</packaging>` and produces a process, as a JVM fast-jar or as a native binary:

    ./mvnw verify
    java -jar service/target/quarkus-app/quarkus-run.jar   # :8080 — SPA /artifacts/, blobs /artifacts/api/**,
                                                           #         npm /artifacts/npm/**, maven /artifacts/maven/**,
                                                           #         docs /artifacts/docs/**, daemons /artifacts/daemons/**,
                                                           #         images /v2/**

    ./mvnw verify -Dnative
    ./service/target/qits-artifacts                        # same routes, ~35ms to listening

**Native is the shipping form.** `.sdkmanrc` names a GraalVM (`25.0.2-graalce`) so `sdk env` alone
is enough toolchain: the build wants a `native-image` on `GRAALVM_HOME`, `JAVA_HOME` or `PATH`, and
finding none it does not fail — it falls back to pulling a 1.8 GB Mandrel image and compiling under
docker. That fallback still works and is what a CI without a GraalVM gets; it is just not the
intended path, and it is worth recognising by name when a build that normally takes a minute starts
downloading a container image.

`quarkus.native.additional-build-args` is gone with the git host: every flag it carried was a JGit
static native-image refuses to freeze into the image heap, and JGit left with the host.

`-Dnative` also flips `skipITs`, so it runs `PackagedProcessIT` against the binary it just built —
openapi, swagger-ui, a blob round trip, an image push/pull, an npm publish/install, a maven
deploy/resolve and the browse endpoints. It is the only place the route stacks are proved to
coexist in one process, and the only thing that exercises zero-copy `sendFile` serving in a binary.

`service/src/main/webui` is a submodule, so a fresh clone wants `git submodule update --init` before
`./mvnw package`; without it Quinoa finds no `package.json`, disables itself with a warning, and the
app ships with no client while the build stays green.

The segment is spelled a THIRD time inside the client — the Angular `baseHref` in its `angular.json`
is `/artifacts/` — because the browser resolves asset urls against the document, not against
anything the server knows. Move `quarkus.quinoa.ui-root-path` and move that.

Everything is served under the `/artifacts` gateway segment, because `qits-gateway` routes
verbatim by prefix and rewrites nothing — there is no unprefixed form, on `qits-net` either.

Note the route stacks resolve differently: `/artifacts/api/**` is JAX-RS and moves with
`quarkus.rest.path`; `/artifacts/npm/**`, `/artifacts/maven/**`, `/artifacts/docs/**`,
`/artifacts/daemons/**` and `/v2/**` are registered straight onto the Vert.x router with the segment
as a literal, and do not.

**Which types this deployment registers is one line of configuration.** Repository types are
contributed as `RepositoryTypeProfile` beans, and each qits-registries module ships *both* halves of
its format — hosted and cache — so the cache profiles arrive on the classpath whether this service
wants them or not. `quarkus.arc.exclude-types` in `service/src/main/resources/application.properties`
vetoes the three, which is what makes "the caches are the mirror's" true rather than merely
intended: `RepositoryTypeProfiles` then indexes exactly seven keys, `ensure` answers a request for
any other with a 400 naming what IS registered, and the GC plan reports those same seven.

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

Writes require a machine token from qits-platform-idp — a bearer with `aud=qits-platform-artifacts`, checked by
`AdminWriteGuard`. The check sits behind the platform-wide rollout gate `qits.auth.machine.required`,
which is off by default: off, the write surface is open exactly as it was before qits-platform-idp existed.
Reads are never guarded — a blob must be usable directly as an `<img>`/`<video>` src.

## The git host

It is not here. The smart-HTTP host, the DFS storage over blobs and the post-receive fan-out are
**qits-githost**'s, an env service of its own, where the fan-out is durable domain events rather
than an HTTP call. Its README carries what this section used to.

## The OCI registry

> **Both halves of this format ship in `qits-registries-oci`, and this service wires only the hosted one.** The pull-through cache half — its repository type, its upstream configuration and its eviction GC — is qits-platform-mirror's, and the paragraphs below that describe it describe code this deployment does not register.

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

The corollary is that `/artifacts/v2` must serve **nothing**, and since the SPA took the whole
segment that now takes saying so: `/v2` is in `quarkus.quinoa.ignored-path-prefixes` for that alone.
A deployment that prefixes the registry is misconfigured, and a 404 is how a registry client is told
"not a registry here" — answered with the SPA it gets `200 text/html` and no
`Docker-Distribution-Api-Version` header, and reports something that names neither cause nor fix.

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
curl -X PUT http://<host>/artifacts/api/repositories/<name> \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $QITS_ARTIFACTS_MACHINE_TOKEN" \
  -d '{"type":"oci-images"}'
```

**`qits` needs none of that** — the startup seed ensures that row alongside the two CI types, so a
fresh deployment accepts the platform's own convention (`qits/<application>:<sha>`, what a pipeline's
publish step pushes) with zero manual steps. The curl above is for **additional** repositories.

So `qits/alpine:latest` works out of the box, and `qits/build-images/ci-base:latest` is repository
`qits`, image `build-images/ci-base`. A push to an unknown first segment is `404 NAME_UNKNOWN` with a
message naming this command; a single-segment reference (`docker push <host>/alpine`) is `400
NAME_INVALID`, because it has no repository/image split at all.

### The pull-through mirror

A second kind of namespace answers on the same `/v2` routes: a **mirror**, which fronts an upstream
public registry. Registered upstreams are rows, not config, and each carries the local segment it is
reachable under:

| domain | namespace | pulled as |
|---|---|---|
| `quay.io` | `quay` | `docker pull <host>/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25` |
| `registry.access.redhat.com` | `redhat` | `docker pull <host>/redhat/ubi9/ubi-minimal:9.6` |
| `docker.io` | `hub` | `docker pull <host>/hub/alpine:latest` |

All three are prefilled by the migration and re-ensured at every boot, so a fresh deployment has
them with no manual step. They are ordinary `artifact_repository` rows of type `oci-mirror`, and what
is cached under one is ordinary `oci_manifest`/`oci_tag` rows — so the hit path is the existing code,
unchanged, and the explorer browses a mirror namespace exactly as it browses `qits`.

Four rules are worth knowing before pulling through one:

- **A push is `405`, by type.** A mirror never accepts content from a client. Cached upstream content
  and pushed content must not share a namespace, and because it is the *type* refusing, no
  deployment can configure its way past it and no repository can drift from one meaning to the
  other. Same rule the npm proxy carries.
- **A single-component image under `hub` means `library/`.** `hub/alpine` is `hub/library/alpine`,
  the docker daemon's own expansion, so both spellings share one cache entry.
- **A first segment that names no repository at all remaps into `hub`**, on `GET`/`HEAD` only, when
  a Docker Hub upstream is registered. That exists so the optional Docker Desktop
  `"registry-mirrors": ["http://localhost:8081"]` setting works — a daemon-configured mirror client
  asks for bare Hub names. An existing repository always wins its segment, so `/v2/qits/…` never
  reaches the remap; the consequence is that a Hub organisation sharing a name with a local
  repository is shadowed, which is the correct precedence here.
- **A miss fetches.** That is the cache: a `GET` that resolves into a mirror namespace and finds
  nothing cached fetches from the upstream the row names, verifies the digest while the bytes
  stream, promotes and serves. See below for what it costs and what it does when the upstream is
  not there.

#### What a miss does

| asked for | not cached | cached |
|---|---|---|
| manifest by **digest** | one upstream `GET`, digest verified, kept **forever** | served; never revalidated |
| **blob** | one upstream `GET`, digest verified **while streaming**, kept forever | served |
| manifest by **tag**, within `tag-ttl` | — | served, **zero** upstream traffic |
| manifest by **tag**, expired | one `GET` | one `HEAD`; unchanged digest is free, a moved one costs one `GET` |

Digest-addressed content is immutable, so it is cached forever and revalidated never. A tag is the
one mirrored thing that moves — `jdk-25` and `9.6` change under toolchain and security updates — so
it carries a TTL and is revalidated by `HEAD`, which returns `Docker-Content-Digest` and which
Docker Hub does not count against its anonymous pull limit. That is what keeps builds current with
**zero curation** and at no measurable cost.

**Children are fetched lazily.** A pulled multi-arch index binds the moment it arrives, with no
child present: pull order is the reverse of push order, so the push path's "everything it references
must already exist" rule is deliberately not applied to a mirror bind. Each child arrives as its own
miss when a client asks for it by digest, so an architecture nobody pulls is never paid for — and
since a multi-arch pull counts once per architecture *fetched*, lazy is the rate-limit-correct order
as well as the cheap one.

**Offline, the cache is strictly additive.** Manifests-by-digest and blobs serve forever with no
upstream contact. An expired tag whose upstream is unreachable **serves stale** — so once a base
image has been pulled once, every later build succeeds with the internet down. Only a never-cached
reference can fail, and the answer says which of the two things went wrong:

- upstream unreachable, nothing cached → **`502`**, naming the upstream. Not a `404`: nothing here
  knows whether the image exists, and "no such manifest" would send a puller to debug the wrong
  registry.
- upstream answered and has no such reference → **`404`**, naming the registry that was asked.
- the upstream row was **deleted** while its cache stayed → **`404`** saying no upstream is
  registered, so nothing can be fetched into the namespace. What is already cached keeps serving.

Never a `500`: a network miss is not this service failing.

Four behaviour keys, and no key naming an upstream — that is what the table is for:

| key | default | what it bounds |
|---|---|---|
| `qits.artifacts.oci.mirror.tag-ttl` | `PT1H` | how long a cached tag is served without asking |
| `qits.artifacts.oci.mirror.manifest-timeout` | `PT30S` | one manifest `GET`/`HEAD`, including the token hop |
| `qits.artifacts.oci.mirror.blob-timeout` | `PT10M` | one blob transfer |
| `qits.artifacts.oci.mirror.endpoint-override` | *(blank)* | dial every upstream here instead of at its domain — the test seam; blank in every deployment |

Every upstream wait carries one of those timeouts and there are no retries. This is the platform's
first hard runtime dependency on the public internet **inside a request**, and after the `FROM`
rewrite it sits under every service build, so a hung upstream must never pin a worker thread. Layer
size is capped by the existing `qits.artifacts.oci.max-layer-size` (1G), which is the first knob to
check if an upstream layer ever exceeds it.

Upstreams that challenge for a token get the anonymous bearer dance — a `401` carrying a
`WWW-Authenticate: Bearer` realm is answered with a plain token `GET` and the request is retried,
once. Docker Hub demands this even for public images; quay.io and Red Hat mostly do not, so the
client sends every request bare first and only pays the hop when challenged. Tokens are cached in
memory per scope, which is per repository — a twelve-layer pull costs **one** token request.

Managing upstreams is four routes under the JSON API, writes token-guarded like every other write
here:

```
GET    /artifacts/api/mirror-upstreams            every upstream, by namespace
GET    /artifacts/api/mirror-upstreams/{domain}    one of them
PUT    /artifacts/api/mirror-upstreams/{domain}    {"slug":"quay"} — idempotent; creates the namespace too
DELETE /artifacts/api/mirror-upstreams/{domain}    stop mirroring; THE CACHE STAYS
```

`PUT` writes both rows in one transaction — the upstream and the `oci-mirror` repository its
namespace resolves to — because either alone is useless: a repository row with no upstream is a
namespace nothing can be fetched into, and an upstream with no repository row is a namespace nothing
resolves to. Re-pointing a registered upstream at another namespace is `400`: content is cached under
the old name, so moving it is a delete and a create, where an operator can see what happens to the
cache.

`DELETE` removes **only** the upstream row. The namespace and every cached byte under it stay,
which is the append-only posture this store has everywhere — what ends is the future, not the past.

Credentials are out of scope until an upstream needs one, and the reason is worth stating because
the intuition is natural and wrong: a client's `docker login` does **not** travel through a
pull-through hop. The daemon authenticates to the registry it dials — this one — and the mirror
dials upstream as itself, so a private upstream needs a *server-side* credential, which becomes an
additive column pair on the upstream row the day it is needed. Every upstream above is anonymous.

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

### No login, in either direction

Reads are anonymous, always — image names are meant to be shared, which is also why `/v2/_catalog`
stays unimplemented and the posture stays private-network rather than capability-URL. **Writes are
anonymous too**: the registry carries no credential of its own, and the machine-token gate guards
the blob-store JSON admin API and nothing else (`RegistryOpenPushTest` pins that turning it on does
not drag `/v2` back behind a docker login).

That is a decision with two halves, one per direction:

- **Inside the deployment**, producers on qits-net are trusted — the platform posture everywhere —
  and a tokenless registry is what lets an automated publisher (the CI/CD image-build story) push
  with no credential store. Every client works: `docker push`, `skopeo copy`, `podman push`, with
  no login step at all.
- **From outside**, write protection is qits-gateway's: `/v2` is on its token-free allowlist for
  **read methods only**, so an internet `docker push` is challenged for a session no registry
  client can hold and dies at the front door. Re-allowlisting `/v2` writes there without restoring
  a guard here would open push to the internet — the gateway's `PublicPathsTest` and the comment in
  `RegistryRoutes.init` both hold that line.

The registry once guarded writes with a static token as an HTTP Basic password. That
bought a measured, awkward tradeoff — docker could push after a `docker login`, skopeo/podman
could not (their shared `containers/image` reads the non-challenging `/v2/` ping as "no
credentials needed") — and the guard's whole benefit dissolved once the platform settled on
trusted-network producers and gateway-terminated external auth, so it is gone rather than dormant.

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

## The npm registry

> **Both halves of this format ship in `qits-registries-npm`, and this service wires only the hosted one.** The pull-through cache half — its repository type, its upstream configuration and its eviction GC — is qits-platform-mirror's, and the paragraphs below that describe it describe code this deployment does not register.

`NpmRoutes` serves the npm registry API at `/artifacts/npm/<repository>/…`. Two repository types
share those routes, and which one a repository is decides what it will do:

| Type | Wire name | Seeded row | What it is |
|---|---|---|---|
| `NPM_PACKAGES` | `npm-packages` | `npm` | hosted — accepts publishes |
| `NPM_PROXY` | `npm-proxy` | `npmjs` | a pull-through cache of an upstream registry; a `PUT` is `405` |

Unlike `/v2` the mount point is an ordinary choice: npm accepts a registry URL of any depth, so
there is no second root-level segment and no gateway change — this lives inside the `/artifacts`
prefix like everything else. The segment is still a literal in the code, and `NpmRegistryTest` is
the only thing that would notice it drifting.

Cached upstream content and published content **never share a namespace**, which is the same rule
the OCI mirror settled, and it is enforced by the type rather than by configuration: a repository's
type is immutable once chosen, so a mirror cannot become a publish target by editing a setting.
Consumers need no merged "group" view because npm does the routing client-side — one `.npmrc`:

```ini
registry=http://qits-platform-artifacts:8080/artifacts/npm/npmjs/     # everything, through the cache
@qits:registry=http://qits-platform-artifacts:8080/artifacts/npm/npm/  # ours, from the hosted repo
```

Both rows are seeded at startup alongside `qits`, so a fresh deployment needs no manual step.
Additional npm repositories are created with the ordinary ensure endpoint, `{"type":"npm-packages"}`
or `{"type":"npm-proxy"}`.

### The wire

```
GET    /artifacts/npm/<repo>/<pkg>                    packument
GET    /artifacts/npm/<repo>/<pkg>/-/<file>.tgz       tarball
PUT    /artifacts/npm/<repo>/<pkg>                    publish
DELETE anywhere                                       405 — no unpublish, no GC
anything else under the base                          JSON 404; npm degrades gracefully
```

A **scoped name arrives percent-encoded** (`GET /@qits%2fangular`) and Vert.x' `normalizedPath()`
leaves the escape alone, so the route grammar matches the encoded form and the handler decodes.
Both spellings resolve, because the tarball URL this registry emits carries a real slash and a
client follows it verbatim. `NpmPathsTest` pins the grammar; `NpmRegistryTest` pins that the router
actually behaves that way.

The **packument is derived state** for a hosted repository: assembled per request from `npm_version`
+ `npm_dist_tag` rows and never stored, so it cannot become a second source of truth — the same
reasoning that keeps OCI tags in one table. `Accept: application/vnd.npm.install-v1+json`, the
abbreviated form both npm and pnpm send, is answered with the full document. That is spec-legal (the
abbreviated type is an optimization a registry may decline) and it is the honest first
implementation: trimming it is a bandwidth change, and trimming it wrong silently breaks an install
that needed a field we dropped.

**Publishing** decodes the base64 `_attachments` tarball, recomputes `shasum` (sha1) and `integrity`
(sha512 SRI) and rejects a mismatch with the client's claim — the npm restatement of "a blob that
does not hash to its name is not a blob". The bytes then go through `BlobStore` like everything
else, so a tarball's *storage* key is its sha256 while npm's two hashes are stored columns
re-emitted in packuments; the store stays sha256-only. **Versions are immutable**: re-publishing one
is `403`, and so is publishing over a version garbage collection removed — a version name is never
reused, even after its row is gone (see "Garbage collection"). Only `npm_dist_tag` mutates.

**`latest` only moves forward.** It is the one dist-tag with an ordering rule: a publish may not
point it at a version sorting below the one it names, by semver precedence — and a prerelease sorts
below its own release. The rule exists because a bare `npm publish` means `--tag latest`, so a main
build publishing `<release>-main.g<sha>` would take `latest` backwards permanently and every
consumer installing without a range would get a main build. Publish prereleases under their own tag
(`npm publish --tag main`); every tag other than `latest` moves anywhere. A first `latest` is always
allowed, an equal one is a no-op, and a version that does not parse as semver is refused rather than
passed through. The refusal is a `403` that takes the whole publish with it, exactly as the
immutability one does.

`dist.tarball` **must be absolute** — npm refuses a relative one, so the OCI registry's
path-form-`Location` trick does not transfer. It is built from `X-Forwarded-Host`/`X-Forwarded-Proto`
when present and from the request's own authority otherwise, and **no config key names it**: the
gateway emits the `X-Forwarded-*` set on every proxied request by default, and a qits-net client
dialling `qits-platform-artifacts:8080` has no forwarding hop, so the request always carries the right answer
while a configured value would be right for one caller and quietly wrong for the other.

### The proxy

A `npm-proxy` repository fronts `qits.artifacts.npm.proxy.upstream` (default
`https://registry.npmjs.org`), so npmjs is hit once per tarball rather than once per CI run. The two
documents get opposite treatment, which is the whole design:

- **Packuments mutate** — a new version appears upstream with nothing here changing — so they are
  cached with a TTL (`qits.artifacts.npm.proxy.packument-ttl`, default `PT5M`) and revalidated with
  `ETag`/`If-None-Match` on expiry, which costs a `304` rather than a document. Upstream's document
  is stored **verbatim** and every `dist.tarball` is rewritten at *serve* time, not at store time:
  the rewrite target depends on the request, and the original URLs are what the tarball miss path
  fetches from. When upstream is unreachable the stale copy is served anyway — CI keeps installing
  through an npmjs outage, which is half of why this exists.
- **Tarballs are immutable**, so a hit is `sendFile` and a miss streams from upstream through
  `BlobStore.stage()` (hashing while streaming, for free), promotes, and serves. A proxied version
  gets its `npm_version` row written lazily on that first pull, which is what keeps the tarball
  route one code path for both types.

`integrity` is re-emitted **unmodified**, and nothing here verifies it. That is the safety argument
rather than a gap: the client verifies the bytes end to end against a hash this service never
computed, so the proxy cannot silently corrupt a package even in principle — while a mid-flight
check would only add a way for a stale-but-correct upstream document to break an install.

Growth is unbounded, exactly like the OCI mirror's; `artifact-access-tracking.md` is the
prerequisite for cleanup and now has two more clients.

**The upstream client is a plain JDK `HttpClient`** — no extension, no reflection registration, no
new dependency — and it is the first outbound TLS in this process. The suite cannot exercise real
TLS by construction (clone-alone, no network: `StubNpmRegistry` is an in-process upstream), so
**a deployment gets one manual smoke against real npmjs**, once, on the native binary:

```
curl -s http://<host>/artifacts/npm/npmjs/left-pad | head -c 200
```

### No login here either

**None. Not a token, not a guard, nothing** — the OCI registry's threat model verbatim (see "No
login, in either direction" above). Producers and consumers are internal, dialling
`qits-platform-artifacts:8080` on qits-net, and from outside `/artifacts/npm/**` falls under qits-gateway's
usual session auth like any other non-allowlisted artifacts path. No `PublicPaths` entry, no method
split, nothing npm-specific; whether an npm client can operate *through* that auth from outside is
deliberately out of scope.

The one wrinkle is client-side and never reaches the wire: the npm CLI has historically refused
`npm publish` when no credential is configured for the target registry (`ENEEDAUTH` is a pre-flight
check). If current npm still enforces it, a pipeline's `.npmrc` carries one dummy `_authToken` line
that **this server neither reads nor knows about** — npm-client ceremony, not an auth scheme, and it
disappears the moment npm accepts an anonymous publish.

### Deliberately not implemented

`DELETE` (405), for the same reason as on `/v2`: the store is append-only and there is no garbage
collector, so unpublish has no meaning here yet. `/-/v1/search`, `/-/npm/v1/security/audits/*`,
`/-/whoami` and the login handshake are absent rather than stubbed — npm degrades gracefully on a
404 for every one of them, so what matters is that the 404 carries npm's `{"error": …}` shape
instead of Vert.x' HTML page. Dist-tag mutation APIs (`npm dist-tag add`) are absent too:
publish-if-absent is the versioning convention, so a tag only ever moves as part of a publish.

## The maven repository

> **Both halves of this format ship in `qits-registries-maven`, and this service wires only the hosted one.** The pull-through cache half — its repository type, its upstream configuration and its eviction GC — is qits-platform-mirror's, and the paragraphs below that describe it describe code this deployment does not register.

`MavenRoutes` serves a maven repository at `/artifacts/maven/<repository>/<path…>`, the npm shape
verbatim: npm lives at `/artifacts/npm/npm/<pkg>`, so the platform's own library deploys to
`/artifacts/maven/maven/eu/wohlben/qits/qits-eventstream/1.0.0/qits-eventstream-1.0.0.jar`. The
first segment is the `artifact_repository` row; `maven` (type `maven-packages`, hosted) and
`central` (type `maven-proxy`, a pull-through cache of Maven Central) are both seeded at startup,
alongside `npm` and `npmjs`. Maven accepts a repository URL of any depth, so — like npm and unlike
`/v2` — there is no root-level segment, no gateway change, and no client-side routing story beyond
declaring the repository.

```
GET|HEAD /artifacts/maven/<repo>/<path…>          an artifact, a pom, metadata, a checksum
PUT      /artifacts/maven/<repo>/<path…>          deploy — mvn deploy / gradle publish compatible
DELETE   anywhere under the base                  405 — no undeploy; the append-only stance, verbatim
anything else under the base                      404 with a short text body, never the SPA's HTML
```

The server is a **dumb path store**. `mvn deploy` PUTs the jar, the pom, then each file's checksums,
then its merged `maven-metadata.xml` with checksums — one request per file, no session, no lock —
and resolution is GETs of the same paths. Every byte goes through `BlobStore` like everything else,
so jars dedupe globally with image layers and tarballs. The whole of the server's intelligence is
derivation, never rewriting:

- **`maven-metadata.xml` is derived state**, the packument precedent at two levels. At artifact
  level, `<versions>` is the distinct version directories present, `<latest>` the highest by maven
  ordering and `<release>` the highest non-`SNAPSHOT` one. At version level, a snapshot directory's
  timestamped filenames parse back into the `<snapshotVersions>` a resolver maps
  `1.0.1-SNAPSHOT` through. **The client's own metadata PUT is accepted and discarded** — refusing
  would break `mvn deploy` on its final request; storing it would serve a merge that goes stale on
  the next deploy, the second source of truth the derived document exists to prevent. A snapshot
  directory holding only literal `-SNAPSHOT` files (a non-unique deploy) answers **404**
  deliberately, because the resolver's defined fallback for a missing document is exactly that
  literal filename.
- **Checksums are derived at GET and verified at PUT, never stored.** Every stored file serves
  `.md5`, `.sha1`, `.sha256` and `.sha512` siblings computed from the blob bytes; a checksum a
  client PUTs is recomputed against the referenced blob and refused `400` on mismatch — the npm
  `requireClaimMatches` restated. A match stores nothing, because a derivable value stored is a
  value that can only ever disagree.
- **Releases are immutable, and the path space has three classes.** Release paths and timestamped
  snapshot files (unique by construction — one deploy, one filename) refuse a redeploy with
  different bytes at `403`; an identical redeploy is a `201` no-op, because deploy retries are
  normal. A **literal `-SNAPSHOT` filename** is the one mutable path — the coordinate is a moving
  target by definition — and serves with `no-cache` rather than `immutable`.

**No login here either** — the OCI/npm threat model word for word, with one less wrinkle than npm:
maven sends no credential unless challenged, this server never challenges, so a pipeline's
`distributionManagement` needs no matching `<server>` entry. From outside, `/artifacts/maven/**`
falls under qits-gateway's ordinary session auth like any other non-allowlisted artifacts path.

The deploy `PUT` streams rather than buffers, capped by `qits.artifacts.maven.max-artifact-size`
(default 128M) — the one size answer for both directions a jar can travel, the npm
`max-publish-size` precedent.

### The pull-through cache

A `maven-proxy` repository fronts `qits.artifacts.maven.proxy.upstream` (default
`https://repo1.maven.org/maven2`), so Central is hit once per file rather than once per build. It
runs on the routes above and **inverts two of the three derivations**, because nothing it holds is
ours:

- **`maven-metadata.xml` is cached, not derived.** It is the one maven document that mutates — it
  lists the versions upstream has — so it is cached with a TTL
  (`qits.artifacts.maven.proxy.metadata-ttl`, default `PT1H`) and revalidated on expiry with
  `If-None-Match`, or `If-Modified-Since` for an upstream too old to answer an etag. Deriving it
  from the cached rows the way the hosted side does would answer with the versions this cache
  happens to hold, and a resolver told a subset stops looking. When upstream is unreachable the
  stale copy is served anyway — a build keeps resolving through a Central outage, which is half of
  why this exists.
- **A file's checksums are upstream's own, cached.** `.sha1`/`.md5`/`.sha256`/`.sha512` beside an
  artifact are immutable paths like any other and are pulled through unchanged, so the maven client
  verifies the jar end to end against a hash this service never computed. Deriving them here would
  be worse than useless: a hash computed from bytes this service downloaded agrees with itself
  whatever arrived, which removes the client's check while looking like it kept it.
- **The metadata's own checksum siblings ARE derived**, and that is the exception the rule needs.
  Upstream's `maven-metadata.xml.sha1` is a hash of whatever its document says *now*, which is a
  different document from the one inside our TTL the moment a version is released — so proxying it
  would hand every client a checksum that does not match the bytes beside it. Deriving it from the
  cached document makes the two consistent by construction.
- **Everything else is an immutable path**: a hit is `sendFile`, a miss streams from upstream
  through `BlobStore.stage()` (hashing while streaming, for free), promotes, and writes an ordinary
  `maven_artifact` row. That row is what keeps the serve path **one** code path for both maven
  types, and what the census, the explorer and the collector all read without a line of new code.

**A `PUT` is `405`, refused by type** — the rule `npm-proxy` and `oci-mirror` settled: cached
upstream content and deployed content must never share a namespace, and no repository can drift from
one meaning to the other because a type is immutable.

SNAPSHOT paths need no special handling: Central hosts no snapshots, so they miss upstream and 404
honestly.

**The upstream client is a plain JDK `HttpClient`**, `NpmUpstream`'s shape verbatim — no extension,
no reflection registration, no new dependency. Two bounded timeouts (30 s for a document, 10 minutes
for an artifact) and **no retries**: this sits inside a request on a worker thread, so a hung
upstream must cost one bounded wait. The suite cannot exercise real TLS by construction
(clone-alone, no network: `StubMavenRepository` is an in-process upstream), so a deployment gets one
manual smoke against real Central, once, on the native binary:

```
curl -sI http://<host>/artifacts/maven/central/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar
```

## The daemon binaries

`DaemonRoutes` serves the platform's own executables at `/artifacts/daemons/<name>/<version>`.
`daemons` (type `daemon-binaries`) is seeded at startup alongside `npm`, `npmjs` and `maven`.

```
PUT      /artifacts/daemons/<name>/<version>      publish — streams; 201 with the computed digest
GET|HEAD /artifacts/daemons/<name>/<version>      the binary, version-addressed
DELETE   anywhere under the base                  405 — no delete; the append-only stance, verbatim
anything else under the base                      404 with a short text body, never the SPA's HTML
```

Unlike the other three surfaces there is **no repository segment**: this one serves the platform's
own daemons and nothing else, so the first segment after the base is the daemon's name. A second
namespace would be a design decision rather than a path someone can mint by typing one.

**The row write IS the publish.** One request stages the bytes, promotes them into `BlobStore` and
inserts the `daemon_binary` row, in one transaction — so there is no way to store a daemon's bytes
without an identity. That is the hole this type exists to close: the bootstrap used to upload the
ci-daemon through the OCI blob-upload session, which promotes bytes and writes no row by
construction, and the consequence was measurable — **every row-less byte in the store was a
ci-daemon build**, so `orphanBytes` reported a live executable that every build downloads as
garbage-shaped. `daemon_binary.blob_id` is now one of the census's live sets.

**Versions are immutable**: re-publishing an existing `(name, version)` is `409`, even for identical
bytes. That differs from maven's idempotent re-deploy on purpose — a maven deploy sends one file per
request and retries are routine, while a daemon publish is one request from one release pipeline, so
a second one means the version was reused or the release ran twice. The response carries the
computed digest, which is what a release pipeline pastes into a deployment.

**Two download spellings, deliberately.** The digest-addressed blob route on `/v2` is *untouched*:
the launcher, the `qits.ci.daemon-binary-url-template` and every existing `QITS_CI_DAEMON_VERSION`
pin keep working exactly as they are, and the digest stays what the pin holds. The version-addressed
`GET` here is the readable second spelling, safe to add only because a version pointer never moves;
it answers with `Docker-Content-Digest` so a consumer can check it against its pin without a second
request.

**Nothing here is authenticated**, in either direction — the same posture `/v2`, `/artifacts/npm`
and `/artifacts/maven` hold, and deliberately not a weaker one. On qits-net producers are trusted,
which is what lets a release pipeline publish with no credential store. Integrity does not come from
write auth: a version is immutable, so an open publish can add a version and can never change one,
and a consumer pins the digest this route echoes, so what a launcher runs is decided by content
addressing. Machine auth arrives wholesale with qits-platform-idp, for every publish path at once — gating
this one alone would report a posture the other three do not have. `DaemonOpenPublishTest` pins it
with the machine-token gate turned on, beside the npm and registry twins.

Reads are anonymous for the extra reason that the cold-start path is a bootstrap script with no
credential: a fresh platform has to be able to fetch a daemon before it has any CI to mint one with.

The publish `PUT` streams rather than buffers, capped by `qits.artifacts.daemon.max-binary-size`
(default 256M). It uses **no `BodyHandler`**, and that is the point: vertx-web defaults one to
10 MiB and the binary is 43 MB, so the obvious implementation would have 413'd every real publish
while passing every test small enough to be quick.

## The documentation bundles

`DocsRoutes` serves published documentation sites at `/artifacts/docs/<repository>/<site>/-/<version>`.
`docs` (type `docs`) is seeded at startup alongside `npm`, `npmjs`, `maven` and `daemons`.

```
PUT  /artifacts/docs/<repo>/<site>/-/<version>          publish one bundle — a streamed .tar.gz
GET  /artifacts/docs/<repo>/<site>/-/<version>/<path>   one file of it, zero-copy
GET  /artifacts/docs/<repo>/<site>/-/<version>          that version's metadata, as JSON
GET  /artifacts/docs/<repo>/<site>                      its versions, newest first
DELETE anywhere under the base                          405 — no delete; the append-only stance
anything else under the base                            404 with a short text body, never HTML
```

A `<site>` is whatever namespacing the publishing project already uses — `@qits/ui-components` where
there is an npm package, `someproject/somelib` where there is not, up to four segments. **The `/-/`
between the name and the version is npm's separator, reused**: `/artifacts/npm/<repo>/<pkg>/-/<file>`
has had it all along, and a multi-segment name needs a marker or `<name>/<version>/<path>` could be
split more than one way. What closes the ambiguity is the segment shape rather than route ordering —
a bare `-` cannot begin a name segment, which `DocsPathsTest` pins.

**A whole bundle is one request, and a version is the unit of everything.** The `PUT` streams a
`.tar.gz` to a temp file, walks it entry by entry into `BlobStore`, and writes one `docs_site` row
plus one `docs_file` row per path — **all in one transaction**. So a version is either wholly
published or not published at all, which matters more here than for any single-file type: a
half-written site is one that lists itself and then 404s its own stylesheet, which reads as a broken
deployment rather than a failed publish. Eviction is the same shape from the other end — the
`docs_file` foreign key cascades, so a GC strategy plans one candidate per *version* and there is no
coordinate for a per-file one.

**Exploded into blobs, not stored as a tarball**, and the dedupe is the reason. Every file is an
ordinary content-addressed blob, so serving one is a row read and a `sendFile` with no unpacking and
no extraction cache — and, far more valuably, unchanged fonts and chunks are byte-identical between
releases and are stored once. Measured on the real Storybook bundle: 53 files, 9.93 MB, and a second
version differing only in `index.html` added **one** blob.

**Versions are immutable**: re-publishing is `409` even for identical bytes, `daemon_binary`'s stance
and its reasoning. That is what lets qits-platform-docs be stateless — `latest` is a query over these
rows rather than a pointer something has to keep correct.

**A hostile archive has nowhere to land, structurally.** An entry's bytes go into `BlobStore` under
their own SHA-256 at a path it computes; the entry's *name* survives only as a database string, so
there is no `resolve(entry.getName())` anywhere in this service. Traversing and absolute names are
refused anyway, as defence in depth. What is left is size, and a compressed archive makes that cheap
to send — so the cap (`qits.artifacts.docs.max-bundle-size`, default 256M) is checked against the
**uncompressed** running total and the file count, not against `Content-Length`, which measures the
wrong number entirely.

**Nothing here is authenticated**, the posture `/v2`, `/artifacts/npm`, `/artifacts/maven` and
`/artifacts/daemons` all hold, and for the same reason: a version is immutable, so an open publish
can add one and can never change one.

`media_type` is resolved from the file **extension** at publish and stored, never sniffed —
`MediaTypeSniffer` has no `woff2` entry and would reject exactly the files a static site is made of.

## The explorer API

The `GET`s under `/artifacts/api` answer the one question this service could not: **what is in
here, and what does it cost.**

None of the protocol surfaces can answer it, and that is by design in each case. `/v2/_catalog` is
refused here; `tags/list` returns `200` with an empty array for an image that does not exist, so
discovery by probing is impossible. npm's `/-/all` and `/-/v1/search` are absent. And
`GET /artifacts/api/repositories/{repo}/blobs` answers `{"records":[]}` for every protocol
repository, because those paths never write `artifact_record` — it looks like an empty registry and
is not. So these routes are new machinery rather than a view over an existing one.

All are reads and all are **unguarded**, like their neighbours: `ArtifactsTokenFilter`
covers write methods only, and `/artifacts/api` is already on qits-gateway's public paths. Every
operation carries `@Operation(hidden = true)` as everything here does, so `docs/openapi.yml` stays
`paths: {}` and the contract is written out below instead.

```
GET /artifacts/api/repositories                                    every repository
GET /artifacts/api/repositories/{repo}/images                      an OCI repository, hosted or mirror
GET /artifacts/api/repositories/{repo}/images/{image}/tags         one image's tags
GET /artifacts/api/repositories/{repo}/images/{image}/manifests    every manifest, tagged or not
GET /artifacts/api/repositories/{repo}/packages                    an npm repository, either type
GET /artifacts/api/repositories/{repo}/packages/{package}/versions one package's versions
GET /artifacts/api/store/summary                                   the whole store, ten ways
GET /artifacts/api/mirror-upstreams                                the registries this one mirrors
```

| Route | Body |
|---|---|
| `repositories` | `{"repositories":[{"name","type","createdAt","itemCount","sizeBytes"}]}` |
| `images` | `{"images":[{"name","tagCount","manifestCount","sizeBytes"}]}` |
| `tags` | `{"tags":[{"tag","digest","sizeBytes","createdAt","accessedAt"}]}` |
| `manifests` | `{"manifests":[{"digest","mediaType","sizeBytes","createdAt","accessedAt","tags"}]}` |
| `packages` | `{"packages":[{"name","versionCount","latest"}]}` |
| `versions` | `{"versions":[{"version","tarballSizeBytes","publishedAt","accessedAt","distTags"}]}` |
| `store/summary` | `{"ociPerImageSumBytes","ociUnionBytes","ociMirrorBytes","orphanBytes","npmPublishedBytes","npmProxyTarballBytes","npmProxyPackumentBytes","mavenPublishedBytes","mavenProxyBytes","daemonBinaryBytes","diskTotalBytes"}` |
| `mirror-upstreams` | `{"upstreams":[{"domain","slug","createdAt","cachedImages"}]}` |

Details a client trips over if it does not know them:

- CI `blobs`, OCI `tags`, and OCI `manifests` accept inclusive `accessed-after`,
  `accessed-before`, `created-after`, `created-before`, `min-size`, and `max-size` filters.
  `never-accessed=true` selects null access timestamps; `never-accessed=false` selects rows that
  have been read. A null timestamp does not match an access range. Invalid or inverted bounds are
  400. CI records expose the nullable `accessedAt` beside their existing fields.
- Client content reads are coalesced to at most one timestamp write per row per hour. A CI blob URL
  identifies content, so it touches every record in that repository naming that digest. An OCI tag
  manifest pull touches its tag and resolved manifest; a digest pull touches the manifest only.
  Layer blobs remain untracked because a globally deduplicated blob cannot be attributed to one
  manifest or tag from its request.
- The three protocol tables track the same way (V11), on the same hour, so the settled GC can reason
  about every type it is configured for. An npm tarball `GET`/`HEAD` touches its `npm_version` row —
  one rule for both npm types, since a proxied version is an ordinary row there, and the proxy
  packument's `fetchedAt` stays a different fact (document revalidation, not byte demand). A maven
  `GET`/`HEAD` of a stored file touches its `maven_artifact` row; derived `maven-metadata.xml` and
  derived checksums touch nothing, because they are not that row's bytes. A daemon `GET`/`HEAD`
  touches its `daemon_binary` row. **The digest-addressed daemon download on `/v2` is deliberately
  unattributed**, exactly as layers are: it resolves an OCI repository and a globally deduplicated
  digest, so the request names no daemon — what keeps a digest-fetched daemon alive is the live pin,
  never an access timestamp.

- **`itemCount` means something different per type**, on purpose: images for `oci-images`, packages
  for either npm type, deployed files for `maven-packages`, published versions for
  `daemon-binaries`, `artifact_record` rows for the two CI
  types. One number with a type-dependent meaning beats five that are always null.
- **404 is an unknown repository; 400 is a repository of the wrong type.** An npm repository has no
  images and never will, and reporting that as an empty list would read as an image registry that
  lost its images. Both OCI types answer the image routes: a mirror namespace holds the same rows,
  so it browses like any other, and `cachedImages` on an upstream is that same count.
- **`ociMirrorBytes` is counted apart from `ociUnionBytes`**, because the two answer different
  questions: what this platform published, and what it cached from three public registries and could
  re-fetch.
- **An unknown *image* is `200` with an empty list**, not a 404 — an image is not a row, so there is
  nothing to be absent. The same answer `tags/list` gives.
- **`{image}` and `{package}` may contain slashes**, and both spellings resolve: `build-images%2Fci-base`
  and `build-images/ci-base`, `@qits%2Fui-components` and `@qits/ui-components`. `qits/build-images/ci-base`
  is repository `qits`, image `build-images/ci-base`; every scoped npm name has a slash in it too.
- **`digest` is the wire form** `sha256:<hex>`, not the bare hex the database stores — it is what a
  person pastes into a pull by digest.
- **`createdAt` on a tag is `oci_tag.updated_at`**, when the tag last came to name that digest. A tag
  is the registry's one movable pointer and has no other timestamp; for a tag that never moves —
  which is every commit-sha tag this platform pushes — it is when the image was pushed.
- **`latest` is null for every proxied package**, and `distTags` is empty for every proxied version.
  A proxy caches tarballs and documents and stores no dist-tag rows.
- **A proxied package appears only once its tarball has been pulled.** The listing comes from
  `npm_version`, not from `npm_proxy_packument`, whose only index is its primary key — listing that
  table is a full scan of hundreds of CLOBs. The cost is that a cached document with no cached bytes
  is missing from the list, which is the honest thing for a store view to omit.
- **`tarballSizeBytes` may be null.** There is no size column on `npm_version`; the figure is the
  file's size on disk, and a row can outlive its bytes. Zero would read as an empty tarball.

### Sizes, which are the only new machinery

**A size on this store is a set union, never a sum.** Blobs are content-addressed and deduped
globally — across types and across repositories — so adding two overlapping figures overstates the
store. Measured three ways over the same content:

```
sum over all 155 manifest ROWS  (per-tag sizes, added up) : 10.63 GiB
sum over 10 IMAGES              (per-image unions, added) :  4.36 GiB
TRUE UNION across everything OCI                          :  4.04 GiB over 564 blobs
```

The inflation is almost entirely *within* an image: every rebuild shares its base layers with the
previous tag. So the **per-image union is the headline number** (1.08× the truth), a per-tag figure
is never shown as if it were additive (2.63×), and the true union and the orphan bytes are named on
the store summary beside it. An unlabelled byte count over a deduped store is a lie with a number
in it.

Where the numbers come from, given that **96.3% of the stored bytes have no database row** — OCI
layers and configs get none by design, and `oci_manifest.size_bytes` is the size of the manifest
*JSON*, three thousandths of the store:

- **`OciManifestFootprints`** parses the stored manifest documents and reads the `size` fields
  inside them. Those are the numbers a manifest's digest covers, so they cannot drift; parsing all
  155 of this store's manifests found zero mismatches and zero missing referenced blobs. An index
  recurses into its children. There is no `oci_blob(digest, size)` table, and that is the deliberate
  first cut — it is what survives a registry ten times this size, and it is not needed at this one.
- **`BlobDiskIndex`** walks the blob directory (1450 files, two levels) for what is actually there.
  It is the only thing that can see the orphans, and the only source of an npm tarball's size.

**Caching and invalidation**, which are two different problems here:

- A **footprint is cached forever and never invalidated**, and that is a property of the key rather
  than an oversight: a manifest is content-addressed, so the bytes behind `(repository, image,
  digest)` cannot change. A push adds a key; it can never make an existing one wrong.
- The **aggregates are not cached at all** — per-image, per-repository and store-wide unions are
  recomputed from the manifest rows on every request. That is a few thousand map merges over an
  index scan, and it is cheaper than being wrong after a push.
- The **disk index is invalidated by the write.** Every byte this service stores lands through
  `BlobStore.promote` — the registry's layers, npm's tarballs and publishes, the proxy's
  pull-throughs, the JSON API's uploads — so that one call is the complete set of events that can
  make a directory listing stale, and it is where `invalidate()` is called from. A 60-second age
  ceiling is a second belt for what a process cannot see: a volume restored under it, or a sibling
  writing the same directory. A test that wipes the directory says so explicitly.

`npmProxyPackumentBytes` is the one figure that comes from neither: it is
`sum(length(npm_proxy_packument.doc))`, summed in SQL rather than in the JVM because those documents
total ~650 MB — **85% of the H2 file**, and 3.8× the tarballs they index. A UI reporting only the
proxy's disk usage is off by nearly 4×. It is counted in characters; the documents are ASCII JSON.

The eleven figures reconcile in exactly one way, which is the panel's whole claim:

```
diskTotalBytes = ociUnionBytes + npmPublishedBytes + npmProxyTarballBytes
               + mavenPublishedBytes + mavenProxyBytes + daemonBinaryBytes + orphanBytes
```

`ociPerImageSumBytes` sits above `ociUnionBytes` by whatever images share, and
`npmProxyPackumentBytes` is outside the identity because those bytes are rows, not files.

**`orphanBytes` is not a rounding error, and it is the figure `daemon-binaries` exists to empty.**
This store holds 124 MiB in three ELF binaries — the ci-daemon — uploaded through the OCI
blob-upload session with no manifest, no tag and no row of any kind. They are servable, because
`serveBlob` validates the repository rather than that the digest belongs to the image, and there is
nothing else in the pool: the three sizes reconcile to `orphanBytes` with zero remainder. Anything
published through `PUT /artifacts/daemons/…` gets its row at publish time and lands in
`daemonBinaryBytes` instead; the three already on the volume move across when they are adopted,
which is an ops action rather than a migration — a lineage must not embed live-platform digests, and
a migration cannot verify one against the running store. Until then: report it; do not sweep it.

### Deliberately not here

- **The git host.** It shares the blob store and the datasource with the registries and nothing
  else: no `artifact_repository` entry, so its pack blobs are row-less to the census. Its refs are
  readable only as pkt-line and its object counts need JGit calls nobody has written.
- **Any link to a project.** Not one column in any of the six tables joins to one. `oci_manifest.repository`
  is `"qits"` for every row — that is the image namespace, equal to the project slug by naming
  accident — and `npm_version.package_name` does not even coincide. The one genuine cross-store link
  is `oci_tag.tag`, which is a git commit SHA, and it is undeclared.
- **Writes of any kind**, and "what is actually used". A tarball `GET` is a `sendFile` with zero
  database writes; last-accessed is `artifact-access-tracking.md`, which is the prerequisite for
  cleanup and is not started.

## Garbage collection

> **The cache engine is gone from this repo.** `CacheEvictionStrategy` and the three cache adapters went to qits-platform-mirror with the types they collect, and git pack GC went to qits-githost. What is left is the pin-based `OwnArtifactsStrategy` over the hosted types, the two CI stubs, and the reconciliation and sweep, which are type-agnostic and unchanged.

**Reading is the default; executing is a separate, deliberate act.** The dry-run plan shipped
first, the user reviewed it, and only then did the execute route land. Nothing executes without the
`POST`.

```
GET  /artifacts/api/gc/plan     what every type's strategy would delete, and what a sweep would unlink
POST /artifacts/api/gc/sweep    execute exactly that, once, and answer with the receipt
```

The sweep computes its own plan inside the request — there is no way to submit one, because a
stored plan is a plan on stale facts, and a plan on stale facts deletes a running image (the OCI
keep-set's cd pins are fetched inside the same request too). The receipt is the plan report's
executed twin: identities deleted per type, blobs unlinked with their bytes, what the grace window
withheld, the pins section the plan carries, and the untouchable pool restated.

**The grace window gates identity rows as well as blob unlinks.** A blob may only be swept by
*losing* its last identity row — so deleting a row while the blob's file is still inside the window
would strand the blob forever, row-less and untouchable. An identity whose released blobs include
one still in grace is therefore withheld whole: rows stay, the next run re-plans it, and row and
file mature out of the window together. On a store younger than the window a sweep provably
deletes nothing, and that no-op receipt is the mechanism's own safety proof.

The `POST` is a write under the `gc` prefix, so it inherits `ArtifactsTokenFilter`'s
`X-Artifacts-Token` check — stated honestly: the live deployment ships `qits.artifacts.token`
blank, which makes that guard inert until the platform's auth posture lands (the standing qits-platform-idp
direction; per the recorded decision, no interim token scheme is invented meanwhile). The
registries' `405` on client deletes (`/v2` manifests, npm unpublish) is untouched: no client gains
deletion semantics from any of this.

### Running a sweep (ops)

This platform has never deleted a byte, and the first sweep changes that. The choreography below is
what that first run does, and it is the **standing procedure** for every sweep after it — the first
one is not a special ceremony, it is the ordinary one performed while nobody yet trusts the result.

1. **Read the dry-run.** `GET /artifacts/api/gc/plan`. Start at `summary`: executable yes/no, what
   it would free, and which type is doing the work. Then check three things in the detail, because
   they are the three ways this goes wrong:
   - the **pins** section — every source `answered`, and its `keeps` are the shas your deployments
     are actually running and the daemon rungs qits-ci is actually on;
   - the **releases are on the kept side** — every type's `kept` list holds its last two releases,
     naming the release rule rather than an access window;
   - the **per-type windows** are the ones intended (`configuration[]`), and each type's dead list
     is content that really has gone cold for that long.
2. **Back up H2 and save the blob listing.** Copy the database file, and keep the plan's
   `sweep.blobIds` and `untouchable.blobIds`. The first is what the run is about to unlink; the
   second is the list to check the CI daemon binary against, and the pair is what makes an unwanted
   deletion answerable afterwards rather than only regrettable.
3. **Run one sweep, by hand.** `POST /artifacts/api/gc/sweep`, once. Nothing is scheduled and
   nothing runs on a timer; the plan it executes is computed inside that request.
4. **Verify four things, in this order:**
   - `GET /artifacts/api/store/summary` — the identity still balances
     (`diskTotal = ociUnion + npmPublished + npmProxyTarballs + mavenPublished + mavenProxy +
     orphans`), which is the census and the disk agreeing after a delete;
   - a **qits-cd restart still pulls** — restart one deployed application and watch it come back
     from its pinned sha, which is the pins section proved against reality;
   - an **evicted proxy package re-caches** — install something the run evicted and watch it come
     back from upstream, which is what "a cache holds re-fetchable content" means when it is true;
   - the receipt's `withheldByGraceWindow` — a young store withholds everything, and that no-op is
     the mechanism working rather than a failure to investigate.

Anything unexpected in step 1 is a reason to change configuration and re-read, not to run the sweep
and inspect afterwards: the plan is reviewable without side effects precisely so that argument
happens before the delete.

### Two layers, because identities and blobs are different questions

A blob is content-addressed bytes with no meaning of its own, so *"is this blob garbage"* is not a
question the blob store can answer. It lives one level up, in the repository types — and each type's
answer is its own.

- **Identity GC** is per type. It deletes *rows*: an `oci_tag`, an `npm_version`, an
  `artifact_record`, a pack description. It frees no bytes; it changes what the store *means*.
- **The blob sweep** is one mechanism with no policy at all. A blob may die only when **no type**
  reaches it any more, because the store dedupes globally across types and repositories.

That split is what keeps eight types collectable safely: a strategy never touches bytes, so no
type's rule can free a blob another type still needs.

**One rule per type used to mean one rule *class* per type; it does not any more.** This section
used to argue that docker's and npm's rules resemble each other by coincidence and must therefore
never share code — no base class, no retention-rule framework, no reuse. **That rule is superseded
by the user's decision of 2026-08-05** (`artifacts-gc-plan.md`, "Settlement"), which replaced the
bespoke strategies with two engines chosen per type in configuration. The design below is that
decision; the argument above is history, and re-splitting an engine into per-type rules is now the
change to refuse.

What the old rule was protecting survives it, and is not softened: a type has exactly **one**
policy (two claimants are a collision the report names and never merges), the per-type **facts**
stay in that type's own adapter, and no engine may switch on `RepositoryType`.

### The settlement: two engines, configured per type

The doctrine, settled by the user on 2026-08-05 (`artifacts-gc-plan.md`): **two generic strategies,
not one bespoke rule per type, mapped onto the types by configuration.**

- **`CacheEvictionStrategy`** — a pull-through cache holds somebody else's re-fetchable content, so
  everything unaccessed past the window goes and a live pin is the only thing that stays regardless.
  It has no release rule on purpose: keeping a mirrored tag because *upstream* calls it a release is
  how a mirror never shrinks.
- **`OwnArtifactsStrategy`** — the platform's own artifacts keep the **last 2 released versions per
  identity group** whatever their age, plus everything a live pin names; the rest ages out. Anything
  older survives on *use* — an old release someone still installs is accessed — rather than on
  policy.

Both check pins **before** the access rule, because a pin is the one fact no timestamp implies: a
container running untouched for months still pulls its image sha on restart.

The rules live in the engines; the **facts** live in a `GcTypeAdapter`, one per type, sharing no
code at all — what an identity is, what a release is, which of two is newer, when each was last
touched, and how a row is deleted. That is the line the settlement drew instead of the old one:
types share a *rule* now, chosen for them by name, and each still owns everything that makes it
different. The eight `*GcStrategy` classes left over are **binders** across that line — a type, an
adapter, four lines — and a rule appearing in one is the settlement being unpicked one type at a
time.

```java
public interface GcTypeAdapter {
  RepositoryType type();
  List<GcCandidate> enumerate();                 // identities, each with its effective access time
  Comparator<GcCandidate> byAge();               // oldest first — "newest" is a per-type fact
  GcStrategy.Applied delete(Plan, GraceWindow);  // this type's own collection funnel
}
```

**Effective access time is `max(created/published/fetched, accessed_at)`** — creation counts as a
first access, so something cached or published an hour ago reads as young rather than never-read.
The adapter folds that in; an engine only compares it.

The mapping is `qits.artifacts.gc.type.<wire-name>.strategy` (`cache`, `own`, `excluded`) and
`….window` (ISO-8601), shipped in the `gc` jar's own `META-INF/microprofile-config.properties`.
Every `RepositoryType` must have an entry — a type with none is **refused**, not defaulted, because
a type nobody configured is a decision nobody took.

| type | strategy | window |
|---|---|---|
| `oci-mirror`, `npm-proxy` | `cache` | `P30D` |
| `maven-proxy` | `cache` | `P90D` (a library is resolved when something builds against it, which is `maven-packages`' sentence and does not stop being true because the jar is somebody else's) |
| `oci-images`, `npm-packages` | `own` | `P30D` |
| `maven-packages`, `daemon-binaries` | `own` | `P90D` |
| `ci-screenshots`, `ci-videos` | `excluded` | — (a window beside a type nobody collects reads as a running rule) |

**Both engines are live over every configured type**, which is the first change to what dies on
this platform: six types used to condemn nothing between them. What each one condemns, in one line
each — the sections below carry the reasoning:

| type | engine, window | identity that dies | what keeps it | liveness expression |
|---|---|---|---|---|
| `oci-images` | `own`, `P30D` | a sha tag, or a manifest no tag and no tagged manifest reaches | the last 2 calver releases; a sha qits-cd pins; the newest sha per image; anything pulled inside the window | manifest closure over surviving tags and manifests |
| `npm-packages` | `own`, `P30D` | a published version | the last 2 releases by semver; anything a dist-tag names; anything installed inside the window | `npm_version.tarball_blob_id` of survivors |
| `maven-packages` | `own`, `P90D` | a **coordinate** — one version's whole file set | the last 2 release versions; the newest deployable set of every snapshot line; anything resolved inside the window | `maven_artifact.blob_id`, sized from the row |
| `daemon-binaries` | `own`, `P90D` | a `daemon_binary` row | the last 2 versions; both rungs qits-ci names; a pinned digest's bytes; anything downloaded inside the window | `daemon_binary.blob_id`, sized from the row |
| `oci-mirror` | `cache`, `P30D` | a cached tag, or a manifest no tag names | anything pulled inside the window; anything a live pin names by digest | manifest closure over survivors |
| `npm-proxy` | `cache`, `P30D` | a cached version, or a cached packument | anything installed inside the window; anything a live pin names by digest | `npm_version.tarball_blob_id` of survivors |
| `maven-proxy` | `cache`, `P90D` | a cached **file** — a path, not a coordinate — or a cached `maven-metadata.xml` | anything resolved inside the window; anything a live pin names by digest | `maven_artifact.blob_id`, sized from the row |
| `ci-screenshots`, `ci-videos` | `excluded` | **nothing** — no engine is configured, so nothing of them is ever deleted | everything | `artifact_record.blob_id`, reported live |
| git host (not an `artifact_repository` type) | none | superseded pack descriptions after a repack | every ref, current packs | `PackCatalog.list` per repo — its own later workstream |

`GcTypeConfigTest` is the guard over that table and is edited **deliberately, once per workstream**:
a type whose dead set moves has its new set written out there, and every other type stays
identity-for-identity what it was.

The report carries the **configuration echo** beside the outcomes: `configuration[]` in
`GET /gc/plan`, one line per type with the configured strategy, the window and the effective rule as
a sentence. It is the half of a plan the outcomes cannot show — "nothing died" reads identically
whether the rule is right or the window is a year. The two `excluded` types say so **twice**: in
that echo, and on their own entry in `types[]`, because `dead: []` beside a claimed strategy would
otherwise read as a rule that ran and found nothing.

### Live pins, and the whole-run abort

Two services hold references into this store that nothing here can derive, and both are read
**once at the start of every plan and every sweep**, never cached:

| source | what it pins | shape |
|---|---|---|
| `GET /cd/api/pins` (qits-cd) | image shas: what is serving, and what a rollback would restore, unioned over every environment | `{"pins":[{"applicationName":…,"shas":[…]}]}` |
| `GET /ci/api/daemon` (qits-ci) | the daemon ladder's top two rungs, which protect `daemon_binary` rows keyed `(name, version)` | `{daemonName, daemonVersion, previousDaemonVersion, source}` |

Both are folded into one `GcPins` for the run. Two details are load-bearing and neither is visible
on the wire:

- **A blank `daemonVersion` is an answer**, not an absence: it means this deployment has adopted or
  pinned no daemon, which is the shipped default. Treating it as a failure would abort every run on
  a platform that has published none.
- **A 64-hex daemon version pins a blob as well as a row.** The pin has been a sha256 digest since
  the daemon shipped (`QITS_CI_DAEMON_VERSION`, fetched from the blob route), so it may name bytes
  no version-addressed row exists for.

**A source that cannot answer aborts the whole run.** Not the type — the run. `POST /gc/sweep`
returns a receipt with `aborted` naming the source, every type carrying that reason, and nothing
deleted, before the census is even taken. The reason it is all-or-nothing: blobs dedupe globally, so
a tarball one type releases may be the last reference to bytes a pinned image also names, and with
the pins missing nothing can tell.

`GET /gc/plan` does **not** fail — a report that 500s tells a reviewer nothing about the types that
are fine. It answers with `executable: false`, the failures in `pinFailures`, and every pin-dependent
type carrying a refusal instead of zeros nobody can interpret.

The keep is reported under the pin's own name — `pinned by qits-cd deployment`, `pinned by qits-ci
daemon ladder` — and both engines check it **before** the access rule, because a pin is the one fact
no timestamp implies.

### One census, never two

`LiveBlobCensus` is the reference set, and both the store summary and every GC plan read it. It was
extracted from the explorer rather than written again, because a second implementation of "what is
live" is a set the UI reports and a set the sweep protects, drifting silently until the day a sweep
deletes something the page called referenced. Its byte-exactness is the summary's own identity,
proved by the explorer's tests: `diskTotal = ociUnion + npmPublished + npmProxyTarballs +
mavenPublished + mavenProxy + orphans`.

It splits liveness **by the type that names a blob**, which is what lets one type let go of content
another still serves — the same bytes can be an image layer and a published tarball, and the file is
one file.

### Row-less blobs are untouchable

A blob on disk that no identity row of any type names is reported (`untouchable` in the plan) and can
never be swept. This is structural rather than an allowlist: **a blob becomes a candidate only by
losing its last identity row to a strategy's own deletion**, so one that never had a row is out of
reach of the mechanism entirely.

It is the most important rule here. The store's row-less pool is 124 MiB in three ELF binaries pushed
through the OCI blob-upload session with no manifest — and one of them is the CI daemon binary every
build downloads by digest. A sweep that deleted "everything no row references" would stop CI
platform-wide. The plan lists the pool's digests so that fact is checkable rather than promised.

The git host's DFS pack blobs are in that pool too, and are safe for the same reason. Git's own GC,
when it lands, contributes them as a live set of its own; nothing about them needs a gate today.

### The delete primitive

`BlobStore.delete` is the only way bytes leave this store. It is **package-private** and its only
permitted caller is the `gc` module's `BlobSweep`, which reaches it through `BlobReclaim` — the one
narrow public door, javadoc'd as gc's alone. That unlink loop runs only when `GcSweepExecutor`
drives it behind the `POST`, after the strategies' identity deletions, against a census taken fresh
after them. Three constraints are enforced in the method rather than trusted to its caller:

- **A grace window** — `qits.artifacts.gc.blob-grace-period`, default **7 days**, measured from the
  file's mtime, which is when `promote` moved it into place. It closes the upload race: a client's
  blob-exists probe (or npm's dedupe) answers "have it" for a blob about to be unlinked, and the
  manifest referencing it lands after. Seven days covers any in-flight session by orders of
  magnitude. A withheld blob is not lost — it is reported as withheld, and the next run takes it.
- **A pre-unlink re-census** — a plan is a photograph; the store moves. The caller passes a guard
  that is asked again inside the store's write lock, the same lock `promote` takes, so the check and
  the unlink cannot be separated by a write. Both writers live in one JVM, which is what makes an
  in-process lock sufficient rather than hopeful. The guard must be a set lookup, not a computation:
  it runs with uploads blocked.
- **Delete then invalidate** — each unlink signals `BlobDiskIndex` exactly as `promote` does, so the
  store summary stays honest through a sweep.

Every outcome is a returned `DeleteResult`, not an exception: `ALREADY_GONE` and `STILL_REFERENCED`
are ordinary answers for a sweep running against a store that moved under it.

### The seam a strategy implements

`GcStrategy` is the whole contract. A strategy is a CDI bean; nothing else is registered, and a type
with two claimants is reported as a policy collision rather than merged.

```java
public interface GcStrategy {
  RepositoryType type();
  default boolean readsPins();                   // true ⇒ never planned on a run with broken pins
  Plan plan(LiveBlobCensus.Census census, GcPins pins);  // pure; throws ⇒ that type is fail-closed
  default String note();                         // a standing caption for the reports, or null
  default Applied apply(Plan plan, GraceWindow grace);  // the execute half; the default refuses
                                                        // any plan that condemns

  record Plan(List<GcIdentity> dead,      // what dies, each naming the rule that condemned it
              List<GcIdentity> kept,      // what survives, each naming the rule that saved it
              Set<String> blobsReleased,  // every blob the dead identities reference
              Set<String> blobsRetained)  // this type's live set AFTER the plan
  {}

  record Applied(List<GcIdentity> deleted,               // rows gone now
                 List<GcIdentity> withheldByGraceWindow, // left whole; re-planned next run
                 List<String> errors)                    // per identity, never thrown
  {}
}
```

The two blob sets may overlap, and asking for both is what keeps reconciliation out of the
strategies: a layer under a dying tag and a surviving one is released *and* retained, and subtracting
is the substrate's job. A strategy that cannot establish its keep-set safely **throws** — the planner
reports the type as failed and treats every blob the census attributes to it as live, so an
unreachable dependency reclaims nothing instead of guessing.

`plan()` stays pure — it is what the dry-run report reads. `apply()` is called only by
`GcSweepExecutor`, only on a plan the same request computed, and it owns its type's deletion
mechanics end to end: OCI rows go through `OciRegistryService.collectTag`/`collectManifest` (the
latter refuses a manifest a tag still names), npm rows through `NpmRegistryService.collect` (the
tombstone and the dist-tag refusal live in the mechanism, not the policy). A strategy that never
condemns — the mirror, the CI stubs — keeps the default, which is correct for the empty set and
loud for anything else.

### `oci-images`, on the own engine

`OciImageGcStrategy` is a four-line bean now: the rule is `OwnArtifactsStrategy`'s, the wiring is
`OwnGcStrategy`'s, and docker's facts are `OciImagesGcAdapter`'s. The settled rule, in one sentence:
**the last two calver releases of every image stay, everything a live pin names stays, and the rest
dies once nothing has pulled it for P30D.**

| Kept because | Spelled |
|---|---|
| it is one of the last two releases | a tag shaped like a calver version (`2026.801.85448`), ranked by the version's own order — not by a row timestamp, so a release pulled last week is not thereby the newer release. There is no `-main.g<sha>` suffix in docker: the sha tag *is* the prerelease coordinate and a release adds a version tag beside it |
| qits-cd pins it | any sha `GET /cd/api/pins` names for that image — what is serving, and what a rollback would restore. **One rule, cd's**: this used to be two rules derived here from raw deployment rows, and the derivation was wrong (it read a `FAILED` attempt as the rollback target and dropped the sha that actually served) |
| the next deploy will pull it | the newest sha tag per image, by `oci_tag.updated_at`. This is the whole safety net for an image cd has never deployed, and it reads `updated_at` rather than the access time the window judges on — a cold, never-deployed image is exactly the case it exists for |
| something still pulls it | anything else accessed inside P30D. This is where the old *unclassified-means-keep* backstop went: a coordinate nobody modelled is kept for as long as it is used, which is a better answer than "forever" and a safer one than the structural rule it replaces |

**Two rules changed direction, and both changes are the settlement's.** Calver releases used to be
kept forever and are now kept as the last two per image; an older one survives on *use*. Build-sha
tags used to die structurally the moment a newer build existed and now die only when cold — which
loosens the rule: a sha something still pulls survives, where the structural rule condemned it.

A manifest is an identity of its own **only when no tag names it and no tagged manifest reaches
it** — a tagged manifest's identity is its tag, and an index child rides on the index's closure.
That is the mirror's shape verbatim, and it has the mirror's consequence: a manifest whose tag this
run condemns becomes an untagged manifest today and is collected on the *next* run. Its bytes are
safe in the meantime — the sweep's pre-unlink re-census sees the surviving row and counts the blob
as still referenced — so the dry-run's per-type figure is the one that runs a run ahead of reality.
Collecting the store's measured 73 untagged manifests is the second half of this rule, and they are
now access-gated like everything else.

**Its pins come from the run**, fetched once at the start from qits-cd, never cached and never
derived here — see "Live pins, and the whole-run abort" above.

### `npm-packages`, on the own engine — and the tombstone only it needs

`NpmPackagesGcStrategy` is a four-line bean now: the rule is `OwnArtifactsStrategy`'s, the wiring is
`OwnGcStrategy`'s, and npm's facts are `NpmPackagesGcAdapter`'s. The settled rule: **the last two
releases of every package stay, anything a dist-tag names stays, and the rest dies once nothing has
installed it for P30D.**

| Kept because | Spelled |
|---|---|
| it is one of the last two releases | the version has **no prerelease part** — `0.0.1` through `2026.801.85149` — ranked by **semver precedence** (`NpmSemver`), not by publish order. Consumers pin ranges, and `^2026.801.85149` has to keep resolving |
| a pointer names it | any version a dist-tag currently names. A packument whose `dist-tags` names a version its `versions` does not list is a broken package to every npm client, so this is checked before the window rather than left to it |
| something still installs it | accessed inside P30D. This is where both of the old structural rules went: what `@main` resolves to was published minutes ago and is young by construction, and an `-rc.1` somebody made by hand is kept for as long as anything installs it |

**Two rules changed direction, and both changes are the settlement's.** Releases used to be kept
forever and are now kept as the last two per package; an older one survives on *use*, which is what a
lockfile install already does to `accessed_at`. And a prerelease used to die structurally the moment
a newer main build existed and now dies only when cold — which loosens the rule.

A version that does not parse as semver is never a release (it cannot be ordered, so it cannot be
one of the last two of anything) and is not thereby condemned either: the window decides.

`npm-proxy` shares the `npm_version` table and is collected by a **different engine over the same
table**: the hosted rows by the rules above, the cached ones by eviction (`NpmProxyGcStrategy`, its
own section below). The scope is filtered by the repository row's *type*, and asserted in both
suites, because a leak in either direction is the one mistake these two types can make.

**The tombstone is npm's alone, and docker needs nothing like it.** Version immutability is enforced
by looking for the row (publish over an existing version is `403`), so deleting a row would quietly
re-open that version's name for a publish carrying different bytes — one coordinate resolving to two
tarballs over its lifetime, the mutability this registry exists to refuse. Immutability's meaning
narrows honestly from *"a version row is never touched"* to *"a version, once published, is never
republished"*, and `npm_version_tombstone` (V6) carries the second half after the first stops being
true. `publish` consults it and answers a `403` that says **garbage-collected**, not *immutable*: a
pusher told "immutable" goes looking for a version that is not there. An OCI tag is a movable pointer
by design and re-pushing one has always been legal, so there is no promise for a deletion to weaken
on that side.

`NpmRegistryService.collect` is the only way a version row ever leaves, it writes the tombstone in the
same transaction, and it refuses a version a dist-tag still names. It is package-private, reached
only through the `NpmRegistryCollection` facade, and its one caller is
`NpmPackagesGcAdapter.delete` — it shipped ahead of that caller precisely so the tombstone
was never a step someone had to remember, and both guarantees live in the mechanism where no path
around them exists.

### The three caches — the first types that ever deleted something

`oci-mirror`, `npm-proxy` and `maven-proxy` run one engine (`CacheEvictionStrategy`) over one rule:
**everything unaccessed for longer than the configured window is evicted, and a live pin outranks
the window.** The first two used to condemn nothing — the mirror said `append-only pending access
tracking` out loud, npm-proxy was unclaimed — and both conditions are discharged: access tracking
shipped (V9 for the OCI tables, V11 for `npm_version` and `maven_artifact`) and the settlement
configured them as `cache`. `maven-proxy` arrived with its type already on the engine, which is what
a settled mapping is for.

What each one calls an identity, and what deleting it costs:

| | identity | effective access | eviction costs |
|---|---|---|---|
| `oci-mirror` | a cached tag; a manifest **no tag names** (an index's child, or a manifest an upstream tag moved off) | `max(updated_at/created_at, accessed_at)` | one upstream pull, per architecture actually asked for |
| `npm-proxy` | a cached version (`npm_version` row, proxy repositories only); a cached packument (`npm_proxy_packument` row) | version: `max(created_at, accessed_at)`; packument: `max(fetched_at, the newest access among that package's versions)` | one upstream request per document and per tarball |
| `maven-proxy` | a cached **file** (`maven_artifact` row, proxy repositories only); a cached document (`maven_proxy_metadata` row) | file: `max(created_at, accessed_at)`; document: `max(fetched_at, the newest access among the files cached under its directory)` | one upstream request per file and per document |

Five things are load-bearing and each one is a way this could have gone wrong:

- **A manifest a tag names is never a candidate of its own** — its tag is its identity. A child of a
  *kept* index is, though, and evicting one is not corruption: a mirror pull binds an index first and
  fetches children lazily, so an index referencing a child with no local row is the normal state of a
  partially-pulled image. Its bytes survive regardless, because the surviving index still reaches
  them in the census's closure.
- **A packument is judged with its versions' access folded in.** `fetched_at` says when the
  *document* was last revalidated upstream, which a TTL moves on its own; a package whose tarballs
  are being installed weekly is in use whatever its document's timestamp says.
- **A cached `maven-metadata.xml` is judged the same way**, with the access of the files cached
  under its directory folded in. Same argument, same failure without it: an artifact something
  builds against weekly is in use whatever its document's timestamp says.
- **Proxy eviction writes no tombstone, and goes through the cache's own doors.** A tombstone
  records "this version's name is spent forever", which is what a hosted registry owes its consumers
  and the exact opposite of what a cache owes: the content is upstream's and re-fetching it is the
  point. So each proxy has doors of its own —
  `NpmRegistryCollection.evictProxiedVersion`/`evictProxiedPackument` and
  `MavenRegistryCollection.evictProxiedArtifact`/`evictProxiedMetadata` — which refuse any
  repository that is not of the cache's type, because in both cases **one table holds both kinds of
  row**. That type check is what makes "no tombstone" safe to say out loud, and it is asserted from
  both sides.
- **`maven-proxy`'s identity is a PATH, where `maven-packages`' is a coordinate.** The hosted type
  folds a version's files into one identity because half a published version is a broken resolve
  nothing can repair. A cache repairs itself on the next request, so there is no half-version to
  prevent — and making a coordinate the unit here would withhold files nothing has asked for in
  months because one sibling is warm.

Mirrors stay a **separate type** for the reason they always were: `jdk-25` and `9.6` are neither
calver releases nor build shas, so under docker's rules every mirrored tag would land on the
unclassified-means-keep backstop. The engines sharpen it — own-ness earns version protection, and a
cache has none of ours to protect. A mirrored `jdk-25` is *upstream's* release, and keeping it
forever on that basis is how a mirror never shrinks.

The mirror's tag eviction also clears the tag's `oci_mirror_tag_check` row, **inside
`OciRegistryService.collectTag`** rather than in the caller: a freshness row for a tag that no longer
exists is a row nothing would ever read or delete again, and a funnel is the only place a rule like
that cannot be forgotten by a second caller. The same funnel serves `oci-images`; nothing in it
reads a repository's type, so no widening was needed to let the mirror through — only that one extra
line.

#### Reclaiming the H2 file after a sweep (ops)

**Evicting a packument frees no disk, and the report says so on every run.** The documents are CLOBs
inside the H2 file (660 MB of an 747 MB database, measured 2026-08-01), so a delete returns their
pages to H2's own free list and the file stays exactly the size it was. `reclaimableBytes` on the
`npm-proxy` line therefore counts tarball blobs and nothing else, and the type's `note` carries the
character figure beside that zero. `maven-proxy` carries the same note for the same reason over its
own `maven_proxy_metadata` documents, which are kilobytes rather than the packuments' hundreds of
megabytes and are in no store-summary figure at all — without it a run that condemned a hundred documents reads as a
run that did nothing.

The file shrinks only under `SHUTDOWN COMPACT`, which closes the database, so **nothing in this
service runs it** — a GC route may not take the platform's store offline. It is a maintenance
restart, in this order:

1. Run the sweep and read the receipt. Compacting before the rows are gone compacts nothing.
2. Stop qits-platform-artifacts (the H2 file is embedded; a live process holds it open).
3. Open the file with the H2 shell — the same JDBC url the service uses, from the same jar:
   `java -cp h2.jar org.h2.tools.Shell -url "jdbc:h2:file:<data-dir>/artifacts" -user … -sql "SHUTDOWN COMPACT"`.
   It rewrites the file and exits; the runtime is roughly linear in *live* data, and on the measured
   747 MB file it is a matter of minutes, not seconds.
4. Start qits-platform-artifacts and check `GET /artifacts/api/store/summary` — the blob figures are unchanged
   (this touches no blob), and the database file on the volume is the number that moved.

Take a copy of the file first, as with any offline database operation. A compaction that is
interrupted leaves the old file behind, which is recoverable; a compaction nobody has a copy of is
not something to find out about during one.

### `maven-packages`, on the own engine — the one type whose identity is not a row

`MavenPackagesGcStrategy` + `MavenPackagesGcAdapter`. This type used to plan "nothing dies" under a
note naming a cleanup rule nobody had implemented; the settlement priced every own type at once, so
the append-only posture is replaced deliberately rather than eroded, and the note goes with it.

**A coordinate is the identity, not a path.** A maven version is a *set* of files — a jar, its pom,
sometimes sources — and half a version is not a smaller version, it is a broken resolve. The settled
rule counts **versions** ("the last 2 release versions per artifact"), which an engine counting paths
could not express at all, so one identity here is one resolvable coordinate and every file under it
lives or dies together:

- `eu.wohlben.qits:qits-eventstream:1.0.0` — a release version directory. A **release**.
- `eu.wohlben.qits:qits-eventstream:1.0.1-20260802.123456-3` — one timestamped snapshot deploy,
  exactly the coordinate the derived version-level metadata resolves `1.0.1-SNAPSHOT` to.
- `eu.wohlben.qits:qits-eventstream:1.0.1-SNAPSHOT` — the literal-filename snapshot, the one mutable
  path class (`uniqueVersion=false`).

| Kept because | Spelled |
|---|---|
| it is one of the last two release versions | per `(groupId, artifactId)`, by **maven's own version order** (`MavenVersionOrder`) — `1.0.10` above `1.0.9`, which a lexical compare gets backwards |
| a resolver would break without it | **the newest deployable set of every snapshot version line**: the newest timestamped set if the line has any, else the literal `-SNAPSHOT` set. `maven-metadata.xml` is computed from the surviving rows at read time, so deleting that one would point the document at a file the store no longer has — the single failure this type must not produce |
| something still resolves it | the **newest** `max(created_at, accessed_at)` across the coordinate's files. A pom read is a resolve of the version, so one warm file keeps the set |

**Where this is deliberately conservative.** `maven-repository-plan.md` §3.6 sketched "keep the
newest N timestamped builds per snapshot version" and never settled N or priced the deletion, so no N
is invented: the window decides, and the only structural keep beyond the release belt is the one a
resolver would break without. The grace window gates the whole coordinate too — one young file
withholds every row of it, because deleting the mature rows and keeping the young one would produce
exactly the half-version the identity model exists to prevent.

Deletion goes through `MavenRegistryCollection` → `MavenRegistryService.collect`, one file at a time
with the collector removing a whole coordinate's set. No tombstone: a collected release path is a
coordinate the repository no longer serves at all, and a re-deploy there is a fresh deploy rather
than a mutation of a live one. `maven-proxy` is the cache beside it — a `cache` in the settlement's
mapping, with an adapter of its own and no rule of its own; see "The three caches" above.

### `daemon-binaries`, on the own engine — the type the platform *executes*

`DaemonBinariesGcStrategy` + `DaemonBinariesGcAdapter`, and it is the last type to be claimed: it
reported "no strategy registered" until its pin source existed, because the keep-set here is partly
qits-ci's answer and the blob class is the one a running service runs.

**Every row is a release.** There is no prerelease coordinate: a `daemon_binary` row is written in
the same transaction as a publish, publishes come from the release pipeline, and versions are
immutable (`409` on republish). So the belt is the settlement's sentence with nothing to qualify —
the last two versions of every daemon live whatever their age, both rungs of qits-ci's ladder live
under qits-ci's own rule, and the rest dies once nothing has downloaded it for P90D.

| Kept because | Spelled |
|---|---|
| it is one of the last two versions | per `(repository, name)`, ranked by version: an **adopted digest-hex version ranks below every calver one**, because the ops adoption carries the blob's own digest as the version and comparing 64 hex characters as a number would rank the oldest thing here as the newest |
| qits-ci's ladder names it | `GET /ci/api/daemon`, both rungs — the version a run would launch and the fallback beneath it. A runner that has not started in months still fetches its rung the moment one does |
| a pin names its bytes | the digest half of the same aggregate: `QITS_CI_DAEMON_VERSION` has been a sha256 since the daemon shipped, so a pinned digest keeps whichever row names those bytes |
| something still downloads it | accessed inside P90D, by the **version-addressed** GET. The digest-addressed `/v2` blob route moves nothing and must not grow a twin — it carries no daemon identity, and the pin is what keeps a digest-fetched daemon alive |

Deletion goes through `DaemonRegistryCollection` → `DaemonRegistryService.collect`, the fourth narrow
door beside `BlobReclaim`, `OciRegistryCollection` and `NpmRegistryCollection`. **There is no
tombstone here, deliberately**: npm has one because a deleted version re-opens its name for a publish
with different bytes under somebody's lockfile, while a daemon version is resolved by a pin a
bootstrap re-reads — so a re-release at a collected version is legitimate rather than a silent
content swap, and a tombstone would refuse it forever.

The row-less legacy ELF blobs are untouched by all of this and cannot be reached by any sweep: a blob
becomes a candidate only by *losing* its last identity row, and those never had one. The settlement's
answer to them is an ops action, once, by hand.

### What the plan says

```jsonc
{
  "summary": {                          // first, because it is what a human reads first
    "executable": true,
    "headline": "a sweep run now would execute this plan: 37 identities would be deleted and 21 blob files unlinked, reclaiming 402.7 MiB, with 3 blobs (12.0 KiB) withheld by the P7D grace window.",
    "identitiesCondemned": 37,
    "blobsSweepable": 21,
    "reclaimableBytes": 422313984,
    "reclaimable": "402.7 MiB",         // the same figure, so nobody counts digits
    "withheldByGraceWindow": 3,
    "types": [                          // one line per type: configured engine, window, outcome, note
      "ci-screenshots (excluded): excluded by configuration, so nothing of it is ever deleted — a decision, not a gap — stub: the golden-diff loop has never produced a screenshot.",
      "ci-videos (excluded): excluded by configuration, so nothing of it is ever deleted — a decision, not a gap — stub: the golden-diff loop has never produced a video.",
      "oci-images (own, P30D): 12 identities condemned, 28 kept, 9 blobs freed, 384.0 MiB reclaimable",
      "npm-packages (own, P30D): 1 identities condemned, 4 kept, 1 blobs freed, 17.5 KiB reclaimable",
      "npm-proxy (cache, P30D): 2 identities condemned, 2 kept, 1 blobs freed, 20.0 KiB reclaimable — cached packuments are H2 CLOBs, not files: 660287820 characters are cached as this report was produced.",
      "oci-mirror (cache, P30D): 2 identities condemned, 0 kept, 9 blobs freed, 18.7 MiB reclaimable",
      "maven-packages (own, P90D): 1 identities condemned, 6 kept, 2 blobs freed, 40.3 KiB reclaimable",
      "daemon-binaries (own, P90D): 1 identities condemned, 2 kept, 1 blobs freed, 41.1 MiB reclaimable"
    ]
  },
  "generatedAt": "2026-08-01T12:00:00Z",
  "dryRun": true,                       // always, on this route; the sweep's receipt is the twin with false
  "graceWindow": "P7D",
  "executable": true,
  "pinFailures": [],
  "pins": [                             // how this run read its pins; the sweep receipt carries the same
    { "source": "qits-cd", "url": "http://qits-cd:8080/cd/api/pins",
      "answered": true, "readAt": "2026-08-01T12:00:00Z", "tookMillis": 34,
      "outcome": "9 application pins over 14 image shas — what is serving, and what a rollback would restore",
      "pinCount": 9,
      "keeps": ["qits-platform-artifacts:3ff84c05…", "qits-ci:ab854a19…"] },
    { "source": "qits-ci", "url": "http://qits-ci:8080/ci/api/daemon",
      "answered": true, "readAt": "2026-08-01T12:00:00Z", "tookMillis": 11,
      "outcome": "daemon qits-ci-daemon, 2 ladder rungs pinned (source: adopted)",
      "pinCount": 2,
      "keeps": ["blob 9f2c…", "qits-ci-daemon@2026.802.40", "qits-ci-daemon@9f2c…"] }
  ],
  "types": [                            // all eight, always — including the ones nobody collects
    { "type": "oci-images", "strategy": "OciImageGcStrategy",
      "note": null, "error": null,
      "dead": [{ "repository": "qits", "identity": "qits-ci:3ff84c05…",
                 "rule": "superseded and unaccessed for longer than P30D" }],
      "kept": [{ "repository": "qits", "identity": "qits-stt:2026.801.85448",
                 "rule": "among the last 2 released versions of this identity group — releases are kept by policy, not by access" }],
      "blobsReleased": 0, "blobsSweepable": 0, "reclaimableBytes": 0 },
    { "type": "daemon-binaries", "strategy": "DaemonBinariesGcStrategy",
      "note": null, "error": null,
      "dead": [{ "repository": "daemons", "identity": "qits-ci-daemon@2026.601.10",
                 "rule": "superseded and unaccessed for longer than P90D" }],
      "kept": [{ "repository": "daemons", "identity": "qits-ci-daemon@2026.802.40",
                 "rule": "pinned by qits-ci daemon ladder" }],
      "blobsReleased": 1, "blobsSweepable": 1, "reclaimableBytes": 43123792 },
    { "type": "npm-packages", "strategy": "NpmPackagesGcStrategy",
      "note": null, "error": null,
      "dead": [{ "repository": "npm", "identity": "@qits/ui-components@2026.801.63140-main.gab854a1",
                 "rule": "superseded and unaccessed for longer than P30D" }],
      "kept": [{ "repository": "npm", "identity": "@qits/ui-components@2026.801.85149",
                 "rule": "among the last 2 released versions of this identity group — releases are kept by policy, not by access" }],
      "blobsReleased": 1, "blobsSweepable": 1, "reclaimableBytes": 17904 },
    { "type": "maven-packages", "strategy": "MavenPackagesGcStrategy",
      "note": null, "error": null,
      "dead": [{ "repository": "maven", "identity": "eu.wohlben.qits:qits-eventstream:1.0.1-20260601.101010-1",
                 "rule": "superseded and unaccessed for longer than P90D" }],
      "kept": [{ "repository": "maven", "identity": "eu.wohlben.qits:qits-eventstream:1.0.1-20260802.123456-3",
                 "rule": "the newest deployable set of this snapshot version — what the derived maven-metadata.xml resolves to, so a resolver would 404 without it" }],
      "blobsReleased": 2, "blobsSweepable": 2, "reclaimableBytes": 41216 },
    { "type": "oci-mirror", "strategy": "OciMirrorGcStrategy",
      "note": null, "error": null,
      "dead": [{ "repository": "hub", "identity": "library/node@sha256:9f2c…",
                 "rule": "cached content unaccessed for longer than P30D" }],
      "kept": [{ "repository": "quay", "identity": "quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25",
                 "rule": "accessed inside the P30D window" }],
      "blobsReleased": 12, "blobsSweepable": 9, "reclaimableBytes": 402653184 },
    { "type": "npm-proxy", "strategy": "NpmProxyGcStrategy",
      "note": "cached packuments are H2 CLOBs, not files: 660287820 characters are cached as this report was produced. Evicting one … reclaims 0 bytes on disk … SHUTDOWN COMPACT …",
      "error": null,
      "dead": [{ "repository": "npmjs", "identity": "left-pad (packument)",
                 "rule": "cached content unaccessed for longer than P30D" },
               { "repository": "npmjs", "identity": "left-pad@1.3.0",
                 "rule": "cached content unaccessed for longer than P30D" }],
      "kept": [{ "repository": "npmjs", "identity": "chalk@5.3.0",
                 "rule": "accessed inside the P30D window" }],
      "blobsReleased": 1, "blobsSweepable": 1, "reclaimableBytes": 20480 },
    { "type": "ci-screenshots", "strategy": "CiScreenshotsGcStrategy",
      "note": "excluded by configuration: no engine is configured for this type, so nothing of it is ever deleted — a decision, not a gap. stub: the golden-diff loop has never produced a screenshot. The intended rule is branch-scoped …",
      "error": null, "dead": [], "kept": [],
      "blobsReleased": 0, "blobsSweepable": 0, "reclaimableBytes": 0 }
  ],
  "sweep":       { "blobCount": 0, "reclaimableBytes": 0,
                   "withheldByGraceWindow": 0, "withheldBytes": 0, "blobIds": [] },
  "untouchable": { "reason": "…", "blobCount": 3, "bytes": 130419952, "blobIds": ["…"] }
}
```

Details a reader trips over otherwise:

- **The summary is derived, never a second opinion.** Every figure in it comes from the report below
  it (`GcSummary`), so it can only be wrong about arithmetic. It leads with the refusal when a plan
  is not executable, because a plan that cannot run must never be skimmed as a plan that would free
  nothing — the figures under a refusal are what the types that could still plan would do.
- **The pins section is the provenance of every keep.** The keep-set is partly another service's
  answer, so the report shows the answer as well as its consequences: the url called, whether it
  answered, how long it took, and the keep-identities it produced. Checking that the pinned shas are
  the shas the deployments are running is the first thing a review of this report does. The sweep
  receipt carries the same section, an aborted run included — a receipt whose whole story is one
  unreachable source has to show that source.
- **A type with no strategy says so, and an excluded type says so twice.** "Nothing to collect",
  "nobody is collecting" and "nobody is *meant* to be collecting" are three answers, and the report
  distinguishes them: an unclaimed type carries "no strategy registered" (what a **new**
  `RepositoryType` reads as on the day it lands, proved by `GcPlanTest` over an empty registration),
  while `ci-screenshots` and `ci-videos` carry the excluded line on their own entry as well as in
  the configuration echo.
- **Every type on an engine refuses when the pins are missing** — all six of them. A run with
  qits-cd or qits-ci unreachable reports them as `live pins unavailable` rather than planning them
  against "nothing is pinned". Two of them are pinned by *coordinate* (an image sha, a daemon
  version); the rest only by **digest**, and blobs dedupe globally, so the check is real everywhere.
- **`sweep` is not the sum of the per-type figures.** A blob dies once, and two types releasing the
  same content free it once. The per-type numbers answer "what does this rule buy on its own"; the
  sweep answers "what would a run free tonight".
- **The per-type figures ignore the grace window; the sweep applies it.** The window is about when an
  unlink may happen, not about what a rule structurally frees, and a strategy's worth should not read
  as zero because its content was pushed this morning.

Reads are unguarded like their neighbours (`ArtifactsTokenFilter` covers write methods only). The
sweep `POST` is the write the `gc` prefix was pre-listed for, and it inherits the guard by
construction — with the blank-token honesty stated at the top of this section still applying.

## The boundary

Everything this context needs from the rest of qits goes through a port it declares and the
consuming application implements:

| Port | Required? | Absent means |
|---|---|---|
| `RepositoryNameResolver` | no | `/artifacts/git/:projectId/:repoName` is 404; `/artifacts/git/:repoId` — the older scheme and the daemon's own fallback — still serves |

That is the only one, and an application may still implement it. In the monorepo `GitHostRoutes`
injected `domain.repository.persistence.RepositoryNameRepository` directly; that alias table belongs
to the projects/repositories context, and this repo holds no foreign key into another context's
schema. The inline `QuarkusTransaction.requiringNew()` around the lookup moved into the port's
contract — the resolver is called on a Vert.x worker thread with no request context bound.

**It now also ships an adapter of its own, and absent still means the same 404.**
`HttpRepositoryNameResolver` asks qits-projects, which owns the alias table, over one
`GET <qits.projects.name-resolver-url>/{projectId}/repositories/by-name/{repoName}` — `200` with
`{"repositoryId": "<id>"}`, `404` for an unknown project or name. It carries `@DefaultBean`, so an
application implementing the port itself still wins, and there is never a second bean to disambiguate.

Two properties of it are the port's contract rather than this implementation's detail. **Unset config
answers nothing**: with no url it returns empty without dialling anything, which is the same 404 an
absent bean produces — so nothing changes anywhere the key is not set. And **it never throws**: a
timeout, a refused connection, a non-200 or an unreadable body are logged at WARN and answered empty,
because `GitHostRoutes` has no exception clause on this port and a throw would be a 500 where a 404
is owed. That is the opposite of the two GC pin ports below. Nothing is cached, for the pin ports'
reason turned around: a rename must not serve a stale id.

`CdDeploymentPins` and `CiDaemonPins` are the exceptions that prove the shape, so they are named
here rather than left to be discovered: both are declared **and** implemented inside this repo, over
HTTP, dialling `qits-cd` and `qits-ci` for GC's live pins (see "Garbage collection"). Absent is not a
supported configuration there, which is the difference that matters and the one the name resolver's
adapter does *not* share: a pin source that cannot answer aborts the whole GC run instead of falling
back.
Both live in `gc/` with the process that needs them — the `artifacts` library dials nothing at all,
which is the domain-blindness the module split gave back.

## Config

Defaults ship from each jar's `META-INF/microprofile-config.properties` (ordinal 100); the consuming
app's `application.properties` overrides them.

| Key | Default | What |
|---|---|---|
| `qits.artifacts.blobs-dir` | `~/.qits/data/artifacts/blobs` | content-addressed blob bytes |
| `qits.artifacts.gc.blob-grace-period` | `P7D` | how long a blob file must sit untouched before the sweep may unlink it — see "Garbage collection" |
| `qits.artifacts.gc.pins.cd-base-url` | `http://qits-cd:8080/cd/api` | where a run reads the image shas deployments pin (`GET /cd/api/pins`). **Renamed** from `qits.artifacts.gc.oci.cd-base-url` |
| `qits.artifacts.gc.pins.cd-timeout` | `PT10S` | per-request timeout on that fetch. **Renamed** from `qits.artifacts.gc.oci.cd-timeout` |
| `qits.artifacts.gc.pins.ci-base-url` | `http://qits-ci:8080/ci/api` | where a run reads the daemon pin ladder (`GET /ci/api/daemon`) |
| `qits.artifacts.gc.pins.ci-timeout` | `PT10S` | per-request timeout on that fetch |
| `qits.artifacts.gc.type.<wire-name>.strategy` | per type, see "The settlement" | which engine collects a repository type: `cache`, `own` or `excluded`. Every type must have one — a missing entry is refused, not defaulted |
| `qits.artifacts.gc.type.<wire-name>.window` | `P30D` / `P90D` per type | how long an identity may sit unaccessed before it is eligible, ISO-8601. Absent for an `excluded` type |
| `qits.auth.machine.required` | `false` | the machine-token rollout gate. Off, the JSON admin write surface is open — network trust. On, its writes need a bearer with `aud=qits-platform-artifacts` |
| `qits.auth.machine.audience` | `qits-platform-artifacts` | this service's own id, and the `aud` its tokens must carry |
| `qits.artifacts.startup-seed.enabled` | `true` | self-seed `ci-screenshots` + `ci-videos` + the `qits` image repository + the two npm roots (`npm`, `npmjs`) |
| `qits.ci.intake-url` | `http://localhost:8080/ci/api/events/post-receive` | post-receive delivery |
| `qits.projects.intake-url` | `http://localhost:8080/projects/api/events/post-receive` | the same event again, where it triggers the repository's backup push to GitHub. No credential, and `-o qits.no-ci` does not suppress it |
| `qits.projects.name-resolver-url` | **unset** | where `HttpRepositoryNameResolver` turns `(projectId, repoName)` into a repo id, up to but not including `/{projectId}`. Unset — the shipped state — means the port answers nothing and `/artifacts/git/:projectId/:repoName` 404s, exactly as before the adapter existed. A deployment sets `http://prod-qits-projects:8080/projects/api/projects` |
| `qits.ci.token` | blank | `X-CI-Token` on those events |
| `quarkus.oidc-client.client-enabled` | `false` | whether those events carry a bearer for `aud=qits-ci`. On needs `QITS_ARTIFACTS_CLIENT_SECRET`, or the boot fails |
| `quarkus.oidc.auth-server-url` | `http://qits-platform-idp:8080/idp` | the idp, reached direct on qits-net. Both the validation above and the token fetch use it |
| `qits.artifacts.oci.max-layer-size` | `1G` | the registry's per-layer cap, enforced while streaming |
| `qits.artifacts.oci.max-manifest-size` | `4M` | manifests are buffered whole to be digested and parsed |
| `qits.artifacts.oci.upload-session-ttl` | `PT30M` | in-memory upload sessions; lost on restart, by design |
| `qits.artifacts.oci.upload-idle-timeout` | `PT1M` | wait for the *next* chunk, not for the whole upload |
| `qits.artifacts.oci.mirror.tag-ttl` | `PT1H` | how long a mirrored tag serves before a `HEAD` revalidation |
| `qits.artifacts.oci.mirror.manifest-timeout` | `PT30S` | the bound on one upstream manifest request |
| `qits.artifacts.oci.mirror.blob-timeout` | `PT10M` | the bound on one upstream blob transfer |
| `qits.artifacts.oci.mirror.endpoint-override` | blank | dial every upstream here rather than at its own domain — the suite's stub seam, blank in a deployment |
| `qits.artifacts.npm.max-publish-size` | `32M` | the largest npm tarball, in either direction — see below |
| `qits.artifacts.maven.max-artifact-size` | `128M` | the largest maven artifact, in either direction — jars are megabytes at most; the deploy PUT streams, so the knob is the only bound |
| `qits.artifacts.daemon.max-binary-size` | `256M` | the largest daemon binary, in either direction. The measured ci-daemon is 43 MB, so this is headroom rather than a snug fit; the publish PUT streams, so the knob is the only bound a chunked upload has |
| `qits.artifacts.npm.proxy.upstream` | `https://registry.npmjs.org` | what an `npm-proxy` repository caches |
| `qits.artifacts.npm.proxy.packument-ttl` | `PT5M` | how long a cached packument serves before revalidation |
| `qits.artifacts.maven.proxy.upstream` | `https://repo1.maven.org/maven2` | what a `maven-proxy` repository caches |
| `qits.artifacts.maven.proxy.metadata-ttl` | `PT1H` | how long a cached `maven-metadata.xml` serves before revalidation. An hour rather than npm's five minutes because a maven build pins exact versions and reads this document only to resolve a range or `LATEST`; a release nobody sees for an hour costs nothing, a document refetched per resolve costs every build |
| `qits.repositories.git.max-pack-size` | `64M` | the git host's `BodyHandler` limit |
| `qits.repositories.git.protect-default-branch` | `false` | refuse a direct update/delete of a repo's default branch — see "The default branch's seatbelt" |
| `qits.repositories.git.push-token` | **unset** | the value `-o qits.token=<value>` must equal; unset and empty both match nothing |
| `quarkus.http.limits.max-body-size` | `1088M` | **global**; above the largest upload cap |
| `quarkus.http.enable-compression` | `true` | gzip on the way out — **build-time fixed**, see below |

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

`qits.artifacts.npm.max-publish-size` is the same kind of number for a third route, and stating it
is not optional: `BodyHandler.create()` defaults to 10 MiB, and an npm publish document carries the
tarball **base64-inflated by 4/3** inside JSON, so 32M here is roughly a 24M tarball ceiling. One
knob covers both directions — it also caps a tarball streamed in from upstream by the proxy —
because it answers one question, how large an npm tarball this deployment is willing to hold. A
deployment that pulls large prebuilt binaries (the `@next/swc-*` shape of package) raises it once
and both paths follow.

`quarkus.http.enable-compression` is in the shipped `application.properties` and **cannot be moved to
a deployment's environment**: it is `BUILD_AND_RUN_TIME_FIXED`, so an env var on the container is
read, accepted and ignored, with no warning anywhere. The value that ships is the value that runs.
The SPA bundle is 206 kB uncompressed and about 60 kB gzipped, and the explorer's JSON compresses
harder still.

`quarkus.http.compress-media-types` is deliberately **left unset**. Setting it *replaces* Quarkus'
default list rather than extending it, so naming one type would silently stop compressing the rest —
and the default is already right: `text/*`, `application/json`, `application/javascript` and the XML
family, none of which is an image layer, a tarball or a git packfile. Those are already-compressed
bytes served with `sendFile`, and re-compressing them would cost CPU to grow them.

`quarkus.rest.path=/artifacts/api` and `quarkus.http.non-application-root-path=/artifacts/q` are
**not** shipped from the jar's defaults: they are the deployable's own decision and live in
`service/src/main/resources/application.properties`. The suite inherits that file rather than
carrying a copy, so the absolute paths the tests assert are the ones the process serves.

Neither intake url's **path** is ours either — `/ci/api/events/post-receive` is qits-ci's segment and
`/projects/api/events/post-receive` is qits-projects', and only the host part of each is a deployment
decision.

## What is deliberately *not* here

- **The repositories/projects context.** Cloning, branches, commits, submodules, the alias table
  itself. This repo provisions a repository over the wire and serves its bytes; what a caller does
  with the history is not modelled here. **The GitHub backup too**: this host says a branch moved,
  and [qits-projects](https://github.com/QuicklyIterateTheSoftware/qits-projects) owns the sync
  target, the credential and the push.
- **CI.** The post-receive event is delivered *to* ci over HTTP; pipelines, runners and the intake
  live in [qits-ci](https://github.com/QuicklyIterateTheSoftware/qits-ci).
- **`QitsGitServlet` / `QitsRepositoryResolver`.** The pre-Vert.x servlet implementation, deleted in
  the monorepo long before the split. Their history is in this repo; their files are not.
- **Building images.** The registry stores and serves them; nothing here produces one. qits-ci step
  containers get no docker socket by design, so `docker build` inside a step fails today and keeps
  failing — consuming this registry works immediately, producing from within a step needs an
  unprivileged builder story of its own. Until then a producer is a host with a docker daemon; no
  credential, since the registry is tokenless and pushes stay inside the deployment.
- **Client-facing deletion.** Garbage collection executes now (see "Garbage collection"), but only
  as an operator's `POST /artifacts/api/gc/sweep` — the registries' `DELETE` endpoints stay
  unimplemented and row-less blobs stay untouchable.
- **Building packages.** The npm registry stores and serves them; nothing here runs `npm pack`. A
  producer is a CI step that runs `npm publish` over plain HTTP to `qits-platform-artifacts:8080` — no docker
  socket, no credential, since the registry is tokenless and publishes stay inside the deployment.
- **A deployable.** See "Layout" above.
