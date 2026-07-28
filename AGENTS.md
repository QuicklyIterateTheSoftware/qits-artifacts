# qits-artifacts — working notes

Read `README.md` first: it defines the two things this repo owns (the blob store and the git host),
the one port, and the config surface. This file is the working conventions on top of it.

## The two rules that shape everything

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior
`mvn install` elsewhere, no credentials. `mvn verify` is the gate. Anything that would break that is
not a tradeoff to weigh, it is the thing this repo exists to avoid.

That is why: the poms duplicate versions instead of inheriting them, no pom declares a `eu.wohlben:*`
dependency, and `GitHostTest` builds its own bare origin with the git CLI instead of using the
monorepo's antrun-derived `fixtures/testing-repo.git`.

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
| `githost/JGitReflection` | `values()` on every enum `Config.getEnum` reads | **every** git route 404s — `FileRepositoryBuilder.build` throws `NoSuchMethodException` and `open()` returns null |
| `dto/UploadResult` | `@RegisterForReflection` | every upload 500s: the type is behind a `Response` return, so nothing registers it |
| `CiPostReceiveNotifier` | the `HttpClient` is an instance field, not static | build fails: an `HttpClientFacade` in the image heap |
| artifacts' `microprofile-config.properties` | H2 url with no `AUTO_SERVER` | the binary dies at boot on `ClassNotFoundException: org.h2.server.TcpServer` |

Only the first is a build-time failure. The rest are green builds that fail in production, which is
why the IT exists and why it drives a real `git clone`/`push` rather than asserting a status code.

## Paths

Everything is served under the `/artifacts` gateway segment — `qits-gateway` routes verbatim by
prefix, so an unprefixed route is simply unreachable, on `qits-net` as much as through the gateway.
Three second-level segments:

| Prefix | Machinery | Moves with |
|---|---|---|
| `/artifacts/api/**` | JAX-RS | `quarkus.rest.path` |
| `/artifacts/q/**` | Quarkus' non-application root (openapi, swagger-ui, health) | `quarkus.http.non-application-root-path` |
| `/artifacts/git/**` | raw Vert.x routes in `GitHostRoutes` | **nothing** — the segment is a literal in the code |

The third line is the one that bites: no config key moves those six routes, and no JAX-RS test
covers them. `GitHostTest` is the only thing that would catch them drifting, which is why its paths
are spelled out absolutely.

Two outbound/inbound addresses are contracts other repos hold:

- `/artifacts/git/<repoId>` and `/artifacts/git/<projectId>/<repoName>` — dialled by qits-ci and by
  qits-workspace-daemon's `Provisioner`.
- `qits.ci.intake-url` → `/ci/api/events/post-receive` — qits-ci's path, not ours. The notifier
  swallows delivery failures at debug, so a wrong value here produces no error anywhere and CI
  simply never runs.

## Package and module conventions

Two top-level packages, deliberately kept apart:

- `eu.wohlben.qits.artifacts.*` — the blob store. `artifacts/` holds `entity`, `persistence`, `dto`,
  `mapper`, `control`, `error`; `service/` holds only `api`. Entities are Panache active-record with
  public fields; mappers are MapStruct `@Mapper(componentModel = "jakarta")`.
- `eu.wohlben.qits.githost` — the git host, `service/` only. It is **not** folded into `artifacts`:
  it shares no code, no table and no datasource with the blob store, and keeping the package
  separate keeps a future second split cheap.

`artifacts` carries its own `error/` package (`ArtifactsException` and the four status-carrying
subtypes) rather than the monorepo's `domain/error/*`. It always did — this is one of the few
places where the duplicate-now register in `migration-plan.md` §5 was already satisfied at import.

## Adding a dependency on another context

Don't. Declare a port in the package that needs it, inject it as `Instance<T>`, and make absent a
supported configuration with a documented behaviour — see the table in the README.
`RepositoryNameResolver` is the only one, and it is optional because the id-addressed git scheme
predates the name-addressed one and remains the daemon's fallback.

