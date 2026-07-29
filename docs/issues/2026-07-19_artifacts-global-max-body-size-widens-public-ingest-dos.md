# Artifacts's global max-body-size raise widens the public capture/OTLP ingest DoS surface

## Introduction

Carried over from the qits monorepo's `docs/issues/`, which no clone of this repository contains —
this file's path was referenced from five places here (`README.md`, `AGENTS.md`,
`service/…/microprofile-config.properties`, `RepositoryType.java`, and the registry feature document)
and every one of those references was dangling. The tradeoff moved with the code when artifacts
became its own deployable; the record should have moved with it, and now has.

The original entry named its sibling features by relative link — the capture ingest endpoint whose
wire-size guard this weakens, the observability OTLP receiver, and the gateway's `PublicPaths`. Those
documents live in the monorepo and are not reachable from here, so they are named rather than linked.
What matters for a reader of *this* repository is below.

## Status: mitigated, not fully resolved

The blast radius has been cut but the coupling is not eliminated. See "Mitigation applied" and "Full
fix (pending)".

## Observed

Artifacts must accept media uploads larger than Quarkus' 10 MB default `max-body-size`. That
setting is a **hard global ceiling — it gates every route**. Two things were verified empirically:

- A streamed `InputStream` upload is `413`'d at the HTTP layer once the body exceeds the limit (so
  streaming does not sidestep it), and
- a **custom Vert.x route with its own `BodyHandler` does NOT bypass it either** (an 11 MB upload is
  rejected at a 10 MB global limit, accepted at 50 MB) — i.e. the `GitHostRoutes` "large pushes"
  pattern only works because dev/fixture pushes happen to be small; it too is capped by the global
  limit.

Consequently the only way to accept large artifacts uploads is to raise the **global** limit, which
also lifts the wire-size guard on the public, unauthenticated `POST /api/capture` and `POST
/api/otel/v1/*` endpoints (both read the body as a fully-buffered `byte[]`). On the Traefik-exposed
`oauth` prod deployment those are internet-reachable, so a large body buffers into heap before any
application-level cap runs.

## Mitigation applied (2026-07-19)

- The global limit is set to **64 MB** (not the originally-committed 1024 MB), and the `ci-videos`
  type cap is **reduced to 64 MB** to match. A golden UI-flow clip (a short, compressed webm/mp4) is
  well under this, so the feature is unaffected, while the public endpoints are exposed to a ~6× lift
  over the 10 MB default instead of ~100×.
- The upload stays a JAX-RS `InputStream` resource (`BlobController.upload`), which streams to disk
  incrementally — it does not buffer the whole body in memory (unlike a `BodyHandler`), so artifacts
  itself doesn't add a large-buffer DoS.

## Full fix (pending)

Decouple the public ingest paths from the global limit with a **per-path low-limit `BodyHandler`** on
`/api/capture` and `/api/otel/*` (a `BodyHandler` can enforce a limit *below* the global ceiling on a
specific route, the inverse of the raise-per-route that fails above), then the global limit can be
raised freely for artifacts without exposing capture/OTLP. Deferred because it modifies the request
handling of endpoints owned by other features (capture, telemetry) and wants their own regression
coverage; it is out of the artifacts feature's scope.

## Regression test to add with the full fix

A `@QuarkusTest` posting a body larger than the per-path cap to `/api/capture` expects `413`, while an
artifacts upload of the same size succeeds — proving the limits are decoupled.

## 2026-07-29 — raised again, to 1088M, for the OCI registry

The `ci-videos`-driven 64 MB is superseded by `OCI_IMAGES`' layer cap: a `docker push` sends a layer
as one request body. Two ceilings move, not one — qits-artifacts' 64M and **qits-gateway's 100M** —
and they are one number spelled in two repositories. The artifacts ceiling is deliberately set
*above* the layer cap (1 GiB + 64M of slack) so the application cap always fires first: were they
equal, the wire 413 would win and a client would get an empty-bodied 413 with the connection reset
instead of the OCI error envelope that explains itself.

### What the earlier entries got wrong about the mechanism

> "a custom Vert.x route does NOT bypass it"

True **only for requests that declare a `Content-Length`**. Quarkus enforces the limit in exactly two
places (verified against 3.34.6, and now pinned by `BodyCeilingProbeTest`):

1. `HttpServerCommonHandlers.enforceMaxBodySize` installs a route at **order −2** on the same router
   `GitHostRoutes` and `RegistryRoutes` register on. A declared `Content-Length` over the limit is
   413'd there, before any application handler runs. Nothing bypasses this.
2. With **no** `Content-Length` that handler only stashes the limit under `io.quarkus.max-request-size`
   and calls `next()`. Enforcement is then whatever reads the body. The only readers that honour the
   key are RESTEasy Reactive's `VertxInputStream` and `io.quarkus.vertx.http.runtime.VertxInputStream`.

So a **chunked** body on a raw Vert.x route that reads `HttpServerRequest` itself is bounded by
nothing. The original investigation used RestAssured, which always sends a `Content-Length`; it
measured one cell of a 2×2 matrix and generalised.

Two consequences:

- **This raise widens strictly less than it appears to.** The public JAX-RS ingest paths this issue is
  about read through `VertxInputStream`, so they are still bounded — at the new, higher number. The
  registry's `PATCH` is not bounded by it at any value, which is why it reads through
  `registry/OciRequestBody` (the same stream) and enforces `qits.artifacts.oci.max-layer-size` while
  streaming, exactly as `BlobService` does per repository type.
- **The per-type caps are unaffected by the raise**, because the ceiling and the caps count different
  things: the ceiling counts wire bytes, `BlobStore.stage` counts bytes written. Raising the ceiling
  only removes an earlier rejection; it cannot relax a later one.
  `BlobControllerTest.thePerTypeCapStillFiresWellBelowTheRaisedGlobalCeiling` is the guard, and it
  asserts the response **body** rather than the status — both rejections are a 413, but only the
  application one carries a message.

### Fixed while here: the git host was capped at 10 MiB, not 64M

`BodyHandler.create()` is not unlimited. vertx-web's `BodyHandlerImpl` defaults `bodyLimit` to
10485760, so every `git push` above 10 MB was silently 413'd — well under the number this file, the
README and `microprofile-config.properties` all claimed applied. The routes now set the limit
explicitly (`qits.repositories.git.max-pack-size`, default 64M), with a regression test that pushes
12 MB of incompressible bytes. It is deliberately **not** the new 1088M ceiling: a pack is buffered
into memory, so inheriting a ceiling sized for something that streams to disk would turn a large push
into a gigabyte heap allocation on a deliberately unauthenticated route.

### The mitigation in "Full fix (pending)" does not transfer to qits-gateway

A per-path low-limit `BodyHandler` remains sound *here*. It cannot be the gateway's mechanism: a
`BodyHandler` buffers the body — which the gateway forbids outright — and consumes the stream
`vertx-http-proxy` needs, so it would break every upload it did not reject. There is also no per-path
variant of `max-body-size` in Quarkus config: `ServerLimitsConfig.maxBodySize()` is a single value.
A per-prefix `Content-Length` ceiling in the gateway is implementable (Quarkus' own shape, at a route
order below `GatewayRouter.ROUTE_ORDER`) and is recorded here as a follow-up rather than built. What
an operator can do today is cap per-location body size at whatever terminates TLS in front of the
gateway.
