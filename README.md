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

Both are library jars, in the shape the monorepo already packaged `artifacts` in: a consuming
Quarkus application pulls them in and gets the routes. Packaging `service` as an app would need an
auth variant, a webui and a main class, none of which belong to this context — the same gap
`migration-plan.md` §9 item 7 records for qits-workspaces.

`artifacts/` owns its **own datasource, persistence unit and Flyway lineage**
(`db/artifacts/migration`, a separate H2 under `~/.qits/data/artifacts`). It always did — that
lineage was never part of the monorepo's shared `db/migration`, so unlike every other extraction
target nothing here had to be squashed or rebased. `V1__init.sql` is the original file, unchanged.

## The blob store

A **repository** is a name plus a type (`ci-screenshots`, `ci-videos`); the type selects the
validation profile and the size cap. A **record** is one immutable upload: the content id is the
SHA-256 of the bytes, so many records may share one blob and stay distinct rows.

Metadata is a flat `String -> String` map, delivered as `X-Artifacts-Meta-*` request headers and
queried as exact-match `?meta.<key>=<value>` predicates, with a `?latest` collapse. That is the
whole coupling model: **blobs reference qits branches and flows by string metadata, never by
foreign key.** Nothing here breaks when qits' schema moves, and nothing here needs a JPA relation
into another context's tables.

    PUT  /api/artifacts/repositories/{repo}                 ensure a repository (token-guarded)
    POST /api/artifacts/repositories/{repo}/blobs           upload, raw body + X-Artifacts-Meta-*
    GET  /api/artifacts/repositories/{repo}/blobs/{id}      serve — open, cacheable, immutable
    GET  /api/artifacts/repositories/{repo}/blobs?meta.…    query

Writes are guarded by a single static token (`X-Artifacts-Token`, config `qits.artifacts.token`;
blank in dev/test disables the guard). Reads are never guarded — a blob must be usable directly as
an `<img>`/`<video>` src.

## The git host

`GitHostRoutes` mounts JGit's `UploadPack`/`ReceivePack` on plain Vert.x routes at `/git/*` —
deliberately **not** as a servlet, because `quarkus-undertow`'s presence breaks Quinoa's production
static serving in the consuming app. JGit speaks the wire protocol and nothing else; the git CLI
remains the only thing that mutates a repository.

There is **no authentication** on `/git/*`. Repo ids are capability UUIDs and the callers are
workspace containers, which cannot hold a user session. A deployment must therefore allow-list
`/git/*` (in the monorepo that is `auth/core`'s `PublicPaths`).

Two addressing schemes:

- `/git/:repoId` — the opaque UUID, resolving to `<data-dir>/<repoId>/origin`.
- `/git/:projectId/:repoName` — a project's repositories served as siblings, so committed relative
  submodule urls (`../<name>.git`) resolve natively. Needs the `RepositoryNameResolver` port.

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
| `RepositoryNameResolver` | no | `/git/:projectId/:repoName` is 404; `/git/:repoId` — the older scheme and the daemon's own fallback — still serves |

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
| `qits.ci.intake-url` | `http://localhost:8080/api/ci/events/post-receive` | post-receive delivery |
| `qits.ci.token` | blank | `X-CI-Token` on those events |
| `quarkus.http.limits.max-body-size` | `64M` | **global**; sized to the largest upload cap |

The last one is a hard *global* ceiling in Quarkus — a custom Vert.x route does not bypass it — so
this jar raising it also raises it for the consuming app's own routes. That tradeoff is the
monorepo's `docs/issues/2026-07-19_artifacts-global-max-body-size-widens-public-ingest-dos.md`.

`quarkus.rest.path=/api` is **not** shipped as a default: it is an app-wide decision. The consuming
application sets it; the tests here set it too, because they assert absolute paths.

## What is deliberately *not* here

- **The repositories/projects context.** Cloning, branches, commits, submodules, the alias table
  itself. This repo serves bytes out of bare origins someone else creates.
- **CI.** The post-receive event is delivered *to* ci over HTTP; pipelines, runners and the intake
  live in [qits-ci](https://github.com/QuicklyIterateTheSoftware/qits-ci).
- **`QitsGitServlet` / `QitsRepositoryResolver`.** The pre-Vert.x servlet implementation, deleted in
  the monorepo long before the split. Their history is in this repo; their files are not.
- **A deployable.** See "Layout" above.