Never add a JPA relation to another context's entity, and never a foreign key. Blobs address the
world by **string metadata**; the git host addresses it by **repo id string**. Both are in a
different database from whatever they name.

## Schema changes

`artifacts/src/main/resources/db/artifacts/migration/`, hand-written, its own lineage on its own
named datasource. This lineage is the original one from the monorepo, carried over **unsquashed** —
do not renumber it, and do not treat `V1__init.sql` as a squash baseline. Never touch the monorepo's
`db/migration`; that is a different database.

The git host owns no tables at all.

## Authentication

Authentication happens at `qits-gateway`. This service resolves a principal from a trusted header
(`X-Qits-User`, read by `artifacts/security/ForwardAuthMechanism`) and authenticates nothing.

**`identity.isAnonymous()` is not a security state** — it means "no name for the audit row". A check
of the form `if (identity.isAnonymous()) deny` would look like a security control and be worth
nothing, because reaching this service at all already implies you are inside the trusted network.

There is no auth variant to select and no authorization policy here, and roles are deliberately not
resolved — the single role check the system has (`qits.auth.required-role`) is the gateway's. See
`migration-auth-plan.md`.

## Tests

- `mvn verify` runs 53 tests (28 in `artifacts/`, 25 in `service/`) in about 30s. Nothing here
  needs docker.
- `mvn verify -Dnative` runs those, then 7 more: `PackagedProcessIT` against the compiled binary.
  It is the only suite that starts a **process** rather than an in-JVM Quarkus, so it is where the
  three route stacks are proved to coexist and where JGit is proved to have survived the compile.
  Its `@TestProfile` points the datasource, the blobs dir and the git data-dir under `target/`,
  passed to the launched binary as `-D` flags; it uses a **file** H2 of the same shape the
  deployment runs, not the unit suite's in-memory one, because the file/embedded shape is the thing
  that broke. Do not add a build-time property there — an IT cannot re-augment.
- `GitHostTest.seedOrigin()` shells `git init` + `git clone --bare` into
  `target/githost-test-repos/<uuid>/origin`. Tests that need the name-addressed scheme register the
  alias on `FakeRepositoryNameResolver`, which is a plain `@ApplicationScoped` bean in test sources
  — that is exactly the "a resolver is present" configuration production runs in.
- `ArtifactsTestSupport` (in `artifacts/`) and `ArtifactsTestMedia` (in `service/`) are separate on
  purpose: the two modules share no test classpath, the same way they do not in the monorepo.
- The suite points `qits.ci.intake-url` at a closed port. The notifier is fire-and-forget, so a push
  test still passes; nothing asserts the event arrives, because the receiver is another repo's.

## What not to "fix"

- `ArtifactsTokenFilter` matches on `getUriInfo().getPath()` starting with `repositories` — the
  path relative to the JAX-RS base, so it holds whatever `quarkus.rest.path` is. It was `artifacts`
  until the resource `@Path`s dropped that segment (the gateway segment carries it now); every
  JAX-RS resource this service ships is under `repositories/`, so the match is still the whole write
  surface. **A resource added outside `repositories/` is unguarded** — extend the prefix set, do not
  assume it is covered. It guards writes only, by design, and is a no-op when `qits.artifacts.token`
  is blank.
- `service` ships `quarkus.http.limits.max-body-size=64M`, which is a **global** ceiling — every
  route in the process, not just the upload. The monorepo tracks this as an open tradeoff
  (`docs/issues/2026-07-19_artifacts-global-max-body-size-widens-public-ingest-dos.md`) and it is
  carried over rather than solved. It is now *narrower* than it was: this module is its own process,
  so the ceiling no longer reaches a consuming app's unrelated routes — only this service's.
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
- The blob store's `RepositoryType` enum hardcodes the two CI types. Adding a type is a schema check
  constraint change plus a validation profile, not a config knob.
