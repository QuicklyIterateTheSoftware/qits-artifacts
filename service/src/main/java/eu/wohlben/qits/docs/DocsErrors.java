package eu.wohlben.qits.docs;

import eu.wohlben.qits.artifacts.error.ArtifactsException;
import eu.wohlben.qits.artifacts.error.DocsException;
import eu.wohlben.qits.artifacts.error.NotFoundException;
import eu.wohlben.qits.artifacts.error.PayloadTooLargeException;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import org.jboss.logging.Logger;

/**
 * The docs error envelope: a status code plus a short plain-text body.
 *
 * <p>There is no JSON contract to honour — the publisher is a release pipeline reading a status and
 * the consumer is qits-docs reading one — so a string is the whole envelope, and nothing here needs
 * {@code @RegisterForReflection}: this stack, like {@code registry}, {@code npm}, {@code maven} and
 * {@code daemon}, adds <b>zero</b> native-image configuration.
 *
 * <p>Nothing here calls {@code rc.fail()}: Quarkus installs {@code QuarkusErrorHandler} as the
 * router's failure handler, and it answers with an HTML page. For this surface that is worse than
 * merely wrong — the consumer is serving a website, and an HTML error body is exactly the thing a
 * browser will render as though it were the page that was asked for.
 */
final class DocsErrors {

  private static final Logger LOG = Logger.getLogger(DocsErrors.class);

  private DocsErrors() {}

  static void send(RoutingContext rc, int status, String message) {
    // A response may already be on its way: a client that hung up mid-publish leaves nothing to
    // answer. Writing again throws IllegalStateException and buries the real cause.
    if (rc.response().ended() || rc.response().headWritten()) {
      return;
    }
    rc.response()
        .setStatusCode(status)
        .putHeader(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
        .end(message);
  }

  /**
   * The safety net at the edge of every handler. Prefer throwing a {@link DocsException} with the
   * right status at the point the problem is understood — the translations below are for what
   * legitimately escapes {@code BlobStore}, which predates all of this and speaks its own
   * vocabulary.
   */
  static void fail(RoutingContext rc, String what, Throwable thrown) {
    switch (thrown) {
      case DocsException e -> send(rc, e.statusCode(), e.getMessage());
      case PayloadTooLargeException e -> send(rc, 413, e.getMessage());
      case NotFoundException e -> send(rc, 404, e.getMessage());
      case ArtifactsException e -> {
        LOG.warnf(e, "docs: %s", what);
        send(rc, e.statusCode(), e.getMessage());
      }
      default -> {
        LOG.errorf(thrown, "docs: %s", what);
        send(rc, 500, "internal docs repository error");
      }
    }
  }
}
