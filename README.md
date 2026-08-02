# qits-artifacts

qits' **byte plane**: the metadata-rich blob store, the in-process git smart-HTTP host workspace
containers clone from and push to, the OCI registry they pull images from, the npm registry —
hosted, plus a pull-through cache of npmjs — they install from, and the maven repository they
deploy their own library to.

One repo, because every one of them is "qits serves bytes over HTTP against on-disk state it owns"
and none has an inbound edge from the rest of qits. The three protocol registries are the third,
fourth and fifth uses of the byte plane and all were predicted by the code — `RepositoryType`
already reserved the seam — and none needed a new storage layer, because a content-addressed
SHA-256 blob store *is* the OCI blob model and is a perfectly good home for a tarball or a jar.
The git host landed here rather than in a `qits-repositories` service because that name collides
with `domain.repository` — see `migration-plan.md` §3.4 in the home repo.

    mvn verify        # a clone of this repo alone builds and tests green — no monorepo, no docker

## Layout

| Module | What |
|---|---|
| `artifacts/` | `eu.wohlben.qits.artifacts.*` — entity, persistence, dto, mapper, control, error. The blob store proper. No web, no JAX-RS. |
| `git-storage/` | `eu.wohlben.qits.githost.storage` — a JGit `DfsRepository` whose packs, pack indexes and refs are blobs, plus the two ports it declares and does not implement (`PackBlobStore`, `PackCatalog`). One compile dependency: JGit. |
| `service/` | `eu.wohlben.qits.artifacts.api` (the JAX-RS boundary), `eu.wohlben.qits.githost` (the Vert.x + JGit smart-HTTP host), `eu.wohlben.qits.githost.persistence` (the two adapters and the git host's entities), `eu.wohlben.qits.registry` (the Vert.x OCI Distribution API), `eu.wohlben.qits.npm` (the Vert.x npm registry and its upstream proxy) and `eu.wohlben.qits.maven` (the Vert.x maven repository). |
| `service/src/main/webui/` | The `qits-spa-artifacts` submodule — an Angular SPA, built into the app by Quinoa and served at `/artifacts`. Not Java, and not a Maven module. |

`artifacts/` and `git-storage/` are library jars and depend on nothing of each other's — they are
different contexts, which is why `git-storage` declares ports and `service` implements them.
**`service/` is the application** — it carries
`<packaging>quarkus</packaging>` and produces a process, as a JVM fast-jar or as a native binary:

    ./mvnw verify
    java -jar service/target/quarkus-app/quarkus-run.jar   # :8080 — SPA /artifacts/, blobs /artifacts/api/**,
                                                           #         git /artifacts/git/**, npm /artifacts/npm/**,
                                                           #         maven /artifacts/maven/**, images /v2/**

    ./mvnw verify -Dnative
    ./service/target/qits-artifacts                        # same routes, ~35ms to listening

**Native is the shipping form.** `.sdkmanrc` names a GraalVM (`25.0.2-graalce`) so `sdk env` alone
is enough toolchain: the build wants a `native-image` on `GRAALVM_HOME`, `JAVA_HOME` or `PATH`, and
finding none it does not fail — it falls back to pulling a 1.8 GB Mandrel image and compiling under
docker. That fallback still works and is what a CI without a GraalVM gets; it is just not the
intended path, and it is worth recognising by name when a build that normally takes a minute starts
downloading a container image.

`-Dnative` also flips `skipITs`, so it runs `PackagedProcessIT` against the binary it just built —
openapi, swagger-ui, a blob round trip, a real `git clone` + `push`, an image push/pull, an npm
publish/install and a maven deploy/resolve. That suite is the only thing in this repo that
exercises **JGit compiled ahead of time**, which is the part of this service most likely to break
in a native image (see "The git host" below), the only thing that exercises zero-copy `sendFile`
serving in a binary, and the only place the five route stacks are proved to coexist in one process.

It was extracted as a library, on the reasoning that packaging it would need an auth variant, a
webui and a main class. All three have lapsed: authentication terminates at `qits-gateway` and this
service reads a header, Quarkus supplies the main class, and the webui is now this repo's own —
`service/src/main/webui` is the `qits-spa-artifacts` submodule, which `quarkus-quinoa` builds during
augmentation and serves from the packaged artifact at `/artifacts`. A fresh clone therefore wants
`git submodule update --init` before `./mvnw package`; without it Quinoa finds no `package.json`,
disables itself with a warning, and the app ships with no client while the build stays green.

The segment is spelled a THIRD time inside the client — the Angular `baseHref` in its `angular.json`
is `/artifacts/` — because the browser resolves asset urls against the document, not against
anything the server knows. Move `quarkus.quinoa.ui-root-path` and move that.

Everything is served under the `/artifacts` gateway segment, because `qits-gateway` routes
verbatim by prefix and rewrites nothing — there is no unprefixed form, on `qits-net` either.

Note the route stacks resolve differently: `/artifacts/api/**` is JAX-RS and moves with
`quarkus.rest.path`; `/artifacts/git/**`, `/artifacts/npm/**`, `/artifacts/maven/**` and `/v2/**`
are registered straight onto the Vert.x router with the segment as a literal, and do not. A `git
clone http://<host>/artifacts/git/<repoId>` against the packaged process is the check that matters,
because nothing in the JAX-RS configuration can prove it.

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
static serving — which used to mean "in the consuming app" and now means in this one, since the SPA
above is served that way. JGit speaks the wire protocol and nothing else; the git CLI remains the
only thing that mutates a repository.

Those routes are also why `quarkus.quinoa.ignored-path-prefixes` is spelled out in
`application.properties` rather than left to Quinoa's derivation: Quinoa derives its ignore list
from `quarkus.rest.path` and `quarkus.http.non-application-root-path`, and no config key names
`/artifacts/git` at all. The six routes below match ahead of Quinoa's SPA re-route on their own, but
the paths *between* them — `/artifacts/git/<repoId>` with no suffix — match nothing, and without the
ignore they would answer `200 text/html` where `git` needs a 404.

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

### The default branch's seatbelt

`ProtectedRefHook` is a JGit `PreReceiveHook` that refuses a direct **update or delete** of a
repository's default branch, so that releasing is something the platform does — through
qits-workspaces' `POST /workspaces/api/workspaces/{id}/integrate` — rather than something a person
remembers to do. It is a hook in Java rather than a `hooks/pre-receive` script because this host
**runs no git**: JGit is driven in-process, so a script in the bare's `hooks/` would never execute.

- The protected ref is the repository's own `HEAD` (`repo.getFullBranch()`), which is per-repo with
  nothing to keep in step and no cross-service read. Every other ref is untouched — workspace
  branches are force-pushed and deleted constantly and this must be invisible to them.
- **Creates are allowed.** An empty repository has no default branch to protect, so the first-run
  seeding push needs nothing.
- Two bypasses, both carried as **push options**, because options ride inside the pack protocol and
  therefore behave identically through all three doors this host is reachable through — a header
  cannot, since qits-gateway strips the whole `X-Qits-` prefix unconditionally:
  - `-o qits.release` — the integrate flow's own push. **Fast-forward only**, which is what keeps
    that push a compare-and-swap.
  - `-o qits.token=<value>` — "push anyway", including non-fast-forward and delete, **iff** the
    value equals `qits.repositories.git.push-token`. That property has **no default: unset matches
    nothing, and a configured-empty value matches nothing either.** With protection on and no token
    configured there is no direct push to the default branch at all; a deployment that wants the
    dev-loop escape configures one.
- Every accepted bypass and every refusal is logged at INFO, the token value never echoed, so "how
  much direct-to-main pushing is still happening" stays a question with an answer.
- Refusals name the integrate endpoint and say a matching token is what overrides them. They never
  echo a configured value — only whether this host has one, which is the part the pusher cannot
  otherwise know.

It ships **inert** (`protect-default-branch=false`) and that is load-bearing: this service is the git
host that serves its own redeploy, so a protection bug landing enabled could refuse the very push
that fixes it. A deployment turns it on by env; a single repository opts in or out through a row in
`git_repository_protection` (`RepositoryProtectionStore`), and no row means the platform-wide setting
decides.

That override used to be `[qits] protectDefaultBranch` in the bare's own config. It became a row
because a DFS-backed repository has no config file — `DfsRepository.getConfig()` is in-memory and its
save is a no-op — so the old read would have answered the platform default for every repository, with
no symptom anywhere. The row is the override for **both** backends: one question with two answer
sources eventually gets two answers.

Push options need `setAllowPushOptions(true)` on **both** `ReceivePack` instances — the one in
`service(...)` that receives them and the one in `infoRefs(...)` that advertises the capability. A
client only sends `-o` if it was offered, so missing the advertisement produces the confusing failure
where the option is silently never seen and every bypass is refused.

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
  -H 'Content-Type: application/json' -H "X-Artifacts-Token: $QITS_ARTIFACTS_TOKEN" \
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
anonymous too**: the registry carries no credential of its own, and `qits.artifacts.token` guards
the blob-store JSON API and nothing else (`RegistryOpenPushTest` pins that setting it does not
drag `/v2` back behind a docker login).

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

The registry once guarded writes with `qits.artifacts.token` as an HTTP Basic password. That
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
registry=http://qits-artifacts:8080/artifacts/npm/npmjs/     # everything, through the cache
@qits:registry=http://qits-artifacts:8080/artifacts/npm/npm/  # ours, from the hosted repo
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
dialling `qits-artifacts:8080` has no forwarding hop, so the request always carries the right answer
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
`qits-artifacts:8080` on qits-net, and from outside `/artifacts/npm/**` falls under qits-gateway's
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

`MavenRoutes` serves a maven repository at `/artifacts/maven/<repository>/<path…>`, the npm shape
verbatim: npm lives at `/artifacts/npm/npm/<pkg>`, so the platform's own library deploys to
`/artifacts/maven/maven/eu/wohlben/qits/qits-eventstream/1.0.0/qits-eventstream-1.0.0.jar`. The
first segment is the `artifact_repository` row; `maven` (type `maven-packages`, hosted) is seeded
at startup alongside `npm` and `npmjs`. Maven accepts a repository URL of any depth, so — like npm
and unlike `/v2` — there is no root-level segment, no gateway change, and no client-side routing
story beyond declaring the repository. A pull-through cache of Maven Central (`maven-proxy`, row
`central`) is its own settled workstream; until it lands, consumers reach Central directly.

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
| `versions` | `{"versions":[{"version","tarballSizeBytes","publishedAt","distTags"}]}` |
| `store/summary` | `{"ociPerImageSumBytes","ociUnionBytes","ociMirrorBytes","orphanBytes","npmPublishedBytes","npmProxyTarballBytes","npmProxyPackumentBytes","mavenPublishedBytes","mavenProxyBytes","diskTotalBytes"}` |
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

- **`itemCount` means something different per type**, on purpose: images for `oci-images`, packages
  for either npm type, deployed files for `maven-packages`, `artifact_record` rows for the two CI
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

The ten figures reconcile in exactly one way, which is the panel's whole claim:

```
diskTotalBytes = ociUnionBytes + npmPublishedBytes + npmProxyTarballBytes
               + mavenPublishedBytes + mavenProxyBytes + orphanBytes
```

`ociPerImageSumBytes` sits above `ociUnionBytes` by whatever images share, and
`npmProxyPackumentBytes` is outside the identity because those bytes are rows, not files.

**`orphanBytes` is not a rounding error.** This store holds 124 MiB in three ELF binaries — the
ci-daemon — uploaded through the OCI blob-upload session with no manifest, no tag and no row of any
kind. They are servable, because `serveBlob` validates the repository rather than that the digest
belongs to the image. There is no garbage collector to reclaim them. Report it; do not sweep it.

### Deliberately not here

- **The git host.** It shares a process and a URL segment with the registries and nothing else:
  separate volume, no blob store, no rows, no `artifact_repository` entry. Its refs are readable
  only as pkt-line and its object counts need JGit calls nobody has written.
- **Any link to a project.** Not one column in any of the six tables joins to one. `oci_manifest.repository`
  is `"qits"` for every row — that is the image namespace, equal to the project slug by naming
  accident — and `npm_version.package_name` does not even coincide. The one genuine cross-store link
  is `oci_tag.tag`, which is a git commit SHA, and it is undeclared.
- **Writes of any kind**, and "what is actually used". A tarball `GET` is a `sendFile` with zero
  database writes; last-accessed is `artifact-access-tracking.md`, which is the prerequisite for
  cleanup and is not started.

## Garbage collection

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
withheld, and the untouchable pool restated.

**The grace window gates identity rows as well as blob unlinks.** A blob may only be swept by
*losing* its last identity row — so deleting a row while the blob's file is still inside the window
would strand the blob forever, row-less and untouchable. An identity whose released blobs include
one still in grace is therefore withheld whole: rows stay, the next run re-plans it, and row and
file mature out of the window together. On a store younger than the window a sweep provably
deletes nothing, and that no-op receipt is the mechanism's own safety proof.

The `POST` is a write under the `gc` prefix, so it inherits `ArtifactsTokenFilter`'s
`X-Artifacts-Token` check — stated honestly: the live deployment ships `qits.artifacts.token`
blank, which makes that guard inert until the platform's auth posture lands (the standing qits-idp
direction; per the recorded decision, no interim token scheme is invented meanwhile). The
registries' `405` on client deletes (`/v2` manifests, npm unpublish) is untouched: no client gains
deletion semantics from any of this.

