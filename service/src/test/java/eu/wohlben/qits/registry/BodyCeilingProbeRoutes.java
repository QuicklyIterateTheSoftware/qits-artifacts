package eu.wohlben.qits.registry;

import io.quarkus.vertx.http.runtime.VertxInputStream;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Two raw Vert.x routes that exist only to settle where {@code quarkus.http.limits.max-body-size} is
 * actually enforced — the question milestone M0 of the OCI registry feature has to answer before
 * anything streams a gigabyte.
 *
 * <p>Test sources on purpose: these prove a property of the <em>framework</em>, not of this service,
 * and nothing that ships should serve {@code /probe/*}. A test-source bean observing {@link Router}
 * is the established shape here — {@code FakeRepositoryNameResolver} is the same idea for a
 * different seam.
 *
 * <p>The two routes are the two halves of the answer. {@link #RAW_BYTES_SEEN} makes the first one's
 * result observable: a route that is never reached and a route that reads zero bytes are different
 * outcomes, and only one of them means the ceiling held.
 */
@ApplicationScoped
public class BodyCeilingProbeRoutes {

  /** Bytes the unguarded route drained, or -1 if it never ran. Reset by the test before each case. */
  public static final AtomicLong RAW_BYTES_SEEN = new AtomicLong(-1);

  void init(@Observes Router router) {
    // The shape nobody had tested: no BodyHandler, no VertxInputStream, the body read straight off
    // HttpServerRequest on the event loop. The data handler is set synchronously in the dispatch
    // task, so no chunk can be dropped and the count is exact.
    router
        .post("/probe/raw-drain")
        .handler(
            rc -> {
              AtomicLong count = new AtomicLong();
              rc.request()
                  .handler(b -> count.addAndGet(b.length()))
                  .endHandler(
                      v -> {
                        RAW_BYTES_SEEN.set(count.get());
                        rc.response()
                            .putHeader("content-type", "text/plain")
                            .end(Long.toString(count.get()));
                      });
            });

    // The shape the registry uses: pause on the EVENT LOOP, then read on a worker through Quarkus'
    // own limit-aware stream. Both halves matter — see OciRequestBody for why the pause cannot be
    // left to the stream's own constructor.
    router
        .post("/probe/streamed")
        .handler(
            rc -> {
              rc.request().pause();
              rc.next();
            })
        .blockingHandler(
            rc -> {
              long total = 0;
              try (InputStream in = new VertxInputStream(rc, 30_000L)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                  total += n;
                }
              } catch (IOException e) {
                // VertxInputStream has already written the 413 and ended the response when the
                // failure is the limit; anything else is a genuine read error.
                if (!rc.response().ended()) {
                  rc.response().setStatusCode(500).end();
                }
                return;
              }
              rc.response().end(Long.toString(total));
            });
  }
}
