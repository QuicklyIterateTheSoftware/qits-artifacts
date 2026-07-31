# qits-artifacts

qits' **byte plane**: the metadata-rich blob store, the in-process git smart-HTTP host workspace
containers clone from and push to, the OCI registry they pull images from, and the npm registry —
hosted, plus a pull-through cache of npmjs — they install from.

One repo, because every one of them is "qits serves bytes over HTTP against on-disk state it owns"
and none has an inbound edge from the rest of qits. The two protocol registries are the third and
fourth uses of the byte plane and both were predicted by the code — `RepositoryType` already
reserved the seam — and neither needed a new storage layer, because a content-addressed SHA-256 blob
store *is* the OCI blob model and is a perfectly good home for a tarball. The git host landed here
rather than in a `qits-repositories` service because that name collides with `domain.repository` —
see `migration-plan.md` §3.4 in the home repo.

    mvn verify        # a clone of this repo alone builds and tests green — no monorepo, no docker

## Layout

| Module | What |
|---|---|
| `artifacts/` | `eu.wohlben.qits.artifacts.*` — entity, persistence, dto, mapper, control, error. The blob store proper. No web, no JAX-RS. |
| `service/` | `eu.wohlben.qits.artifacts.api` (the JAX-RS boundary), `eu.wohlben.qits.githost` (the Vert.x + JGit smart-HTTP host), `eu.wohlben.qits.registry` (the Vert.x OCI Distribution API) and `eu.wohlben.qits.npm` (the Vert.x npm registry and its upstream proxy). |
| `service/src/main/webui/` | The `qits-spa-artifacts` submodule — an Angular SPA, built into the app by Quinoa and served at `/artifacts`. Not Java, and not a Maven module. |

`artifacts/` is a library jar. **`service/` is the application** — it carries
`<packaging>quarkus</packaging>` and produces a process, as a JVM fast-jar or as a native binary:

    ./mvnw verify
    java -jar service/target/quarkus-app/quarkus-run.jar   # :8080 — SPA /artifacts/, blobs /artifacts/api/**,
                                                           #         git /artifacts/git/**, npm /artifacts/npm/**,
                                                           #         images /v2/**

    ./mvnw verify -Dnative
    ./service/target/qits-artifacts                        # same routes, ~35ms to listening

**Native is the shipping form.** `.sdkmanrc` names a GraalVM (`25.0.2-graalce`) so `sdk env` alone
is enough toolchain: the build wants a `native-image` on `GRAALVM_HOME`, `JAVA_HOME` or `PATH`, and
finding none it does not fail — it falls back to pulling a 1.8 GB Mandrel image and compiling under
docker. That fallback still works and is what a CI without a GraalVM gets; it is just not the
intended path, and it is worth recognising by name when a build that normally takes a minute starts
downloading a container image.

`-Dnative` also flips `skipITs`, so it runs `PackagedProcessIT` against the binary it just built —
openapi, swagger-ui, a blob round trip, a real `git clone` + `push`, an image push/pull and an npm
publish/install. That suite is the only thing in this repo that exercises **JGit compiled ahead of
time**, which is the part of this service most likely to break in a native image (see "The git host"
below), the only thing that exercises zero-copy `sendFile` serving in a binary, and the only place
the four route stacks are proved to coexist in one process.

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
`quarkus.rest.path`; `/artifacts/git/**`, `/artifacts/npm/**` and `/v2/**` are registered straight
onto the Vert.x router with the segment as a literal, and do not. A `git clone
http://<host>/artifacts/git/<repoId>` against the packaged process is the check that matters,
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

- The protected ref is the bare's own `HEAD` (`repo.getFullBranch()`), which is per-repo with no
  table and no cross-service read. Every other ref is untouched — workspace branches are force-pushed
  and deleted constantly and this must be invisible to them.
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
that fixes it. A deployment turns it on by env; a single repository opts out with
`[qits] protectDefaultBranch = false` in its own bare config, which needs no table (this service owns
none) and travels with the volume.

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
is `403`. Only `npm_dist_tag` mutates.

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
| `qits.artifacts.token` | blank (open) | the JSON API's write guard (`X-Artifacts-Token`); the registry is deliberately tokenless |
| `qits.artifacts.startup-seed.enabled` | `true` | self-seed `ci-screenshots` + `ci-videos` + the `qits` image repository + the two npm roots (`npm`, `npmjs`) |
| `qits.repositories.data-dir` | `~/.qits/data/repositories` | where the git host finds `<repoId>/origin` |
| `qits.ci.intake-url` | `http://localhost:8080/ci/api/events/post-receive` | post-receive delivery |
| `qits.ci.token` | blank | `X-CI-Token` on those events |
| `qits.artifacts.oci.max-layer-size` | `1G` | the registry's per-layer cap, enforced while streaming |
| `qits.artifacts.oci.max-manifest-size` | `4M` | manifests are buffered whole to be digested and parsed |
| `qits.artifacts.oci.upload-session-ttl` | `PT30M` | in-memory upload sessions; lost on restart, by design |
| `qits.artifacts.oci.upload-idle-timeout` | `PT1M` | wait for the *next* chunk, not for the whole upload |
| `qits.artifacts.npm.max-publish-size` | `32M` | the largest npm tarball, in either direction — see below |
| `qits.artifacts.npm.proxy.upstream` | `https://registry.npmjs.org` | what an `npm-proxy` repository caches |
| `qits.artifacts.npm.proxy.packument-ttl` | `PT5M` | how long a cached packument serves before revalidation |
| `qits.repositories.git.max-pack-size` | `64M` | the git host's `BodyHandler` limit |
| `qits.repositories.git.protect-default-branch` | `false` | refuse a direct update/delete of a repo's default branch — see "The default branch's seatbelt" |
| `qits.repositories.git.push-token` | **unset** | the value `-o qits.token=<value>` must equal; unset and empty both match nothing |
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

`qits.artifacts.npm.max-publish-size` is the same kind of number for a third route, and stating it
is not optional: `BodyHandler.create()` defaults to 10 MiB, and an npm publish document carries the
tarball **base64-inflated by 4/3** inside JSON, so 32M here is roughly a 24M tarball ceiling. One
knob covers both directions — it also caps a tarball streamed in from upstream by the proxy —
because it answers one question, how large an npm tarball this deployment is willing to hold. A
deployment that pulls large prebuilt binaries (the `@next/swc-*` shape of package) raises it once
and both paths follow.

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
- **Garbage collection.** Both registries are append-only; untagged manifests, orphaned blobs and a
  proxy cache that only grows all accumulate. Acceptable at private-deployment scale, and the
  `DELETE` endpoints stay unimplemented so nothing depends on deletion semantics before they exist.
- **A maven repository type.** The same seam again, a third protocol.
- **Building packages.** The npm registry stores and serves them; nothing here runs `npm pack`. A
  producer is a CI step that runs `npm publish` over plain HTTP to `qits-artifacts:8080` — no docker
  socket, no credential, since the registry is tokenless and publishes stay inside the deployment.
- **A deployable.** See "Layout" above.