### Two layers, because identities and blobs are different questions

A blob is content-addressed bytes with no meaning of its own, so *"is this blob garbage"* is not a
question the blob store can answer. It lives one level up, in the repository types — and each type's
answer is its own.

- **Identity GC** is per type. It deletes *rows*: an `oci_tag`, an `npm_version`, an
  `artifact_record`, a pack description. It frees no bytes; it changes what the store *means*.
- **The blob sweep** is one mechanism with no policy at all. A blob may die only when **no type**
  reaches it any more, because the store dedupes globally across types and repositories.

That split is what makes six independent strategies safe by construction: a strategy never touches
bytes, so no strategy can free a blob another type still needs.

**Docker's and npm's rules resemble each other by coincidence, not by identity.** Both reduce to
"releases stay, keep the newest prerelease" today — but what a release is, what "newest" is, and what
deleting one breaks are different in the two systems. They are two classes with no shared policy
code, no base class beyond the seam, and no retention-rule framework. A future implementer's rule:
if a change would let one strategy reuse another's policy, it is the wrong change.

| type | identity that dies | keeps, always | keeps, conditionally | liveness expression | gate |
|---|---|---|---|---|---|
| `oci-images` | sha tags; manifests unreachable from kept tags | calver version tags | shas an ACTIVE qits-cd deployment pins, plus the previous distinct sha per service; newest sha per image | manifest closure over kept manifests | dry-run reviewed |
| `npm-packages` | suffixed `-main.g<sha7>` versions except the newest per package | every unsuffixed version; anything a dist-tag names | newest prerelease per package | tarball blob ids of kept versions | dry-run reviewed |
| `ci-screenshots` | records of deleted branches; superseded per (branch, userflow) | — | newest per (branch, userflow) | `artifact_record.blob_id` | **stub claims the type** (`CiScreenshotsGcStrategy`): plans nothing at zero rows under a note naming the rule, fails closed once rows exist |
| `ci-videos` | superseded per userflow beyond a byte budget | — | newest N per userflow, N in bytes | `artifact_record.blob_id` | **stub claims the type** (`CiVideosGcStrategy`): same posture, deliberately its own class — byte-budgeted is not branch-scoped |
| `npm-proxy` | **parked** — cache eviction is access-based, which is `artifact-access-tracking`'s territory, not a structural rule | — | — | — | not this design |
| `oci-mirror` | **nothing yet** — access is tracked, but no retention window/eviction policy is settled | every cached tag and manifest | — | manifest closure over the namespace | claimed, so the report says so |
| `maven-packages` | **nothing** — releases are never eligible; timestamped snapshot builds accumulate at a recorded price, with the cleanup rule named (keep the newest N per snapshot version) | every deployed file | — | `maven_artifact.blob_id`, sized from the row | claimed, so the report says so |
| git host (not an `artifact_repository` type) | superseded pack descriptions after a repack | every ref | current packs | `PackCatalog.list` per repo | the DFS migration |

