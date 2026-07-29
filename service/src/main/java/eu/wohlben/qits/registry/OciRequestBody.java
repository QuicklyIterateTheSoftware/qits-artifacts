package eu.wohlben.qits.registry;

import io.quarkus.vertx.http.runtime.VertxInputStream;
import io.vertx.ext.web.RoutingContext;
import java.io.InputStream;

/**
 * The request-body half of the registry's raw Vert.x routes: the one thing that must happen on the
 * event loop, and the one class that changes if Quarkus ever moves {@link VertxInputStream}.
 *
 * <p><b>Why not our own stream.</b> {@code VertxInputStream} is the same implementation RESTEasy
 * Reactive runs the JAX-RS blob upload through — so the two upload paths behave identically at the
 * ceiling — and, decisively, it is the only thing on the classpath that reads {@code
 * io.quarkus.max-request-size} from the {@link RoutingContext}. Quarkus enforces {@code
 * quarkus.http.limits.max-body-size} at router order -2 for a declared {@code Content-Length} only;
 * for a chunked body it merely stashes the limit under that key and delegates to whatever reads the
 * body. A hand-rolled stream that ignored the key would give the registry <em>no wire limit at
 * all</em> on exactly the encoding {@code docker push} uses for layers. {@code BodyCeilingProbeTest}
 * pins both halves of that.
 *
 * <p>It lives in {@code io.quarkus.vertx.http.runtime}, which carries no compatibility promise. That
 * is the acceptable kind of risk here: an upgrade that moves it breaks <em>this file</em> at compile
 * time, which is the failure mode this repo prefers over a green build that fails in production.
 */
final class OciRequestBody {

  private OciRequestBody() {}

  /**
   * Chain as the <b>first</b> handler of every route whose body is read on a worker thread.
   *
   * <p>Vert.x delivers body chunks to the request's data handler as they arrive, and with no handler
   * set and the request not paused {@code HttpEventHandler.handleChunk} <em>discards</em> them — it
   * only advances {@code bytesRead}. {@code blockingHandler} returns control to the event loop
   * before the worker runs, so {@code VertxInputStream}'s own {@code pause()}, issued on the worker,
   * is always late by some number of chunks. Pausing here — synchronously, in the same event-loop
   * task that dispatched the request head — creates the request's pending buffer before any content
   * can be processed.
   *
   * <p>The symptom of omitting this is a digest mismatch on finalize, intermittently, under load
   * only. It is worth the extra line.
   */
  static void pauseForWorker(RoutingContext rc) {
    rc.request().pause();
    rc.next();
  }

  /**
   * The body as a blocking stream, safe to hand to {@code BlobStore}. Must be called from a worker
   * thread — it throws {@code BlockingOperationNotAllowedException} on an event loop.
   *
   * @param idleTimeoutMillis how long to wait for the <b>next chunk</b>, not for the whole upload;
   *     on expiry the connection is closed and the read throws {@code IOException}
   */
  static InputStream open(RoutingContext rc, long idleTimeoutMillis) {
    return new VertxInputStream(rc, idleTimeoutMillis);
  }
}
