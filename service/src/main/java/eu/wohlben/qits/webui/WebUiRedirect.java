package eu.wohlben.qits.webui;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;

/**
 * {@code /artifacts} → {@code /artifacts/}, and nothing else.
 *
 * <p>Quinoa mounts the web client at {@code /artifacts/*}, which does not match the bare segment —
 * so before this route existed, typing {@code /artifacts} into a browser answered 404 while
 * {@code /artifacts/} served the client. Upstream behaviour, but not a defensible surface: the
 * segment is this service's to serve in every spelling, and the bare one means "take me to the
 * client".
 *
 * <p>GET and HEAD only — the bare segment has no meaning for a write, and a machine client POSTing
 * to {@code /artifacts} gets a 405 rather than a bounce at HTML. 301, because the
 * answer will never be anything else, and the query string travels. No other route stack is
 * touched: {@code /artifacts/api}, {@code /artifacts/git}, {@code /artifacts/npm} and {@code /v2}
 * all carry more path than this exact-match route can see.
 */
@Singleton
public class WebUiRedirect {

  void init(@Observes Router router) {
    router
        .route("/artifacts")
        .method(HttpMethod.GET)
        .method(HttpMethod.HEAD)
        .handler(
            rc -> {
              String query = rc.request().query();
              rc.response()
                  .setStatusCode(301)
                  .putHeader("Location", query == null ? "/artifacts/" : "/artifacts/?" + query)
                  .end();
            });
  }
}
