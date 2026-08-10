# qits-platform-artifacts — working notes

Read `README.md` first: it defines what this repo owns (the blob store and the git host, plus the
three protocol registries built on the blob store — OCI at `/v2`, npm at `/artifacts/npm` and maven
at `/artifacts/maven`), the one
port, and the config surface. This file is the working conventions on top of it.

## The two rules that shape everything

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior
`mvn install` elsewhere, no credentials. `mvn verify` is the gate. Anything that would break that is
not a tradeoff to weigh, it is the thing this repo exists to avoid.

That is why: the poms duplicate versions instead of inheriting them, no pom declares a `eu.wohlben:*`
dependency, and `GitHostTest` provisions its own origin through the git host itself instead of
using the monorepo's antrun-derived `fixtures/testing-repo.git`.

**`service/` compiles to a GraalVM native image**, the same rule qits-workspace-daemon and
qits-gateway carry, and it extends the clone-alone rule rather than qualifying it: `.sdkmanrc` names
`25.0.2-graalce`, so `sdk env` gives you a `native-image` and `./mvnw verify -Dnative` produces
`service/target/qits-artifacts` in about a minute with no container involved.

Two consequences worth stating before you reach for a dependency:

- **A missing GraalVM does not fail the build.** Quarkus logs `Cannot find the native-image ...
  Attempting to fall back to container build` and shells docker with a 1.8 GB Mandrel image. Green
  either way, so the fallback is easy to be in without noticing — recognise it by the image pull.
- **Every dependency is a decision about what the builder has to be told.** Reflection, dynamic
  proxies, `ServiceLoader`, resource loading by computed name and JNI/JNA all need registering, and
  the failure lands at *runtime* in the binary while the JVM suite stays green. This repo has
  already paid that bill four times over — see "Native" below — so treat `PackagedProcessIT`, not
  `mvn verify`, as the gate for anything that touches JGit, Jackson-serialised DTOs or the
  datasource url.

## Native

`-Dnative` lives in `service/pom.xml`, not the root pom: only `service` is an application. It flips
`skipITs` so the build runs `PackagedProcessIT` against the binary rather than skipping past it.
`quarkus.package.output-name` and failsafe's `native.image.path` spell `qits-artifacts` twice and
must move together, or the native IT launches nothing and passes.

Everything that had to be declared, and the symptom each one produces if it is dropped:

| Where | What | Symptom without it |
|---|---|---|
| `application.properties` | `--initialize-at-run-time` for `jgit.util.FileUtils`, `jgit.lib.internal.WorkQueue`, `jgit.internal.storage.file.WindowCache` | build fails: a seeded `Random`, a started `JGit-WorkQueue` thread in the image heap |
| `application.properties` | `--initialize-at-run-time` for `jgit.internal.storage.dfs.DfsBlockCache` | **nothing yet** — measured, see below |
| `application.properties` | `WindowCache` **stays** although no repository is file-backed any more | unmeasured, and the failure mode is a silent 404 on every git route — `UploadPack`/`ReceivePack` pull JGit's file-storage classes in regardless |
| `githost/JGitReflection` | `values()` on every enum `Config.getEnum` reads | **every** git route 404s — `FileRepositoryBuilder.build` throws `NoSuchMethodException` and `open()` returns null |
| `dto/UploadResult` | `@RegisterForReflection` | every upload 500s: the type is behind a `Response` return, so nothing registers it |
| `PostReceiveNotifier` | the `HttpClient` **and** the retry `ScheduledExecutorService` are instance fields, not static | build fails: an `HttpClientFacade` in the image heap, and a started thread pool beside it |
| `npm/NpmUpstream` | the `HttpClient` is an instance field, not static | same as above — an `HttpClientFacade` frozen into the image heap |
| `maven/MavenUpstream` | the `HttpClient` is an instance field, not static | same as above; the sixth outbound client, and the rule has still not changed. It reads and writes only `String`/`byte[]` and needs nothing else declared — the maven stack, like `registry` and `npm`, still adds zero native-image configuration |
| `gc/CdHttpDeploymentPins`, `gc/CiHttpDaemonPins` | the `HttpClient` is an instance field, not static | same as above; the third and fifth outbound clients, and the rule has not changed. It moved with the class when GC became its own module — the rule travels with the client, not with the package |
| `registry/MirrorUpstream` | the `HttpClient` is an instance field, not static — and so is `MirrorBearerTokens`' `ObjectMapper`, which is reachable from one | same as above; the fourth outbound client, and the rule still has not changed |
| `githost/HttpRepositoryNameResolver` | the `HttpClient` is an instance field, not static | same as above; the seventh outbound client, and the rule has still not changed — it travels with the client, not with the package, so it applies in `githost` exactly as in `gc`. It reads the answer as a `JsonNode` and needs nothing else declared |
| artifacts' `microprofile-config.properties` | H2 url with no `AUTO_SERVER` | the binary dies at boot on `ClassNotFoundException: org.h2.server.TcpServer` |
| `registry/MirrorUpstream`'s config | `endpoint-override` injected as `Optional<String>`, not `String` | the binary dies at boot on `Failed to load config value of type java.lang.String` — SmallRye reads a **configured-empty** value as absent, and that key ships blank. `defaultValue = ""` does not help. Invisible to `mvn verify`, where every test sets a real value |

Only the first is a build-time failure. The rest are green builds that fail in production, which is
why the IT exists and why it drives a real `git clone`/`push` rather than asserting a status code.

The git host's content reads (`blob`/`tree`) needed **nothing added** — `TreeWalk` and `RevWalk`
read only config enums `JGitReflection` already names — but they reach JGit's object and tree
parsing, which no other route here does, so `PackagedProcessIT.contentReadsSurviveTheCompile` is
what says so rather than an assumption.

`DfsBlockCache` is the one entry here that is **precautionary rather than earned**, and it is
labelled so rather than quietly padding the list: the image builds green with and without it —
measured, both ways — so nothing observed has needed it. It is the direct analogue of `WindowCache`
above (a large static cache on the object-read path, one line below it in the same library) and it
is the only DFS class in that shape, so it is cheaper to declare than to rediscover. Drop it if a
later measurement shows it is dead weight; do not assume it earned its place.

## Paths

Almost everything is served under the `/artifacts` gateway segment — `qits-gateway` routes verbatim
by prefix, so an unprefixed route is normally unreachable, on `qits-net` as much as through the
gateway. Five second-level segments and the segment itself, plus one root-level exception:

| Prefix | Machinery | Moves with |
|---|---|---|
| `/artifacts/` | the Angular SPA, built and served by Quinoa from the `src/main/webui` submodule | `quarkus.quinoa.ui-root-path` **and** the client's own `baseHref` |
| `/artifacts/api/**` | JAX-RS | `quarkus.rest.path` |
| `/artifacts/q/**` | Quarkus' non-application root (openapi, swagger-ui, health) | `quarkus.http.non-application-root-path` |
| `/artifacts/git/**` | raw Vert.x routes in `GitHostRoutes` | **nothing** — the segment is a literal in the code |
| `/artifacts/npm/**` | raw Vert.x routes in `NpmRoutes` (the npm registry, hosted + proxy) | **nothing** — a literal, and `NpmPaths.BASE` is the only place it is spelled |
| `/artifacts/maven/**` | raw Vert.x routes in `MavenRoutes` (the maven repository, hosted + proxy) | **nothing** — a literal, and `MavenPaths.BASE` is the only place it is spelled |
| `/artifacts/daemons/**` | raw Vert.x routes in `DaemonRoutes` (the platform's own daemon binaries) | **nothing** — a literal, and `DaemonPaths.BASE` is the only place it is spelled |
| `/artifacts/docs/**` | raw Vert.x routes in `DocsRoutes` (published documentation bundles) | **nothing** — a literal, and `DocsPaths.BASE` is the only place it is spelled |
| `/v2/**` | raw Vert.x routes in `RegistryRoutes` (the OCI Distribution API) | **nothing** — a literal, and not under `/artifacts` at all |

`/artifacts/npm` is *not* forced on us the way `/v2` is: npm accepts a registry URL of any depth, so
it sits inside the segment the gateway already routes here and needs no extra prefix on
`QitsService.ARTIFACTS`. The first path segment after it is the `artifact_repository` row, the same
first-segment rule the OCI registry uses. `/artifacts/maven` is the same case verbatim: maven
accepts a repository URL of any depth, and `MavenPaths.BASE` is the only place the segment is
spelled.

The SPA is the one that takes the *whole* segment, so it is the one that can swallow the others.
Quinoa's SPA re-route is a catch-all at `/artifacts/*` registered near-last, so anything with a real
route in front of it still wins — but a request matching **no** route is rerouted to `index.html` and
answers `200 text/html`. `quarkus.quinoa.ignored-path-prefixes` is what stops that, and it is set
explicitly (`/api,/q,/git,/npm,/maven,/daemons,/docs,/v2`) rather than left to Quinoa's derivation,
because the
derivation reads `quarkus.rest.path` and `quarkus.http.non-application-root-path` and **nothing
names `/git`, `/npm`, `/maven` or `/daemons`**. `/daemons` is the least forgiving omission of the
four: a bootstrap script `curl`s a daemon binary and execs it, so `index.html` at 200 becomes an
executable that is a web page.
Setting the key REPLACES the derivation rather than extending it, and its values are relative to
`ui-root-path`, so `/api` and `/q` cannot be written as `${quarkus.rest.path}`: that line is a
hand-kept copy of a derivation and has to be edited when either key moves.

`/v2` is in that list even though **nothing is mounted at `/artifacts/v2`** — it is the one entry
that ignores a path no route serves. The registry is at the host root, so a deployment that sends
`/artifacts/v2` is misconfigured and has to find out: a 404 tells a registry client "not a registry
here", while the SPA answers 200 `text/html` with no `Docker-Distribution-Api-Version` header. That
assertion predates the SPA (`PackagedProcessIT.theRegistryIsMountedAtTheHostRootNotUnderTheArtifactsSegment`)
and enabling SPA routing is what would otherwise have flipped it — it caught the change, which is
the argument for leaving it spelled out absolutely.

The client's `baseHref` is a fourth spelling of the segment and lives in another repo. Quinoa mounts
the files; the `baseHref` is what makes `index.html` ask for them at the right url. A mismatch serves
a page whose every asset 404s, and no test here would see it.

Bare `/artifacts` (no trailing slash) is a 301 to `/artifacts/` — Quinoa mounts the SPA at
`/artifacts/*`, which does not match the bare segment on its own, so `webui/WebUiRedirect` serves
the missing spelling (GET/HEAD only, query preserved; a write to the bare segment answers 405).

`/v2` is the exception to the segment rule and it is forced on us: docker and podman resolve an image
reference against `<host>/v2/` and accept no path prefix. The gateway claims it as an *extra prefix*
on its artifacts entry (`QitsService.ARTIFACTS("/v2")`) rather than as a service of its own, so a
deployment still names one host and gets both.

The last three lines are the ones that bite: no config key moves those routes, and no JAX-RS test
covers them. `GitHostTest`, `RegistryTest`, `NpmRegistryTest`, `MavenRegistryTest` and `DaemonRegistryTest`
are the only things that would catch them drifting, which is why all five spell their paths out
absolutely.

Two outbound/inbound addresses are contracts other repos hold:

- `/artifacts/git/<repoId>` and `/artifacts/git/<projectId>/<repoName>` — dialled by qits-ci and by
  qits-workspace-daemon's `Provisioner`.
- `/artifacts/git/<repoId>/blob/<rev>/<path>` and `/artifacts/git/<repoId>/tree/<rev>[/<path>]` —
  the content reads qits-ci's pipeline-config reader uses instead of a local mirror. Both answer
  the resolved commit in a `Git-Commit-Sha` header, **not** an `X-Qits-*` one: the gateway strips
  that prefix unconditionally. `blob`/`tree` are literal second segments and the routes are
  registered ahead of the name-addressed scheme; a clone of a repository *called* blob or tree is
  the one overlap, and the handler `next()`s it back to the router rather than answering it.
- `qits.ci.intake-url` → `/ci/api/events/post-receive` — qits-ci's path, not ours. The notifier
  retries a failed delivery on the `qits.post-receive.retry-delays` schedule and then logs the loss
  at **WARN**, so a wrong value here says so in the log after about three minutes and CI never runs.
  It carries a bearer for `aud=qits-ci` when this deployment has client credentials, and nothing
  when it does not — re-fetched per attempt, so a retry never presents a token an idp cutover has
  invalidated.
- `qits.projects.intake-url` → `/projects/api/events/post-receive` — the same event, same body,
  qits-projects' path. It answers by pushing the repository to its GitHub sync target, so a wrong
  value here is a backup that never happens, announced by the same WARN. It carries no credential,
  and — the one difference from the ci delivery — `-o qits.no-ci` does **not** suppress it: a backup
  is owed even for a push CI ignores. Tags are excluded from both; the tag side of a backup is
  projects' own sweep.
- **The delivery is retried, and that was paid for.** Both consumers get up to five attempts, at
  roughly 5s/15s/45s/2m (`qits.post-receive.retry-delays`, one entry per retry). Any 2xx is success;
  a refused connection and any non-2xx are both retried, because during a bootstrap they are the
  same outage a second apart. Measured twice on two consecutive from-scratch bootstraps: the
  database container is redeployed one phase before the next push, qits-ci's pool is severed
  (`FATAL: terminating connection due to administrator command`), the intake 500s, and with one
  fire-and-forget attempt swallowed at debug the bootstrap then hung an hour on a build nothing had
  queued. Every attempt is off-thread — the hook fires inside `ReceivePack.receive()` before the push
  response is written, so nothing here may block or throw, and that constraint outranks the retry.

## Package and module conventions

Two top-level packages, deliberately kept apart:

- `eu.wohlben.qits.artifacts.*` — the blob store. `artifacts/` holds `entity`, `persistence`, `dto`,
  `mapper`, `control`, `error`; `service/` holds only `api`. Entities are Panache active-record with
  public fields; mappers are MapStruct `@Mapper(componentModel = "jakarta")`.
  - `eu.wohlben.qits.artifacts.gc` and `.gc.dto` (module `gc`) are **garbage collection** — a
    process modelled from within qits-platform-artifacts rather than artifacts domain
    (`artifacts-gc-plan.md`, settlement). A subpackage rather than a sibling top-level name, and
    deliberately **not** `artifacts.control` in a second jar: adapters sharing a package with the
    code they extend is the split package Quarkus' `SplitPackageProcessor` warns about on every
    build, the same trap `githost.persistence` avoids.
  - **The dependency runs one way: `gc` → `artifacts`, never back.** `artifacts` does not know a
    collector exists, which is the property that keeps a retention rule out of the write path. `gc`
    does not depend on `git-storage` either — pack blobs are row-less to the census and structurally
    unreachable by any sweep. `service` depends on all three and hosts GC's only web surface,
    `api/GcPlanController`.
  - Where a strategy needs one of the store's package-private funnels, `artifacts` opens a **narrow
    public door** — `BlobReclaim` (over `BlobStore.delete`/`lastWrittenAt`/`blobGracePeriod`),
    `OciRegistryCollection` (`collectTag`/`collectManifest`), `NpmRegistryCollection` (`collect`) —
    each javadoc'd as the gc module's alone, plus `DaemonRegistryCollection` and
    `MavenRegistryCollection` (`collect`). The funnels
    stay package-private: widening them to public would hand their constraints to every package on
    the classpath to serve one module. `MavenRegistryCollection` opens **three** methods rather than
    one — `collect` for the hosted type, `evictProxiedArtifact`/`evictProxiedMetadata` for the
    cache — and the split is the point: one table holds both maven types' rows, so the doors differ
    by which repository type they refuse.
- `eu.wohlben.qits.githost` — the git host. Mostly `service/`, plus `eu.wohlben.qits.githost.storage`
  in the `git-storage` module. It is **not** folded into `artifacts`: it shares no code with the blob
  store, and keeping the package separate keeps a future second split cheap. It now shares the
  datasource and the Flyway lineage (see "Schema changes"), which is the one part of that sentence
  that changed.
  - `githost.storage` (module `git-storage`) is the DFS storage **engine** and its two declared
    ports. Its only compile dependency is JGit.
  - `githost.persistence` (module `service`) is where those ports are **implemented**, plus the git
    host's entities. It is a separate package name from `githost.storage` on purpose: putting the
    adapters in the same package as the ports makes a split package across two jars, which Quarkus'
    `SplitPackageProcessor` warns about on every build and which buys nothing — nothing here needs
    package-private access.
  - The adapters can only live in `service`. `git-storage` may not depend on
    `qits-artifacts-artifacts` and `artifacts` may not depend on `git-storage` — they are different
    contexts — and `service` is the one module that already depends on both.
- `eu.wohlben.qits.registry`, `eu.wohlben.qits.npm`, `eu.wohlben.qits.maven` and
  `eu.wohlben.qits.daemon` — the four protocol
  wire stacks, `service/` only. Unlike the git host these *do* share the blob store, so the split is
  by layer rather than by context: every byte and every row goes through `artifacts/control`
  (`OciRegistryService`, `NpmRegistryService`, `MavenRegistryService`, `DaemonRegistryService`,
  `BlobStore`), and the
  `service/` package holds routes, error envelopes and — for npm and maven — the outbound upstream
  client. A wire package that touched a Panache repository directly would be the drift to watch for.

`artifacts` carries its own `error/` package (`ArtifactsException` and the four status-carrying
subtypes) rather than the monorepo's `domain/error/*`. It always did — this is one of the few
places where the duplicate-now register in `migration-plan.md` §5 was already satisfied at import.

## The git host's storage

A repository is a JGit `DfsRepository`: its packs, pack indexes, refs and reftables are blobs in
this service's own content-addressed store, listed by `git_pack`/`git_pack_file`. Nothing is on
disk as a repository, and there is no key to pick anything else.

`DfsGitRepositoryProvider` is the one implementation of `GitRepositoryProvider`, and
`GitHostRoutes.open` is the **whole** seam — one method. `infoRefs` and `service` take a
`Repository` and never learn where it came from.

A second backend — bare origins at `<qits.repositories.data-dir>/<repoId>/origin`, on a volume
qits-projects and qits-workspaces also mounted — ran beside this one for a release cycle, selected
by `qits.repositories.git.storage`. Both it and the volume are gone: the property, the data-dir
property, `FileGitRepositoryProvider` and `GitRepositoryBackend` no longer exist, and neither does
the two-way volume contract the Dockerfile used to carry.

Three things about DFS storage that are not obvious and cost time to rediscover:

- **The git CLI cannot open one.** No directory to point `--git-dir` at, no worktree to add, no
  config file to write. Every operation is the wire protocol or in-process JGit — which is the point,
  not a limitation: receive-pack becomes the only writer, so no ref moves without firing
  `post-receive`.
- **`getConfig()` does not persist.** That is why `[qits] protectDefaultBranch` is a row rather than
  a line in a repository's config: the config write is a no-op, so the old read would have answered
  the platform default for every repository with no symptom anywhere.
- **Existence is answered by the ref database, not by a table.** A repository that has been created
  has a reftable in the catalog; one that has not reads empty and is a 404. There is deliberately no
  `git_repository` row to keep in step.

## Adding a dependency on another context

Don't. Declare a port in the package that needs it, inject it as `Instance<T>`, and make absent a
supported configuration with a documented behaviour — see the table in the README.
`RepositoryNameResolver` is the only one, and it is optional because the id-addressed git scheme
predates the name-addressed one and remains the daemon's fallback.

It now ships an adapter of its own, `HttpRepositoryNameResolver`, and that does **not** make it a
third pin-port-style exception: absent is still a supported configuration with the documented
behaviour. Unset `qits.projects.name-resolver-url` returns empty without a call, which is the same
404 an absent bean gives; and the adapter **never throws**, because `GitHostRoutes` has no exception
clause on this port. `@DefaultBean` is what keeps a consuming application — and the test suite's
`FakeRepositoryNameResolver` — able to implement the port instead.

**The two GC pin ports are the exception, and they break the rule in both halves on purpose.**
`CdDeploymentPins` (`GET /cd/api/pins`) and `CiDaemonPins` (`GET /ci/api/daemon`) are ports this repo
also implements, as plain GETs on qits-net, and absent is *not* a supported configuration: they
throw, and a run that cannot read a pin deletes nothing at all. Both halves were decided rather than
drifted into (`artifacts-gc-plan.md` ⚖4 and the settlement), and both live in the `gc` module, which
narrows the exception rather than removing it: the `artifacts` library dials nothing and is
domain-blind again, and the two outbound calls belong to the process that needs them. The keep-sets
are "which image shas would a restart or a rollback pull" and "which daemon would a run launch";
qits-cd and qits-ci are the only things that know, and the alternative — a driver assembling those
lists and handing them in — puts a safety-critical input outside the service that acts on it, where
the two drift and the drift deletes something live. They are fetched **once per run** and never
cached: a cached pin list is a plan on stale facts, and two fetches inside one run can disagree.

**Neither policy is re-derived here.** cd answers with a set of shas per application, and this repo
keeps all of them under one rule. It used to derive "ACTIVE plus the previous distinct sha" from raw
deployment rows, and the derivation was wrong — it stopped at the first older row of any status, so
an `ACTIVE(A) / FAILED(C) / DECOMMISSIONED(B)` history pinned an attempt that never served and
dropped the sha a rollback restores. A keep-set defined twice is a keep-set waiting to disagree.

Never add a JPA relation to another context's entity, and never a foreign key. Blobs address the
world by **string metadata**; the git host addresses it by **repo id string**. Both are in a
different database from whatever they name.

## Schema changes

`artifacts/src/main/resources/db/artifacts/migration/`, hand-written, its own lineage on its own
named datasource. This lineage is the original one from the monorepo, carried over **unsquashed** —
do not renumber it, and do not treat `V1__init.sql` as a squash baseline. Never touch the monorepo's
`db/migration`; that is a different database.

The OCI mirror owns two (V7): `oci_mirror_upstream`, whose slug is a foreign key into
`artifact_repository` because every upstream is paired with the `oci-mirror` row its namespace
resolves to, and `oci_mirror_tag_check`, which the miss path writes — one row per mirrored tag,
moved both by a fetch and by a `HEAD` that found the digest unchanged, and deliberately **not**
moved when the upstream could not be reached (a failed check that touched it would suppress the
next attempt for a whole TTL). The maven repository owns one (V8): `maven_artifact`, path-keyed
with its `size_bytes` beside the blob id — and the pull-through cache (V13) adds **no second
artifact table**, because a cached file is an ordinary `maven_artifact` row under a `maven-proxy`
repository and every reader already attributes by the repository row's type. V13's one table is
`maven_proxy_metadata`, the cached `maven-metadata.xml` with its two validators and `fetched_at`:
the one maven document that mutates, and the one thing that could not be an immutable path or a
derivation over the cached rows. The daemon-binaries type owns one (V10): `daemon_binary`,
keyed `(repository, name, version)` with its `size_bytes` beside the blob id too — those two are the
protocol tables the census sizes without a disk read. The docs type owns two (V12): `docs_site`, keyed
`(repository, name, version)`, and `docs_file`, whose key is that plus the path and whose foreign
key **cascades** — that cascade is what makes a *version* the unit of eviction at the schema level,
so no sweep, bug or hand-written query can leave a site half-collected and serving 404s from a
version that still lists itself. `daemon_binary` deliberately holds **no
prefill**: adopting the ELF blobs already on a deployment's volume is an ops action, because the
lineage must not embed live-platform digests and a migration cannot verify one against the running
store. Access tracking owns two: V9 put a nullable `accessed_at` on `artifact_record`,
`oci_manifest` and `oci_tag`, and V11 put the same column on `npm_version`, `maven_artifact` and
`daemon_binary` — **neither backfills**, because null has to keep meaning "never read" for a sweep
to tell it apart from "read long ago". **A plan reserves no migration number**: three workstreams were
widening this lineage at once, and the rule they share is "take the next free V at land time, and re-enumerate
`ck_artifact_repository_type` from the `RepositoryType` enum as it stands in the tree"
(proxy-pulling-normal-images.md §4). `OciMirrorMigrationTest` pins that by looping over
`values()` — it owns a private file-H2 under `target/` and runs Flyway over the real directory,
because every `@QuarkusTest` here wipes the tables and a prefill is invisible to all of them.

The git host owns **three** tables — `git_pack`, `git_pack_file` (V4) and `git_repository_protection`
(V5) — in this same lineage, on this same datasource. It used to own none, and this file said so
flatly; the sentence was amended rather than left standing when the second storage backend landed
(`git-host-storage-unification-plan.md`, decision ⚖5). The alternative was a second named datasource
with its own H2 file and its own Flyway config, for three tables that produce three rows per push
against a 746 MB database. Nothing here is a foreign key into another context, and the rule above it
is unchanged: the git host addresses the world by repo-id **string**.

Their entities live in `service`, under `eu.wohlben.qits.githost.persistence` — not in `artifacts/`.
`service/src/main/resources/application.properties` names that package in
`quarkus.hibernate-orm.artifacts.packages`, which **replaces** the value the artifacts jar ships
rather than extending it, so `eu.wohlben.qits.artifacts.entity` is spelled there too. It is spelled
in `service` and not in the jar's `microprofile-config.properties` because that package is not on
`artifacts/`'s classpath at all.

### We do not garbage collect git

Not "we have not got round to it" — a recorded decision with a number beside it
(`git-host-storage-unification-plan.md`, ⚖2). Nothing frees a blob, so a repack does not reclaim
space, it **duplicates**: `DfsGarbageCollector` writes the new pack, the packs it replaced lose their
catalog rows, and their bytes stay forever. Measured on the platform's largest real repository, one
run took it from **7.8 MB to 15 MB** — against 8.4 MB for the bare it replaced.

The accepted cost instead is roughly three blobs and three rows per push: about **75 blobs per active
repository per year** at the measured rate, against a blob store already past 5 GiB. It is written
down because the one thing that must never happen is someone running a repack to save space. The
posture is repeated in `V4__git_pack_catalog.sql`, in `GitPack`'s javadoc and in
`git-storage/README.md`, and `GarbageCollectionTest` asserts the amplification rather than hiding it.

`BlobStore` **now has a delete, and the sweep now calls it** — only behind
`POST /artifacts/api/gc/sweep` (see "Garbage collection" below) — and this paragraph still holds,
which is worth being precise about: a repack still duplicates, the measured 7.8 MB → 15 MB stands,
and the git pack blobs are row-less to the census and therefore untouchable — no sweep can reach
them. Pack GC is its own later workstream and needs the DFS migration first.

## Garbage collection

All of it is the **`gc/` module** (`eu.wohlben.qits.artifacts.gc`, DTOs in `.gc.dto`) except the
route: `api/GcPlanController` stays in `service` with every other route. `README.md`'s "Garbage
collection" section is the contract; these are the rules that get "helpfully" refactored away.

- **One census.** `LiveBlobCensus` is what the store summary reads *and* what a GC plan reads. A
  second computation of "what is live" is a set the UI reports and a set a sweep protects, drifting
  until a sweep deletes something the page called referenced. `ArtifactExplorerService.storeSummary`
  delegates to it; the explorer's own tests are the byte-exactness proof.
- **Per repository is a FILTER over the one plan, never a second planner.** `GET /gc/repositories`
  and `GET /gc/repositories/{repo}/plan` exist, and neither applies a rule of its own: a
  repository's figures are `GcStrategy.Plan.scopedTo(name)` — its share of its type's plan —
  reconciled against the same census. That is sound because **no rule of either engine reaches
  across two repositories**: both own-engine adapters qualify their identity group with the
  repository name (`OciImagesGcAdapter.group()` is `repository + "/" + image`), and the cache engine
  is per candidate. A second planner would be a second policy over one type, which is the mistake
  the whole design refuses. The one subtraction that must never happen is spelled in `scopedTo`'s
  javadoc and pinned by `GcScopedPlanTest`: a blob condemned in repositories A **and** B stays
  **retained** in each scoped plan, because the other one's row is standing in that view. So
  `blobsRetained` is widened, never `blobsReleased` narrowed — and the scoped plan still states the
  type's whole post-plan live set, which is what lets `BlobSweep` consume it unchanged.
- **A scoped sweep narrows what is planned, never what is protected.**
  `POST /gc/repositories/{repo}/sweep` deletes that repository's identity rows through the same
  per-type funnels — the adapters needed no change, because every `GcIdentity` carries its
  repository and their delete loops already dispatch on it — and then runs the *same* whole-store
  blob reconciliation with only that plan applied. Three belts, none of which knows what a
  repository is. Two rules that look like candidates for a per-repository relaxation and are not:
  the **whole-run abort** on an unreadable pin source holds identically at repository scope (one
  repository is not a smaller blast radius at the blob layer — its released bytes can be the last
  local reference to content a pin names by digest), and a repository whose type nobody collects
  answers a **receipt with its reason and zeros**, not an error status. The one ordering
  difference from the whole-store run is deliberate: the *name* is resolved before the pins are
  read, because a repository that does not exist is a fact about the request and an aborted receipt
  for it would claim a run was attempted.
- **The per-repository figures do not add up, and that is the honest answer.** Σ(per-repo) ≤ the
  global sweep figure: a blob two repositories both condemn dies in a whole-store run and in
  neither scoped one. Proportional attribution was rejected — a fraction of a blob frees nothing,
  and it would be the first number in this feature matching no operation anyone can run. Both
  figures ship per repository: `structural` (no grace — what the rule condemns) and grace-applied
  (what a run now would unlink, plus what the window withholds). The listing column shows the
  structural one, matching `GcTypePlan`'s per-type semantics.
- **Row-less blobs are untouchable, structurally.** A blob becomes a candidate only by *losing* its
  last identity row to a strategy's own deletion, so one that never had a row cannot be reached. This
  is not an allowlist and must not become one: 124 MiB of this store has no row, and one of those
  blobs is the CI daemon binary every build downloads by digest.
- **Two engines, and this supersedes the "no shared policy" rule.** Settled by user decision
  2026-08-05 (`artifacts-gc-plan.md`, "Settlement"). The rules live in `CacheEvictionStrategy` (a
  cache holds re-fetchable content, so everything unaccessed past the window goes) and
  `OwnArtifactsStrategy` (own artifacts keep the last 2 released versions per identity group, the
  rest ages out), mapped onto types by configuration. **The superseded rule was "one bespoke
  strategy per type, no shared policy code, no retention-rule framework"** — it is history, and
  re-splitting an engine back into per-type rules is now the wrong change, in the same words the
  old rule used against merging them. Two things it said still hold and are not softened: a type
  has exactly **one** policy (two beans claiming it is a collision, reported and never merged), and
  the per-type *facts* stay in that type's own `GcTypeAdapter` — no engine may switch on
  `RepositoryType`, no adapter may carry a window or a keep-count.
- **The grace window gates identity rows, not only blob unlinks.** A blob can only be swept by
  *losing* its last row, so a row deleted while the blob's file is inside the window would strand
  the blob — row-less, untouchable, never reclaimed. `GcStrategy.apply` therefore withholds an
  identity whole when any blob it releases is still in grace; `GcSweepExecutor` (behind
  `POST /artifacts/api/gc/sweep`) applies only plans it computed in the same request, and on a
  store younger than the window a sweep provably deletes nothing.

- **Live pins are read once per run, and a source that cannot answer aborts the whole run.**
  `GcPinSources` reads qits-cd (`GET /cd/api/pins`) and qits-ci (`GET /ci/api/daemon`) at the start
  of every plan and every sweep, never cached, and folds them into one `GcPins`. `GcSweepExecutor`
  returns a receipt with `aborted` and deletes nothing — before the census — when any source failed;
  this replaces the old per-type fail-closed for the sweep. The rule is all-or-nothing because blobs
  dedupe globally: a tarball one type releases may be the last reference to bytes a pinned image also
  names. `GET /gc/plan` must **never** 500 on it — it answers `executable: false` with
  `pinFailures` and the pin-dependent types refused.
- **Two pin semantics that look like bugs if you "fix" them.** A blank `daemonVersion` is an
  *answer* meaning "no daemon is pinned" (the shipped default) and must not abort a run; a 64-hex
  daemon version pins the **blob** at that digest as well as any row, because the pin has been a
  sha256 digest since the daemon shipped and may name bytes no row exists for.
- **The pin config keys are `qits.artifacts.gc.pins.cd-*` and `.ci-*`**, renamed from
  `qits.artifacts.gc.oci.cd-*`, and they live in the `gc` jar's own
  `META-INF/microprofile-config.properties`. A deployment carrying the old spelling silently loses
  the value.
- **The dry-run report is the review surface, and four of its parts are load-bearing.** `summary`
  is first in `GcPlanReport` because it is what a human reads first — executable yes/no, the
  reclaim in bytes and in units, one line per type — and it is **derived** by `GcSummary` from the
  rest of the report, never re-computed: a summary that decided for itself what would die would be
  a second policy. `pins` (`GcPinSource`, built in `GcPinSources` and carried on `GcPins`) gives
  each source its url, outcome, duration, pin count and the keep-identities it produced, and the
  **sweep receipt carries the same section**, an aborted one included. Excluded types say
  `GcRules.EXCLUDED_NOTE` on their own line as well as in the configuration echo — `dead: []`
  beside a claimed strategy otherwise reads as a rule that ran and found nothing. And the npm-proxy
  H2 line rides in that type's `note()`, so it reaches both the type entry and the summary line
  where the `0` it explains is printed.
- **Both engines are live over every configured type.** The settlement (`artifacts-gc-plan.md`,
  2026-08-05) replaced the bespoke strategies with `CacheEvictionStrategy` + `OwnArtifactsStrategy`,
  mapped onto types by `qits.artifacts.gc.type.<wire-name>.strategy|window` (`GcTypeConfig`).
  `oci-mirror`, `npm-proxy` and `maven-proxy` run the cache engine; `oci-images`,
  `daemon-binaries`, `npm-packages`, `maven-packages` and `docs` run the own engine; only the two CI
  types are `excluded`, and
  that is a decision rather than a gap. `GcTypeConfigTest` is the guard, and it is edited
  **deliberately, once per workstream**: the moving types' new dead sets are written out there, and
  every other type stays identity-for-identity as it was.
- **The two binders are `CacheGcStrategy` and `OwnGcStrategy`; read one before touching a live
  type.** Both are wiring, not policy: read the configured window, refuse if a deployment
  reconfigured the type onto the other engine, hand the engine a `GcPinned`, route `apply` back to
  the adapter. **Every type on either engine declares `readsPins()`** — a pin can name a **blob** by
  digest and blobs dedupe globally, so a run with an unreachable qits-cd or qits-ci reports them
  refused rather than planning them against "nothing is pinned". `OwnGcStrategy` composes two
  keep-classes in order: `GcTypeAdapter.pinnedBy` (the type's own coordinates) first, the digest
  check as the floor under it.
- **`GcTypeAdapter.pinnedBy` takes the whole enumeration, and that is not an accident.** A
  coordinate pin is often a fact about a *group* — "this image's newest build" cannot be answered
  from one tag — and reading the rows once per run is what keeps a plan judged against one snapshot.
  `OwnArtifactsStrategy` has an overload taking the candidates for the same reason: the binder
  enumerates once and passes the list on.
- **The newest-build-tag belt is `oci-images`' one derived pin, and it reads `updated_at`.** It is
  the pull the *next* deploy will make, which qits-cd cannot answer for because it has not happened
  — the whole safety net for an image cd has never deployed (`qits-spa-home`, measured). Reading the
  candidate's access time instead would disarm it exactly where it is needed: a cold, never-deployed
  image is the case it exists for.
- **A manifest under a tag a run condemns is collected on the NEXT run.** A tagged manifest's
  identity is its tag and an index child rides on the index's closure, so neither is a candidate of
  its own — the mirror's rule, now `oci-images`' too. The bytes are safe in the meantime because the
  sweep's pre-unlink re-census sees the surviving row; what runs a run ahead of reality is the
  dry-run's per-type figure, not the store.
- **`maven-proxy`'s identity is a PATH, and that is deliberately NOT `maven-packages`' rule.** The
  hosted type folds a version's files into one coordinate because half a published version is a
  broken resolve nothing can repair; a cache repairs itself on the next request, so there is no
  half-version to prevent and a coordinate unit would only withhold cold files because one sibling
  is warm. Its second identity is the cached document (`<path> (metadata)`, a public spelling
  because it is on the wire), whose staleness folds in the access of the files under its directory —
  the packument rule restated, and wrong without the fold in exactly the same way.
- **`maven-packages`' identity is a COORDINATE, not a row.** A version is a set of files, and half
  a version is a broken resolve, so `MavenPackagesGcAdapter` folds rows into
  `groupId:artifactId:version` (timestamped snapshots into their own resolvable coordinate) and the
  grace window withholds the whole set. Its one derived belt is **the newest deployable set of every
  snapshot line** — what `maven-metadata.xml` redirects `1.0.1-SNAPSHOT` to; deleting it would point
  the document at a file the store no longer has. No N-per-line rule was invented: §3.6 named the
  shape and never priced it, so the window decides.
- **`daemon-binaries` has no tombstone and must not grow one.** npm has one because a deleted
  version re-opens its name under somebody's lockfile; a daemon version is resolved by a pin a
  bootstrap re-reads, so a re-release at a collected version is legitimate. Its funnel is
  `DaemonRegistryCollection` → package-private `DaemonRegistryService.collect`, the fourth narrow
  door. Adopted rows carry the **digest hex** as their version, so the adapter's version order ranks
  those below every calver one — comparing 64 hex characters as a number ranks the oldest thing
  there as the newest.
- **Proxy eviction writes NO tombstone, and that is the point.** `NpmRegistryCollection.collect`
  (hosted) writes one because a published version's name is spent forever; `evictProxiedVersion` /
  `evictProxiedPackument` (proxy) write none, because re-fetching the version from upstream is what
  the cache is for. One table holds both kinds of row, so the eviction doors check the repository's
  **type** and refuse anything but `npm-proxy` — that check is what makes "no tombstone" safe.
  `MavenRegistryCollection.evictProxiedArtifact` / `evictProxiedMetadata` are the same three
  sentences over `maven_artifact` and `maven_proxy_metadata`, refusing anything but `maven-proxy`.
- **A packument's staleness folds in its versions' access.** `fetched_at` alone says when the
  *document* was last revalidated, which a TTL moves on its own; judging on it would evict the
  document of a package something installs weekly. And evicting one frees **no disk** — the
  documents are H2 CLOBs, so the type's `note()` carries the character count beside the zero, and
  the file shrinks only under the `SHUTDOWN COMPACT` maintenance restart documented in the README.
  Nothing in this service runs that.
- **Engines hold rules, `GcTypeAdapter` holds facts, and neither may grow the other's half.** No
  engine may switch on `RepositoryType`; no adapter may carry a window or a keep-count. The facts
  are: what identities exist, what a release is here, which of two is older, and how a row is
  deleted. Effective access time is `max(created/published/fetched, accessed_at)` — creation counts
  as the first access, folded in by the adapter — so a freshly published artifact is young rather
  than never-read.
- **Pins are a keep-class checked before the access rule** (`GcPinned`), and the rule comes back as
  a sentence so the report names which service saved an identity. A pin is the one fact no timestamp
  implies: a container running untouched for months still pulls its image sha on restart.
- **Every `RepositoryType` needs a configuration entry.** `GcTypeConfig.of` refuses rather than
  defaulting to `excluded`, so adding a constant means adding two lines to the `gc` jar's
  `META-INF/microprofile-config.properties`. The mapping's prefix is `qits.artifacts.gc.type`, not
  `qits.artifacts.gc`: a mapping rooted at the wider prefix would claim `blob-grace-period` and the
  pin urls, which other classes read.

Ten strategy classes exist, one per type, and **eight of them are thin binders rather than rules** —
`OciMirrorGcStrategy`, `NpmProxyGcStrategy` and `MavenProxyGcStrategy` on the cache engine,
`OciImageGcStrategy`, `DaemonBinariesGcStrategy`, `NpmPackagesGcStrategy`,
`MavenPackagesGcStrategy` and `DocsGcStrategy` on the own engine, each naming its `*GcAdapter` and
nothing else. A class that is four lines long is doing its
job; a rule appearing in one is the settlement being unpicked one type at a time. The two CI stubs
(`CiScreenshotsGcStrategy`, `CiVideosGcStrategy`) are the exception and are deliberately two
classes, because their intended rules diverge in kind (branch-scoped against byte-budgeted) and one
shared base for two unimplemented rules would decide their shape before either was designed. Both
types are `excluded` in configuration, the stubs plan `nothingDies` at zero rows under a `note()`
naming the intended rule, and they **fail closed the day rows exist** — a plan over rows with no
implemented rule is a guess. A few things the strategies share cost time otherwise.

- **All of them are `@Singleton`, not `@ApplicationScoped`, and the reason is the report.** `GcPlanner`
  names a strategy by its class's simple name, and a normal-scoped bean would answer that through its
  client proxy — `OciImageGcStrategy_ClientProxy`, a name in no source file. `@Singleton` is a
  pseudo-scope, so there is no proxy and still one instance. `GcPlanner.nameOf` also unwraps a proxy
  if it gets one, so a strategy that forgets is merely inconsistent rather than misreported; both
  names are asserted.
- **No strategy reads the census any more.** The census carries blobs, not identities, so every
  rule is computed from the type's own rows — `oci_tag`/`oci_manifest` plus `OciManifestFootprints`
  for one, `npm_version`/`npm_dist_tag` for the next, `maven_artifact` and `daemon_binary` for the
  other two. The two blob sets they return are in the census's vocabulary, which is what the
  substrate reconciles over, and each suite asserts `blobsRetained` equals the census's own live set
  for the type when nothing dies.
- **No strategy performs an HTTP call any more** — every type on an engine declares `readsPins()`
  and is handed the run's `GcPins`. See "Adding a dependency on another context" above; the suites
  point `qits.artifacts.gc.pins.cd-base-url` and `.ci-base-url` at a closed port, so `GcPinsTest` and
  `GcPlanControllerTest` assert the refusal path rather than avoiding it. Only the two CI stubs plan
  on a run whose pins failed, which is what keeps such a report readable at all.

Two things are npm's alone, and the plan is explicit that docker needs neither:

- **The republish tombstone (V6, `npm_version_tombstone`).** Version immutability is enforced by
  looking for the row, so deleting a version re-opens its name for a publish with different bytes.
  `NpmRegistryService.publish` checks the tombstone as well as the row and answers a 403 that says
  *garbage-collected*, not *immutable* — a pusher told "immutable" goes looking for a version that is
  not there. A separate table rather than a flag on `npm_version`, because the packument is assembled
  from those rows at read time and a marker column would need a `where` clause in every reader.
- **`NpmRegistryService.collect` is package-private with exactly one caller**,
  `NpmPackagesGcAdapter.delete`, which reaches it across the jar boundary through the
  `NpmRegistryCollection` facade: it is the only way a version row is ever removed, it writes the
  tombstone in the same transaction, and it refuses a version a dist-tag still names (a dist-tag
  pointing at a version the packument no longer lists is a broken package to every npm client). It
  shipped ahead of its caller so the tombstone was never a step someone had to remember.
  The proxy's twins are `evictProxiedVersion`/`evictProxiedPackument`, which write **no** tombstone
  and refuse a repository that is not `npm-proxy` — see the cache bullets above.
  `DaemonRegistryService.collect` is the daemon twin behind `DaemonRegistryCollection`, and it
  deliberately writes **no** tombstone.
  `OciRegistryService.collectTag`/`collectManifest` are the OCI twins — package-private behind
  `OciRegistryCollection`, called by `OciImagesGcAdapter.delete` and by `OciMirrorGcAdapter.delete`
  (nothing in them reads a repository's type, so both OCI types always came through the same door),
  and `collectManifest` refuses a manifest a tag still names. `collectTag` also deletes the tag's
  `oci_mirror_tag_check` row: an auxiliary row cleaned inside the funnel cannot be forgotten by a
  caller.

`oci-mirror` **evicts** now. Its old rule — "nothing dies, append-only pending access tracking"
(proxy-pulling-normal-images.md ⚖2) — was a decision with a condition attached, and the condition is
discharged, so the pin its suite held was replaced deliberately rather than eroded. Two facts of that
adapter cost time otherwise: a manifest a tag names is never a candidate of its own (its tag is its
identity), and a child of a kept index *is* one — evicting an architecture nobody pulls is the
lazy-pull bargain, not corruption, and its bytes survive through the index's closure anyway.
No strategy is left whose whole rule is "nothing dies" — `maven-packages` was the last, and the
settlement priced it with every other own type.

`npm-proxy` is claimed by `NpmProxyGcStrategy`. It shares `npm_version` with the hosted registry, so
the scope is filtered by the repository row's **type** and asserted from both sides
(`NpmPackagesGcStrategyTest`, `NpmProxyGcStrategyTest`) — a leak in either direction is the one
mistake these two types can make. Its second identity is the cached packument
(`<package> (packument)`, a public spelling because it is on the wire). `maven-proxy` /
`MavenProxyGcStrategy` is that hazard verbatim over `maven_artifact`, asserted from both sides by
`MavenPackagesGcAdapterTest` and `MavenProxyGcStrategyTest`.

`BlobStore.delete` is package-private for the same reason `promote` is the one write funnel: the
constraints (grace window off the file mtime, the pre-unlink guard inside the write lock `promote`
also takes, `BlobDiskIndex` invalidation) only hold if there is one way in. Adding a second caller,
or widening it to public, removes them without failing anything. The `gc` module reaches it through
`BlobReclaim`, and its two siblings `OciRegistryCollection` and `NpmRegistryCollection` do the same
for the registries' collect funnels — three named doors with one documented owner each, which is why
none of the three funnels had to become public when GC moved out.

## Authentication

User authentication happens at `qits-gateway`. This service resolves a principal from a trusted
header (`X-Qits-User`, read by `ForwardAuthMechanism` in the published `qits-auth-core` jar) and
authenticates no user itself.

**`identity.isAnonymous()` is not a security state** — it means "no name for the audit row". A check
of the form `if (identity.isAnonymous()) deny` would look like a security control and be worth
nothing, because reaching this service at all already implies you are inside the trusted network.

There is no auth variant to select and no authorization policy here, and roles are deliberately not
resolved — the single role check the system has (`qits.auth.required-role`) is the gateway's. See
`migration-auth-plan.md`.

## Tests

- `mvn verify` runs 580 tests (116 in `artifacts/`, 18 in `git-storage/`, 135 in `gc/`, 311 in
  `service/`) in about three minutes — counted from the surefire reports, which the previous figure
  here was not: it claimed 617 with 157 in `artifacts/`, and that module has counted 116 for a while.
  Nothing here
  needs docker — and that is the constraint that shapes the registry suite: `docker`, `podman` and
  `skopeo` may not be assumed present, so `registry/OciClient` + `registry/TinyImage` synthesise a
  real image in memory and drive a full push/pull over the JDK `HttpClient`. It uses that rather than
  RestAssured for one reason that matters: `BodyPublishers.ofInputStream` sends a body with no
  `Content-Length`, which is the chunked path docker actually uses and the one the wire ceiling does
  not gate. RestAssured also percent-encodes the colon in a digest, so any assertion carrying one
  needs `urlEncodingEnabled(false)` or it tests RestAssured rather than the registry.
- The npm suite is the same shape and the same rule: `npm/TinyPackage` + `npm/NpmClient` synthesise
  a real gzipped tarball and a publish document and drive the round trip. RestAssured is unusable
  for the packument routes specifically — it re-encodes a path, and the whole question there is
  whether `@qits%2fangular` reaches the router with its escape intact. **There is no network**, so
  the proxy suite runs against `npm/StubNpmRegistry`, an in-process JDK `HttpServer`; a test that
  reached real npmjs would not merely be slow, it would pass *wrongly*, since npmjs answers 404 for
  a synthetic package exactly as a misconfigured proxy does. That stub is driven over HTTP rather
  than by touching its fields, and the reason is worth knowing before writing another one: Quarkus
  instantiates a `QuarkusTestProfile` in **two** classloaders, so a static singleton exists twice
  and the application ends up talking to a different instance than the test configures.
- **The maven proxy suite is the npm proxy's shape verbatim**, and for the same two reasons:
  `maven/StubMavenRepository` is an in-process upstream, because a test that reached repo1.maven.org
  would fail offline and pass *wrongly* (Central 404s a synthetic artifact exactly as a
  misconfigured proxy does), and because counting upstream requests is what every caching claim
  rests on. It carries a `RUN` salt for the mirror suites' reason — nothing wipes
  `target/artifacts-svc-test-blobs`, so reused bytes make a fetch a blob-store hit and the count
  comes out one short. Two classes, split by TTL exactly as npm's are: `MavenProxyTest` at the
  shipped hour (the hit cases, the derived-checksum case, the publish refusal, the census
  attribution) and `MavenProxyMetadataTest` at `PT0S` (revalidation with either validator,
  serve-stale, a new upstream version).
  **The strongest case in the pair is the one where upstream's checksum is deliberately WRONG**: a
  proxy that derived checksums locally would answer the jar's real hash and pass every other test in
  the file, so hosting a mismatching `.sha1` is what proves the client's verification is still end
  to end.
- The maven suite is the hosted half of that shape again: `maven/TinyArtifact` synthesises a real
  jar in memory and `maven/MavenClient` drives the deploy/resolve round trip over the JDK
  `HttpClient` — no RestAssured, no maven binary, no network, because the path grammar and the
  encoding questions are the point. `MavenSnapshotTest` drives the ⚖1 flow the way a real client
  does: timestamped files PUT as ordinary paths, then the derived version-level metadata read back.
  Both name their own artifact per case, because the service module's suite has no table reset and
  releases are immutable — a shared coordinate would be order-dependent in exactly the way the
  registry exists to refuse.
- **The OCI mirror suite is that shape again with one extra hazard, and the hazard is why the
  suite's default upstream is a closed port.** `registry/StubOciRegistry` is the in-process registry
  the miss path is a mirror *of*, reached through `qits.artifacts.oci.mirror.endpoint-override`.
  Unlike npm's single configured upstream, the mirror's upstreams are **prefilled rows naming real
  public registries** — quay.io, Docker Hub, Red Hat — so without that key pointed somewhere safe
  any test touching `/v2/quay/…` would dial the internet and pass or fail for reasons unrelated to
  this code. `src/test/resources/application.properties` points it at `http://localhost:1`; a suite
  that wants the stub opts in by profile. Two further rules that each cost real time to rediscover:
  - **Every claim this cache makes is a claim about upstream request counts**, so assert counters,
    not bytes. A test that only checked the content came back passes identically against a proxy
    that caches nothing.
  - **Fixture content must be unique per RUN, not merely per test.** `clean-at-start` wipes the
    tables once per run, but nothing ever wipes `target/artifacts-svc-test-blobs`, and blobs dedupe
    globally and content-addressed. Reuse an earlier run's image content and its layer is already on
    disk — a blob-store hit, so the fetch count comes out one short with nothing in the failure to
    say why. Both mirror suites carry a `RUN` salt for exactly this, and it is the one thing to check
    first if a count is off by one.
- **`endpoint-override` redirects every upstream, which is why the derivation needs its own test.**
  An upstream's address is derived from its domain (`MirrorEndpoints`: `https://<domain>`, with
  `docker.io` → `registry-1.docker.io` as the one well-known hop) and there is deliberately no
  per-domain endpoint config. With every upstream pointed at one stub, the wire suites would pass
  just as well if the derivation were a single hardcoded host — so `MirrorEndpointsTest` is a plain
  JUnit test over the three prefilled domains, and it is what makes "table-driven" a measurement.
- `mvn verify -Dnative` runs those, then 23 more against the compiled binary: `PackagedProcessIT`
  (21) and `ProtectedGitHostIT` (2). They are two classes because they are two process
  configurations — `PackagedProcessIT` asserts the SHIPPED defaults leave the default branch
  unprotected, and the seatbelt cases need it on — and `@TestProfile` is per class. The protection
  cases used to turn it on per repository with one `git config` on the served bare; the override is
  a row now, and a packaged process owns its H2 exclusively with `clean-at-start`, so no test
  outside it can write that row. There is no HTTP verb for it (workstream AT's), which is why the
  platform switch is what these two flip.
  It is the only suite that starts a **process** rather than an in-JVM Quarkus, so it is where the
  route stacks are proved to coexist and where JGit is proved to have survived the compile.
  It is also the **only** place the web UI can be tested at all: Quinoa logs "Quinoa is disabled by
  default in tests" and registers neither the static resources nor the SPA re-route, so a
  `@QuarkusTest` asserting anything about `/artifacts/` passes against a process that has no client
  in it. Two of them are that, and they are the guard on
  `quarkus.quinoa.ignored-path-prefixes`.
  Its `@TestProfile` points the datasource and the blobs dir under `target/`,
  passed to the launched binary as `-D` flags; it uses a **file** H2 of the same shape the
  deployment runs, not the unit suite's in-memory one, because the file/embedded shape is the thing
  that broke. Do not add a build-time property there — an IT cannot re-augment.
- `OciConformanceIT` runs the **upstream** OCI distribution-spec suite against the packaged process,
  and it is the only test here that can falsify this repo's own reading of the spec — `RegistryTest`
  and `PackagedProcessIT` drive a client written from that same reading. It is gated on
  `-Doci.conformance-binary=<path>` and **skips** without it, which is load-bearing: `-Dnative`
  flips `skipITs`, so a failing gate would make a native build need Go. See README, "Conformance",
  for how to build the binary and why three capability flags are declared `false`. It currently
  reports 586 run, 0 failures.
  Its first run found **two genuine non-conformances**, both since fixed, and both worth knowing as a
  pattern: each was a deliberate design decision whose consequence was the wrong status code — an
  out-of-order final chunk `PUT` answered 400 instead of the mandated 416, and an invalid digest in a
  manifest reference missed the route and answered 404 instead of 400. Neither was reachable by
  `RegistryTest`, because that suite drives a client written from the same misreading. **The fixes are
  guarded by unit tests, not by this IT** — it is opt-in and needs Go, so anything it proves must also
  be provable by `mvn verify` alone or it is not actually guarded.
- The git host's protection cases are **three** `@QuarkusTest` classes, not one, and the split is
  forced: `qits.repositories.git.push-token` configured / configured-empty / unset are process
  configurations, and a `@TestProfile` is per class. `GitHostTest` runs under the SHIPPED config
  (protection off, token unset) and carries no profile at all; `GitHostPushTokenTest` and
  `GitHostEmptyPushTokenTest` carry the token cases. `GitHostFixture` is the shared git CLI driver —
  static, because the three classes cannot usefully share a base class. Note that SmallRye reads a
  configured-empty value as *absent* for an `Optional<String>`, which is why the hook must treat
  unset and empty identically rather than distinguishing them.
  `GitHostTest` used to be an abstract `GitHostSuite` with one subclass per storage backend; there
  is one backend, so it is one class again.
- **`GitHostTest` reads no directory, and that constraint outlived the second backend.**
  A repository is provisioned through `GitRepositoryProvider` and every fact about it is then asked
  over the wire — `git ls-remote`, not `git rev-parse` in the served bare. There is no bare to read:
  a `DfsRepository` has no directory for the git CLI to open, by design. Two consequences worth
  knowing before extending it: protection is turned on through `RepositoryProtectionStore`, not by
  writing a repository's config; and an annotated tag is proved annotated by the `<ref>^{}` line in
  the advertisement rather than by `cat-file -t`. `ls-remote` **filters that peeled line out** when
  the pattern is the exact ref name, because it matches patterns against ref names and `<ref>^{}` is
  not one — the fixture globs.
- **Tag pushes are measured, not assumed** (`GitHostTest`, the "tags" block, and one native case).
  Four answers other repos build on:
  - An annotated tag push is **accepted** with protection on and no push option. `ProtectedRefHook`
    guards one ref name — the repository's `HEAD` — so a tag is just another ref to it.
  - One `git push` with `HEAD:refs/heads/main` **and** `<tagobj>:refs/tags/<v>` arrives as **one**
    receive-pack: one pre-receive, one post-receive, one set of push options. Asserted by counting
    the POSTs under `GIT_CURL_VERBOSE`, which is the only way to tell it from two pushes.
  - **`--atomic` works**, which JGit's file-backed ref store does not suggest: the advertisement
    offers `atomic` and a branch the hook refuses takes the tag down with it. Without the flag the
    tag lands anyway and outlives a release that never happened.
  - A non-forced push over an **existing** tag ref is refused (`already exists`) — the version
    uniqueness guarantee. The refusal is the git CLI's, off the advertisement: this host allows the
    move under `--force`, because JGit's `receive.denyNonFastForwards` default is off. So a release
    push must never pass `--force`, and it should pass `--atomic` — otherwise a duplicate version
    rejects the tag while its merge commit lands on main.
- `GitHostFixture.seedOrigin()` asks `GitRepositoryProvider` for an empty repository and pushes one
  commit into it over the served endpoint. `PackagedProcessIT.seedOrigin()` does the same against
  the binary through `PUT /artifacts/git/:repoId` — no in-JVM bean there — and both ITs read every
  ref back with `ls-remote` rather than in a directory. Tests that need the name-addressed scheme
  register the alias on `FakeRepositoryNameResolver`, a plain `@ApplicationScoped` bean in test
  sources. It **overrides** the shipped `HttpRepositoryNameResolver`, which carries `@DefaultBean`,
  so the test classpath still holds exactly one bean and no test dials qits-projects. The HTTP
  adapter is proved on its own by `HttpRepositoryNameResolverTest` — plain JUnit against an
  in-process `HttpServer`, because under CDI the Fake is what you would get.
- `ArtifactsTestSupport` (in `artifacts/`) and `ArtifactsTestMedia` (in `service/`) are separate on
  purpose: the modules share no test classpath, the same way they do not in the monorepo. `gc/`'s
  `GcFixture` and `artifacts/`'s `SeededStoreFixture` are the same rule and the same seeding, copied
  rather than shared — the alternative is a published test jar and a package-private support class
  widened across a jar boundary, which couples three suites to one classpath to save a seeding
  method. Either copy may grow the cases its own module needs; neither is the other's contract.
- **The explorer's size math is proved in `artifacts/`, its wire behaviour in `service/`, and the
  split is not cosmetic.** `ArtifactExplorerServiceTest` builds two images over five content blobs
  arranged so per-tag, per-image and store-wide unions all give *different* answers over the same
  content, plus one blob no row references — because a bug that sums where it should merge produces a
  number that still looks plausible, and only arithmetic that names the double-counted layer catches
  it. `ArtifactBrowseControllerTest` proves the two names in this service that contain a slash (an
  OCI image name, a scoped npm package) resolve in both spellings, encoded and literal, which is a
  property of the path templates and of nothing else. Both must hold; neither implies the other.
- The suite points `qits.ci.intake-url` **and** `qits.projects.intake-url` at closed ports, so a
  push test passes without a receiver; the notifier never blocks the push and the delivery is simply
  lost. It also shortens `qits.post-receive.retry-delays` to a single 50 ms retry, and that is a
  genuine test-only need rather than a drifting copy: the shipped schedule would leave three minutes
  of pending timers per push in a suite that runs in three. Each lost delivery logs one WARN in a
  green run — the change working, not a failure.
  `PostReceiveNotifierTest`, `CiPostReceiveBearerTest`, `PostReceiveRetryTest`,
  `GitHostNoCiOptionTest` and
  `GitHostProjectsIntakeDownTest` are the five that do assert deliveries, against `StubIntake` —
  which plays qits-ci's intake, qits-projects' intake and qits-platform-idp's token endpoint at once, counts
  the two intakes separately (the fan-out's whole point is that the counts differ under
  `-o qits.no-ci`), and passes everything it observed through system properties because a
  `QuarkusTestProfile` is built in two classloaders. Either intake can be told to **refuse** its next
  few requests, which is how `PostReceiveRetryTest` plays the outage: it counts attempts as well as
  deliveries, because the claim is that an event survives an outage *exactly once* rather than
  arriving twice.

## What not to "fix"

- `AdminWriteGuard` matches on `getUriInfo().getPath()` against a **set** of prefixes —
  `repositories`, `store`, `gc` and `mirror-upstreams` — relative to the JAX-RS base, so it holds
  whatever `quarkus.rest.path` is. It was `artifacts` until the resource `@Path`s dropped that segment (the gateway segment
  carries it now). **A resource added outside those prefixes is unguarded** — extend the set, do not
  assume it is covered. `store` holds only the read-only store summary today and is listed so that
  stays a choice rather than an accident. It guards writes only, by design, and is a no-op while the
  rollout gate `qits.auth.machine.required` is off.
  **It is JAX-RS, so it runs on no raw Vert.x route** — adding a prefix for one would look exactly
  like guarding it and guard nothing. **All four wire surfaces are unguarded on purpose** (qits-net
  trust): `/v2`, `/artifacts/npm`, `/artifacts/maven` and the daemon publish `PUT`. The daemon one
  is the one to expect a proposal about, because it carries the platform's own executables — a
  `DaemonPublishGuard` calling `HttpAuthenticator.attemptAuthentication` under the `MachineAuth`
  keys existed for one commit and was removed as a decision, not a simplification. What replaces
  write auth is what replaces it on the other three: versions are immutable (`409` on republish), so
  an open publish can add a version and never change one, and consumers pin the digest the route
  echoes. **No publish surface is gated piecemeal** — machine auth arrives wholesale with qits-platform-idp,
  for all of them at once. `RegistryOpenPushTest`, `NpmOpenPublishTest` and `DaemonOpenPublishTest`
  each run the gate on and assert their route stays open.
- `service` ships `quarkus.http.limits.max-body-size=1088M`, which is a **global** ceiling — every
  route in the process, not just the upload. Tracked as an open tradeoff in
  `docs/issues/2026-07-19_artifacts-global-max-body-size-widens-public-ingest-dos.md`, which now
  lives in **this** repo (its five references used to point at a monorepo path no clone contains).
  Two things about it are counter-intuitive and both are settled empirically by
  `BodyCeilingProbeTest`:
  - **It only gates a declared `Content-Length`.** Quarkus installs the check as a route at order
    −2; with no `Content-Length` it merely stashes the limit under `io.quarkus.max-request-size` for
    a body *reader* to apply. A raw Vert.x route reading `HttpServerRequest` itself is not gated at
    all — which is why `RegistryRoutes` and `MavenRoutes` read through
    `registry/OciRequestBody` (Quarkus' own
    `VertxInputStream`, the one thing on the classpath that honours that key) and never off the
    request. A hand-rolled stream here would silently remove the registry's only wire limit.
  - **It is deliberately *above* `qits.artifacts.oci.max-layer-size`, not equal to it.** Equal values
    make the wire 413 preempt the application 413, and the client gets an empty body and a reset
    connection instead of the OCI error envelope. It must also never drop below 64M, or `ci-videos`
    breaks silently.
- **`BodyHandler.create()` is not unlimited** — vertx-web defaults it to 10 MiB, which is why the git
  host silently 413'd every push over 10 MB until `qits.repositories.git.max-pack-size` existed. Any
  new `BodyHandler` needs its limit stated. It must *not* be the global ceiling: a `BodyHandler`
  buffers into memory, so a route that uses one wants a much lower number than a route that streams
  to disk. The npm publish `PUT` is the third such route (`qits.artifacts.npm.max-publish-size`), and
  the one where the number is least obvious: a publish document carries its tarball
  **base64-inflated by 4/3** inside JSON, so 32M there is roughly a 24M tarball.
  **The daemon publish `PUT` is the one that would have been caught by this trap and was not**: the
  binary is 43 MB against the 10 MiB default, so a `BodyHandler` there would have 413'd every real
  publish while passing every test small enough to be quick. It streams through `OciRequestBody`
  instead, bounded by `qits.artifacts.daemon.max-binary-size` (256M) — `DaemonBinaryCapTest` lowers
  the knob rather than uploading a quarter of a gigabyte, and asserts the chunked case too, because
  a body with no `Content-Length` is gated by that knob and by nothing else.
- App-level config lives in `service/src/main/resources/application.properties` — the shipped copy —
  and the tests **inherit** it: Quarkus merges main's `application.properties` into the test config
  rather than letting the test one shadow it. So never re-declare an app-level setting
  (`quarkus.rest.path`, `quarkus.http.non-application-root-path`, the openapi settings, ...) in
  `src/test/resources`. A second copy only has to drift once for the suite to be green against a
  value the packaged process never sees. That file is for genuine test-only overrides: in-memory H2,
  `target/` data dirs, the closed-port intake url.
- `OpenApiSchemaExportTest` writes `docs/openapi.yml` from `/artifacts/q/openapi`
  (`./mvnw -pl service test -Dtest=OpenApiSchemaExportTest`). **`paths: {}` is correct output here**:
  every artifacts operation carries `@Operation(hidden = true)`, as in the monorepo's own document,
  and `/artifacts/git/**` is Vert.x so it appears in no OpenAPI document at all. Committed anyway so
  unhiding an operation shows up as a diff.
- A `Failed to start quarkus` / `Port already bound: 8081` failure is the known flake
  (`migration-plan.md` §9 item 14) — `@QuarkusTest` restarts racing for the test port. Re-run first.
- The blob store's `RepositoryType` enum hardcodes its types. Adding one is a schema check
  constraint change plus a validation profile, not a config knob — since V2 the constraint is named
  (`ck_artifact_repository_type`), so widening it is a one-liner (V3 is that one-liner, twice over).
- **The eight protocol types' profiles are empty and their `maxBytes()` is `0`, and that is not an
  oversight.** `OCI_IMAGES`, `NPM_PACKAGES`, `NPM_PROXY`, `OCI_MIRROR`, `MAVEN_PACKAGES`,
  `MAVEN_PROXY`, `DAEMON_BINARIES` and `DOCS` never
  flow through
  `BlobService` — their
  bytes arrive on their own wire routes and go straight to `BlobStore` — so there is no media type to
  sniff (a gzipped tar sniffs to nothing and would 400) and no metadata to require. The empty
  media-type set is what makes the zero cap safe: `accepts()` rejects a stray JSON-API upload before
  anything reads the cap. The real caps are `qits.artifacts.oci.max-layer-size`,
  `qits.artifacts.npm.max-publish-size`, `qits.artifacts.maven.max-artifact-size` and
  `qits.artifacts.daemon.max-binary-size`, config knobs
  because they have to move with the wire ceiling.
- **`/v2` has two resolution seams and they are not interchangeable.**
  `OciRegistryService.requireOciRepository` is the **write** one: it demands an `oci-images` row and
  refuses an `oci-mirror` one with `405`. `resolveForPull` is the **read** one: it also accepts a
  mirror namespace, normalises a Hub single-component image to `library/<name>`, and remaps a first
  segment with no repository row at all into the Hub namespace. Routing a write through the read seam
  compiles, passes anything that does not push to a mirror, and quietly lets a client write into a
  cache of somebody else's registry. The precedence inside `resolveForPull` is load-bearing too: an
  existing repository always wins its segment, so `/v2/qits/…` can never be shadowed by the remap.
- **The mirror miss path deliberately does NOT call `requireReferencesExist`.** A pulled index binds
  with none of its children present, because pull order is the reverse of push order — the index
  first, children by digest afterwards, each its own miss. Applying the push path's rule here would
  make every multi-arch pull fail on the first request, and un-lazying it would pay an upstream for
  architectures nobody asked for (a multi-arch pull counts once per architecture *fetched*). A mirror
  index referencing a child with no local row is the **normal** state, and BW's census was made to
  tolerate it on purpose.
- **`recordMirrorTagCheck` must never be called on a failed check.** Serving stale is a decision to
  hand out old bytes *now* while still trying next time; touching `checked_at` on an unreachable
  upstream would turn one outage into a whole TTL of silence, and would look exactly like a working
  cache.
- **The mirror verifies digests and the push path's `finalizeUpload` does not discard on mismatch —
  the two are different on purpose.** A push comes from inside qits-net, so wrong bytes are promoted
  under their own true digest and cost nothing. An upstream is not trusted, so a stream that does not
  hash to the digest requested is deleted from the staging area and refused with `502`. The temp file
  is the caller's to remove: `BlobStore` hands out a staging path and never deletes one.
- **A mirror error is a 502, not a 404 and never a 500, and which one is not cosmetic.** `502` means
  "the upstream could not be asked" and `404` means "the upstream was asked and has no such thing".
  Collapsing them sends whoever is debugging a failed build to the wrong registry — the single most
  expensive wrong answer this service can give, since after the `FROM` rewrite it sits under every
  build.
- **Wire routes must not return DTOs.** The `dto/UploadResult` lesson is worse on a raw Vert.x
  route: behind a JAX-RS `Response` there is at least a provider chain for the build to see the type
  through, but nothing sees a type serialised only inside a Vert.x handler. Responses are built with
  `JsonObject` (or a Jackson `ObjectNode` written out as text) and inbound documents are read as
  `JsonNode`, precisely so no `@RegisterForReflection` is needed and the failure mode is
  unavailable. `registry` and `npm` together add **zero** native-image configuration; if either ever
  seems to need some, something reflective has crept in.
- **Wire handlers carry `@ActivateRequestContext`/`@Transactional` on `OciRegistryService`,
  `NpmRegistryService` and `MavenRegistryService`.** A raw Vert.x route has no CDI request context
  and no transaction.
  `GitHostRoutes` is no precedent — it touches no database. Drop an annotation and those routes fail
  with `ContextNotActiveException` at runtime only. The same fact has a test-side consequence worth
  knowing: inside a `@QuarkusTest` a request context is *already* active, so two of these calls in a
  row share one Hibernate session and a read after a bulk update can see the pre-update row. That is
  a property of the test, not of the service — but it will look like a lost write.
- **Push options need `setAllowPushOptions(true)` on BOTH `ReceivePack` instances.** The one in
  `GitHostRoutes.service(...)` receives the options; the one in `infoRefs(...)` *advertises the
  capability*, and a client only sends `-o` if it was offered. Setting it on one and not the other
  compiles, passes anything that does not drive a real client, and produces the confusing failure
  where every option is silently never seen — so `ProtectedRefHook`'s two bypasses (`qits.release`,
  `qits.token=`) would all refuse, and the post-receive hook's own option, `-o qits.no-ci` (skips the
  CI intake POST for the push and only that one — the qits-projects backup event fires regardless;
  it grants no write, unlike the other two, so it needs no gate), would
  silently suppress nothing. The advertisement is asserted directly
  (`theReceivePackAdvertisementOffersPushOptions`, and again in the native IT) precisely because it
  has no other symptom.
- **`ProtectedRefHook` ships inert and must stay that way in this repo.** `mvn verify` proves the
  matrix, but the shipped value of `qits.repositories.git.protect-default-branch` is what decides
  whether this service can still receive its own redeploy — qits-platform-artifacts is the git host that
  serves the push that updates qits-platform-artifacts. `PackagedProcessIT`'s
  `theShippedDefaultsLeaveTheDefaultBranchUnprotected` asserts it against the packaged binary with
  no overrides; flipping the default
  here rather than in a deployment's env would be the one change that can strand this repo.
- **npm's `latest` dist-tag only moves forward**, by semver precedence
  (`NpmRegistryService.requireLatestMayMoveTo`, ordering in `NpmSemver`). A bare `npm publish` means
  `--tag latest`, so without this a main build publishing `<release>-main.g<sha>` would move
  `latest` onto a prerelease permanently. Three edges are deliberate: only `latest` is ordered, the
  first assignment is always allowed, and a version that does not parse as semver is **refused**
  rather than passed through. The refusal is thrown inside `publish()`'s transaction, so it takes
  the whole publish with it — the same shape the immutability refusal has, and the reason a publish
  never half-lands. A pipeline publishing prereleases needs `npm publish --tag main`; the message
  says so.
- **`quarkus.http.enable-compression=true` lives in `application.properties` and cannot move to a
  deployment's env.** It is `BUILD_AND_RUN_TIME_FIXED`, so an env var is read, accepted and ignored
  with no warning anywhere — the quietest kind of misconfiguration. And
  `quarkus.http.compress-media-types` must stay **unset**: setting it REPLACES Quarkus' default list
  rather than extending it, so naming one type silently stops compressing the other seven.
- **The explorer's caches are two different things and only one of them is invalidated.**
  `OciManifestFootprints` is keyed by `(repository, image, digest)` and is content-addressed, so an
  entry can never become wrong and nothing clears it; the *aggregates* built from it are deliberately
  not cached at all. `BlobDiskIndex` is the one that needs a write signal, and it gets it from
  `BlobStore.promote` — the single funnel every stored byte passes through, which is why the call
  sits there and not in each of the four write paths. Adding a fifth way to write a blob without
  going through `promote` would break the summary silently; there is no such path today and there
  should not be one.
- **`NpmUpstream`'s `HttpClient` is an instance field, not a static one** — the same constraint
  `PostReceiveNotifier` carries, and the reason the table above lists it. It is also this process'
  only outbound TLS, which no test can exercise (no network); a deployment smokes it once by hand.