The OCI keep-set is **fetched at plan time and fail-closed**: qits-cd unreachable aborts that type's
plan with nothing planned, never a plan on stale pins. The seam supports that directly — see below.

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
permitted caller is `BlobSweep` — whose unlink loop runs only when `GcSweepExecutor` drives it
behind the `POST`, after the strategies' identity deletions, against a census taken fresh after
them. Three constraints are enforced in the method rather than trusted to its caller:

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
  Plan plan(LiveBlobCensus.Census census);       // pure; throws ⇒ that type is fail-closed
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

### `oci-images`, the first of the six strategies that exist

`OciImageGcStrategy` implements the row the table above gives it. Five rules, each named in the
report beside the identity it saved:

| Kept because | Spelled |
|---|---|
| it is a release | a tag shaped like a calver version (`2026.801.85448`). There is no `-main.g<sha>` suffix in docker — the sha tag *is* the prerelease coordinate and a release adds a version tag beside it |
| a container is running it | the sha of an **ACTIVE** qits-cd deployment; a restart pulls that reference again |
| a rollback would pull it | the **previous distinct** sha per application — the newest row under the ACTIVE one whose sha differs. A redeploy of the same sha is not a previous version |
| the next deploy will pull it | the newest sha tag per image, by `oci_tag.updated_at`. This is the whole safety net for an image cd has never deployed |
| nobody modelled it | belt and braces: a tag that is neither a calver version nor a build sha is kept and reported as unclassified. Only build coordinates are ever condemned |

