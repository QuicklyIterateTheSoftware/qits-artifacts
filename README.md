# qits-artifacts

qits' **byte plane**: the metadata-rich blob store, and the in-process git smart-HTTP host
workspace containers clone from and push to.

Two things, one repo, because both are "qits serves bytes over HTTP against on-disk state it owns"
and neither has an inbound edge from the rest of qits. The git host landed here rather than in a
`qits-repositories` service because that name collides with `domain.repository` — see
`migration-plan.md` §3.4 in the home repo.

    mvn verify        # a clone of this repo alone builds and tests green — no monorepo, no docker

## Layout

| Module | What |
|---|---|
| `artifacts/` | `eu.wohlben.qits.artifacts.*` — entity, persistence, dto, mapper, control, error. The blob store proper. No web, no JAX-RS. |
| `service/` | `eu.wohlben.qits.artifacts.api` (the JAX-RS boundary) and `eu.wohlben.qits.githost` (the Vert.x + JGit smart-HTTP host). |

`artifacts/` is a library jar. **`service/` is the application** — it carries
`<packaging>quarkus</packaging>` and produces a process, as a JVM fast-jar or as a native binary:

    ./mvnw verify
    java -jar service/target/quarkus-app/quarkus-run.jar   # :8080, blobs on /artifacts/api/**, git on /artifacts/git/**

    ./mvnw verify -Dnative
    ./service/target/qits-artifacts                        # same routes, ~35ms to listening

**Native is the shipping form.** `.sdkmanrc` names a GraalVM (`25.0.2-graalce`) so `sdk env` alone
is enough toolchain: the build wants a `native-image` on `GRAALVM_HOME`, `JAVA_HOME` or `PATH`, and
finding none it does not fail — it falls back to pulling a 1.8 GB Mandrel image and compiling under
docker. That fallback still works and is what a CI without a GraalVM gets; it is just not the
intended path, and it is worth recognising by name when a build that normally takes a minute starts
downloading a container image.

`-Dnative` also flips `skipITs`, so it runs `PackagedProcessIT` against the binary it just built —
openapi, swagger-ui, a blob round trip and a real `git clone` + `push`. That suite is the only thing
in this repo that exercises **JGit compiled ahead of time**, which is the part of this service most
likely to break in a native image (see "The git host" below).

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
| `quarkus.http.limits.max-body-size` | `64M` | **global**; sized to the largest upload cap |

The last one is a hard *global* ceiling in Quarkus — a custom Vert.x route does not bypass it — so
this jar raising it also raises it for the consuming app's own routes. That tradeoff is the
monorepo's `docs/issues/2026-07-19_artifacts-global-max-body-size-widens-public-ingest-dos.md`.

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
- **A deployable.** See "Layout" above.
