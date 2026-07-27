# qits-artifacts — working notes

Read `README.md` first: it defines the two things this repo owns (the blob store and the git host),
the one port, and the config surface. This file is the working conventions on top of it.

## The one rule that shapes everything

This repo must build and test green from a **clone of itself alone** — no monorepo, no docker, no
prior `mvn install` elsewhere, no credentials. `mvn verify` is the gate. Anything that would break
that is not a tradeoff to weigh, it is the thing this repo exists to avoid.

That is why: the poms duplicate versions instead of inheriting them, no pom declares a `eu.wohlben:*`
dependency, and `GitHostTest` builds its own bare origin with the git CLI instead of using the
monorepo's antrun-derived `fixtures/testing-repo.git`.

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

- `mvn verify` runs 53 tests (28 in `artifacts/`, 25 in `service/`) in about 30s. There are no
  integration tests and no failsafe binding: nothing here needs docker.
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