Then every manifest row **no kept tag reaches** dies, index children included — which is what
collects the store's 73 untagged manifests, left behind when a tag re-push moved the tag row.

**It reads qits-cd, at plan time, every time.** That is the only outbound dependency this service
has on another one, it is deliberate (`artifacts-gc-plan.md` ⚖4), and it is why `plan()` is allowed
to throw: a cached pin list is a plan on stale facts, and a plan on stale facts deletes an image a
container is about to restart from. `CdDeploymentPins` is the port; it fetches transport and no
policy, listing environments and then each environment's deployments, and the keep-set is the union
over **all** environments rather than one configured id — over-keeping is safe here and under-keeping
ends in `IMAGE_MISSING`. Any failure aborts that type with nothing planned.

### `npm-packages`, the second — and the tombstone only it needs

`NpmPackagesGcStrategy` implements its own row of the table. It shares no code with the strategy
above beyond the seam, and the resemblance stops as soon as the rules are spelled out:

| Kept because | Spelled |
|---|---|
| it is a release | the version has **no prerelease part** — `0.0.1` through `2026.801.85149`. Consumers pin ranges, and `^2026.801.85149` has to keep resolving. Never eligible: not by age, not because a newer release exists |
| it is the current main build | the newest `-main.g<sha7>` per package, by **semver precedence** (`NpmSemver`), which is what `@main` resolves to. `…85149-main.gd43d710` outranks `…85149-main.g21655ba` because prerelease identifiers compare as ASCII |
| a pointer names it | belt and braces: any version a dist-tag currently names. Today it changes no outcome, which is exactly when a backstop is worth having — a packument naming a version its `versions` does not list is a broken package |
| nobody modelled it | a prerelease of an unrecognised shape (an `-rc.1`), or a version that is not semver at all and so cannot be proved superseded. Only main builds are ever condemned |

`npm-proxy` is **not** claimed, though it shares the `npm_version` table: its content is a cache of
upstream, so its policy is eviction rather than retention and the design parks it. "No strategy
registered for npm-proxy" is the honest report of a decision nobody has taken.

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
same transaction, and it refuses a version a dist-tag still names. It is package-private and its one
caller is `NpmPackagesGcStrategy.apply` — it shipped ahead of that caller precisely so the tombstone
was never a step someone had to remember, and both guarantees live in the mechanism where no path
around them exists.

### `oci-mirror`, the third — a rule of "nothing dies", said out loud

`OciMirrorGcStrategy` keeps every cached tag and every cached manifest, under one rule:
**`append-only pending an access-based retention policy`**. That is the settled posture, not a
placeholder for an invented structural rule.

A cache's eviction is access-based — *which of these has nobody pulled in a year*. This store now
tracks that fact, but the retention window and deletion policy remain deliberately unsettled, so it
keeps everything at a price stated up front. The price: an estimated 1.5–2.5 GiB one-time fill for
the platform's real base images, then low-GiB-per-year drift as upstreams move mutable tags like
`jdk-25` and strand the manifests they used to name. A later GC policy can now use the same
access-filtered explorer view operators inspect.

**Why a class at all, when the answer is "no".** An unclaimed type reports "no strategy registered",
which is the honest report of a decision nobody has taken — and here one *was* taken. Claiming the
type is how the report tells the two apart, and the contrast with the `npm-proxy` line beside it is
the point of both. It is also why mirrors are a separate type: `jdk-25` and `9.6` are neither calver
releases nor build shas, so inside docker's rules every mirrored tag would land on the
unclassified-means-keep backstop — same outcome, dishonest report, and the one case that backstop
exists to catch buried under hundreds that are not it.

This is the one strategy that reads the census: with no rules of its own, the type's live set *is*
its answer. It also depends on nothing outside this service, so it can never fail closed — an
`error` on this type's line means something is genuinely wrong.

### `maven-packages`, the sixth — the mirror's shape, on purpose

`MavenPackagesGcStrategy` claims the type and plans `nothingDies` under a note, keeping the default
`apply` that refuses any condemning plan. It takes the mirror's shape rather than the CI stubs':
the stubs fail closed *when rows appear*, which made sense for types expected to stay empty — this
type has rows from its first hour, that being its purpose, so a fail-closed-at-rows stub would
report `error` on every plan forever and train the reader to ignore the one signal that means
something.

The rule itself, said out loud: **releases are never eligible** (a maven release repository is the
purest form of the rule npm and docker both reduce to), and **timestamped snapshot builds
accumulate** — priced honestly: jar plus pom at the platform library's tens-of-kilobytes scale is
noise, and even a CI cadence of snapshot deploys is single-digit MiB per library per year. The
cleanup rule is named in the strategy's note — *keep the newest N timestamped builds per (group,
artifact, snapshot version); releases never eligible* — and lands when someone wants the bytes
back. `maven-proxy` is deliberately unclaimed, the `npm-proxy` line verbatim: a re-fetchable cache
of upstream, whose eviction is `artifact-access-tracking`'s third waiting client.

### What the plan says

```jsonc
{
  "generatedAt": "2026-08-01T12:00:00Z",
  "dryRun": true,                       // always, on this route; the sweep's receipt is the twin with false
  "graceWindow": "P7D",
  "types": [                            // all seven, always — including the ones nobody collects
    { "type": "oci-images", "strategy": "OciImageGcStrategy",
      "note": null, "error": null,
      "dead": [{ "repository": "qits", "identity": "qits-ci:3ff84c05…",
                 "rule": "build sha: no qits-cd deployment pins it and it is not this image's newest build" }],
      "kept": [{ "repository": "qits", "identity": "qits-stt:2026.801.85448",
                 "rule": "calver release tag — releases are never eligible" }],
      "blobsReleased": 0, "blobsSweepable": 0, "reclaimableBytes": 0 },
    { "type": "npm-packages", "strategy": "NpmPackagesGcStrategy",
      "note": null, "error": null,
      "dead": [{ "repository": "npm", "identity": "@qits/ui-components@2026.801.63140-main.gab854a1",
                 "rule": "superseded main build: a newer one exists and no dist-tag names it" }],
      "kept": [{ "repository": "npm", "identity": "@qits/ui-components@2026.801.85149",
                 "rule": "release version — no prerelease part, so consumers' ranges resolve to it; releases are never eligible" }],
      "blobsReleased": 1, "blobsSweepable": 1, "reclaimableBytes": 17904 },
    { "type": "oci-mirror", "strategy": "OciMirrorGcStrategy",
      "note": null, "error": null,
      "dead": [],                       // never; the rule is the posture, not a placeholder
      "kept": [{ "repository": "quay", "identity": "quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25",
                 "rule": "append-only pending access tracking" }],
      "blobsReleased": 0, "blobsSweepable": 0, "reclaimableBytes": 0 },
    { "type": "npm-proxy", "strategy": null,
      "note": "no strategy registered for npm-proxy", "error": null,
      "dead": [], "kept": [],
      "blobsReleased": 0, "blobsSweepable": 0, "reclaimableBytes": 0 }
  ],
  "sweep":       { "blobCount": 0, "reclaimableBytes": 0,
                   "withheldByGraceWindow": 0, "withheldBytes": 0, "blobIds": [] },
  "untouchable": { "reason": "…", "blobCount": 3, "bytes": 130419952, "blobIds": ["…"] }
}
```

Three details a reader trips over otherwise:

- **A type with no strategy says so.** "Nothing to collect" and "nobody is collecting" are different
  answers, and only one of them is fine. Six types are claimed today (`oci-images`, `npm-packages`,
  `oci-mirror`, `maven-packages` — whose whole policies are "nothing dies", which is still a
  decision — and the two CI stubs, whose notes name their intended rules); only `npm-proxy` reports
  "no strategy registered", which is the honest state of a decision nobody has taken.
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

That is the only one an application implements. In the monorepo `GitHostRoutes` injected
`domain.repository.persistence.RepositoryNameRepository` directly; that alias table belongs to the
projects/repositories context, and this repo holds no foreign key into another context's schema.
The inline `QuarkusTransaction.requiringNew()` around the lookup moved into the port's contract —
the resolver is called on a Vert.x worker thread with no request context bound.

`CdDeploymentPins` is a second port and the exception that proves the shape, so it is named here
rather than left to be discovered: it is declared **and** implemented inside this repo, over HTTP,
and it is the one place this service dials another (`qits-cd`, for the `oci-images` keep-set — see
"Garbage collection"). Absent is not a supported configuration there, which is the other difference:
an unanswerable pin list aborts that type's plan instead of falling back.

## Config

Defaults ship from each jar's `META-INF/microprofile-config.properties` (ordinal 100); the consuming
app's `application.properties` overrides them.

| Key | Default | What |
|---|---|---|
| `qits.artifacts.blobs-dir` | `~/.qits/data/artifacts/blobs` | content-addressed blob bytes |
| `qits.artifacts.gc.blob-grace-period` | `P7D` | how long a blob file must sit untouched before the sweep may unlink it — see "Garbage collection" |
| `qits.artifacts.gc.oci.cd-base-url` | `http://qits-cd:8080/cd/api` | where the `oci-images` strategy reads its keep-set — the **only** service this one dials. Unreachable is fail-closed: that type reclaims nothing |
| `qits.artifacts.gc.oci.cd-timeout` | `PT10S` | per-request timeout on that fetch |
| `qits.artifacts.token` | blank (open) | the JSON API's write guard (`X-Artifacts-Token`); the registry is deliberately tokenless |
| `qits.artifacts.startup-seed.enabled` | `true` | self-seed `ci-screenshots` + `ci-videos` + the `qits` image repository + the two npm roots (`npm`, `npmjs`) |
| `qits.repositories.data-dir` | `~/.qits/data/repositories` | where the `file` git backend finds `<repoId>/origin` |
| `qits.repositories.git.storage` | `file` | which git storage backend serves repositories — `file` (bare origins on the volume) or `dfs` (packs and refs as blobs in this service's own store). Runtime; an unknown value fails the boot |
| `qits.ci.intake-url` | `http://localhost:8080/ci/api/events/post-receive` | post-receive delivery |
| `qits.ci.token` | blank | `X-CI-Token` on those events |
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
| `qits.artifacts.npm.proxy.upstream` | `https://registry.npmjs.org` | what an `npm-proxy` repository caches |
| `qits.artifacts.npm.proxy.packument-ttl` | `PT5M` | how long a cached packument serves before revalidation |
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
  unprivileged builder story of its own. Until then a producer is a host with a docker daemon; no
  credential, since the registry is tokenless and pushes stay inside the deployment.
- **Client-facing deletion.** Garbage collection executes now (see "Garbage collection"), but only
  as an operator's `POST /artifacts/api/gc/sweep` — the registries' `DELETE` endpoints stay
  unimplemented, row-less blobs stay untouchable, and the npm proxy cache still only grows (its
  eviction is parked for access tracking).
- **A maven Central pull-through.** The hosted maven repository is above; the `maven-proxy` type,
  its `central` row and the upstream cache are their own settled workstream (maven-repository-plan.md,
  ⚖3), landing after the platform's own library publishes.
- **Building packages.** The npm registry stores and serves them; nothing here runs `npm pack`. A
  producer is a CI step that runs `npm publish` over plain HTTP to `qits-artifacts:8080` — no docker
  socket, no credential, since the registry is tokenless and publishes stay inside the deployment.
- **A deployable.** See "Layout" above.
