package eu.wohlben.qits.docs;

import eu.wohlben.qits.artifacts.control.ArtifactsRepositorySeeder;
import eu.wohlben.qits.artifacts.control.BlobStore;
import eu.wohlben.qits.artifacts.control.DocsRegistryService;
import eu.wohlben.qits.artifacts.control.DocsRegistryService.BundleFile;
import eu.wohlben.qits.artifacts.error.DocsException;
import eu.wohlben.qits.registry.OciRequestBody;
import io.quarkus.runtime.configuration.MemorySize;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The docs wire, at {@code /artifacts/docs/<repository>/<site>/-/<version>[/<path>]}.
 *
 * <p>Two verbs and one idea, the daemon wire's shape with a bundle where it has a file: a streaming
 * {@code PUT} that publishes a whole version, and a {@code GET} that serves one file of one version
 * zero-copy.
 *
 * <p><b>The publish is atomic across fifty-odd files.</b> Every entry is staged and promoted before
 * a single row is written, and then {@code DocsRegistryService.publish} writes the site and all of
 * its files in one transaction — so a version is either wholly published or not published at all.
 * That matters more here than for any single-file type: a half-written site is one that lists itself
 * and then 404s its own stylesheet, which looks like a broken deployment rather than a failed
 * publish. Re-publishing a version is {@code 409}; versions are immutable, which is what lets
 * qits-docs resolve {@code latest} by query and hold no state of its own.
 *
 * <p><b>This service is the byte plane, not the website.</b> There is no index-file convention here,
 * no redirect and no notion of {@code latest}: {@code GET …/-/<version>} answers JSON metadata, and
 * asking for {@code index.html} means asking for {@code index.html}. Every one of those is a reading
 * experience, they belong to qits-docs, and putting them here would mean two services with an
 * opinion about what a documentation URL means.
 *
 * <p><b>There is no authentication here, at all</b> — the same stance {@code /v2}, {@code
 * /artifacts/npm}, {@code /artifacts/maven} and {@code /artifacts/daemons} take, and for the same
 * reason: on qits-net producers are trusted, and what a publish cannot do is <em>change</em>
 * anything, because a version is immutable. Machine auth arrives wholesale with qits-idp, for every
 * surface at once; gating this one alone would report a decision nobody took.
 *
 * <p><b>No {@code BodyHandler}, anywhere in this class.</b> {@code BodyHandler.create()} defaults to
 * 10 MiB — the measured Storybook bundle is 9.7 MB uncompressed and the next one will not be, so it
 * would 413 real publishes on a threshold nobody chose. The {@code PUT} streams through {@code
 * OciRequestBody} to a temp file instead. Note what bounds it: {@code
 * qits.artifacts.docs.max-bundle-size} is checked against the <b>uncompressed</b> total inside
 * {@link DocsBundle}, because a compressed archive makes {@code Content-Length} a measure of the
 * wrong number.
 */
@ApplicationScoped
public class DocsRoutes {

  private static final Logger LOG = Logger.getLogger(DocsRoutes.class);

  /** Waits for the NEXT chunk, not the whole upload — the registry's idle-timeout shape. */
  private static final Duration UPLOAD_IDLE_TIMEOUT = Duration.ofMinutes(1);

  @Inject DocsRegistryService registry;
  @Inject BlobStore blobStore;

  /**
   * The cap on an unpacked bundle, and on any single file in one.
   *
   * <p>The measured Storybook bundle is 9.7 MB across 53 files, so 256M is not a snug fit and is not
   * meant to be: docs sites grow with screenshots and embedded media, and a cap that has to be
   * raised in a deployment the day a release lands is a cap that will be raised in a hurry. Matching
   * the daemon default keeps one number in a deployment's head rather than two.
   *
   * <p>It is a config knob and not a constant for the reason every sibling's is — it has to move
   * with a deployment's disk budget rather than express a property of the format.
   */
  @ConfigProperty(name = "qits.artifacts.docs.max-bundle-size", defaultValue = "256M")
  MemorySize maxBundleSize;

  void init(@Observes Router router) {
    // Order is not what keeps these apart — DocsPaths' segment shape is, and DocsPathsTest pins it.
    // They are registered longest-first anyway, so a future loosening of a character class shows up
    // as a wrong answer rather than as an unreachable route.

    // HEAD is NOT derived from GET by Vert.x — every GET route needs its HEAD twin, or every client
    // that probes before downloading sees a 404.
    router.headWithRegex(DocsPaths.FILE).blockingHandler(guarded("head file", rc -> serve(rc, false)));
    router.getWithRegex(DocsPaths.FILE).blockingHandler(guarded("get file", rc -> serve(rc, true)));

    router.getWithRegex(DocsPaths.BUNDLE).blockingHandler(guarded("get version", this::version));
    router.getWithRegex(DocsPaths.SITE).blockingHandler(guarded("list versions", this::versions));
    router.getWithRegex(DocsPaths.SITES).blockingHandler(guarded("list sites", this::sites));

    // The publish PUT streams rather than buffers, for the two reasons OciRequestBody exists: a
    // BodyHandler would cap at 10 MiB, and a raw HttpServerRequest read is bounded by nothing.
    router
        .putWithRegex(DocsPaths.BUNDLE)
        .handler(OciRequestBody::pauseForWorker)
        .blockingHandler(guarded("publish", this::publish));

    // DELETE is deliberately unimplemented, exactly as on /v2, /artifacts/npm, /artifacts/maven and
    // /artifacts/daemons: the store is append-only and retiring a version is the GC strategy's job,
    // not a verb a publisher holds. 405 rather than 404, which would read as "unknown site".
    router
        .route(HttpMethod.DELETE, DocsPaths.BASE + "/*")
        .handler(rc -> DocsErrors.send(rc, 405, "this docs repository does not implement delete"));

    // Everything else under the base is a short plain-text 404, never the SPA's HTML. That matters
    // here specifically: the consumer is assembling a website, and an HTML body under a 200-shaped
    // failure is the one thing a browser would happily render in place of the page asked for.
    router.route(DocsPaths.BASE).handler(this::notFound);
    router.route(DocsPaths.BASE + "/*").handler(this::notFound);
  }

  private void notFound(RoutingContext rc) {
    DocsErrors.send(rc, 404, "not a route this docs repository serves: " + rc.normalizedPath());
  }

  // --- GET / HEAD -------------------------------------------------------------------------------

  /**
   * {@code GET|HEAD …/-/<version>/<path>} — one file of one published version, served zero-copy.
   *
   * <p>Anonymous, like every read in this service. Immutable on top and content-addressed
   * underneath, so the bytes behind this URL can never mean something else and the response says so
   * with the same cache header a maven release carries. The {@code ETag} is the blob digest, which
   * makes a revalidation free and makes two versions that share an asset visibly share it.
   */
  private void serve(RoutingContext rc, boolean withBody) {
    String repository = rc.pathParam("repository");
    String name = rc.pathParam("name");
    String version = rc.pathParam("version");
    String path = rc.pathParam("path");
    registry.requireDocsRepository(repository);

    DocsRegistryService.StoredFile stored =
        registry
            .findFile(repository, name, version, path)
            .orElseThrow(
                () ->
                    new DocsException(
                        404, "no such file in " + name + "@" + version + ": " + path));
    Path blob;
    long size;
    try {
      blob = blobStore.locate(stored.blobId());
      size = Files.size(blob);
    } catch (Exception missing) {
      throw new DocsException(
          404, "the bytes of " + path + " in " + name + "@" + version + " are not stored");
    }

    // Locate first, then touch — a row whose bytes are gone is a 404, not an access. HEAD counts,
    // the stance the OCI manifest route already takes. The row moved is the SITE's: the version is
    // what ages out, so the version is what records being wanted.
    registry.touchSite(repository, name, version);

    HttpServerResponse response =
        rc.response()
            .putHeader(HttpHeaders.CONTENT_TYPE, stored.mediaType())
            .putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(size))
            .putHeader(HttpHeaders.ETAG, "\"" + stored.blobId() + "\"")
            .putHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable");
    if (!withBody) {
      // HEAD must carry the same Content-Length as GET, and sendFile writes the file region
      // unconditionally, so it must not be reached here.
      response.end();
      return;
    }
    response
        .sendFile(blob.toString())
        .onFailure(
            thrown -> {
              LOG.debugf(
                  thrown,
                  "docs %s@%s/%s: send aborted after %d bytes",
                  name,
                  version,
                  path,
                  response.bytesWritten());
              if (!response.ended()) {
                response.close();
              }
            });
  }

  /** {@code GET …/-/<version>} — what this version is, without listing every file in it. */
  private void version(RoutingContext rc) {
    String repository = rc.pathParam("repository");
    String name = rc.pathParam("name");
    String version = rc.pathParam("version");
    registry.requireDocsRepository(repository);

    DocsRegistryService.StoredSite site =
        registry
            .find(repository, name, version)
            .orElseThrow(
                () -> new DocsException(404, "no such docs version: " + name + "@" + version));
    respond(rc, 200, describe(site));
  }

  /**
   * {@code GET …/<repository>} — every site, with its version count and its newest.
   *
   * <p>Flat, and ungrouped on purpose: a scope lives in a site's name, so a reader that wants to
   * show {@code @qits/ui-components} under {@code @qits} derives that itself. Deciding it here would
   * put a presentation choice in the byte plane and make a second reader with a different idea
   * impossible.
   */
  private void sites(RoutingContext rc) {
    String repository = rc.pathParam("repository");
    registry.requireDocsRepository(repository);

    JsonArray listed = new JsonArray();
    for (DocsRegistryService.CatalogEntry entry : registry.listCatalog(repository)) {
      listed.add(
          new JsonObject()
              .put("name", entry.name())
              .put("versionCount", entry.versionCount())
              .put("latestVersion", entry.latestVersion())
              .put("latestPublishedAt", entry.latestPublishedAt().toString()));
    }
    // An empty store is an empty list and a 200, not a 404: "nothing is published yet" is a fact
    // about the platform that a catalog page has to be able to render.
    respond(rc, 200, new JsonObject().put("sites", listed));
  }

  /**
   * {@code GET …/<site>} — every published version, newest first.
   *
   * <p>This is what makes qits-docs able to be stateless: {@code latest} is the first element of
   * this list, so nothing has to hold a pointer that could be wrong.
   */
  private void versions(RoutingContext rc) {
    String repository = rc.pathParam("repository");
    String name = rc.pathParam("name");
    registry.requireDocsRepository(repository);

    List<DocsRegistryService.StoredSite> found = registry.listVersions(repository, name);
    if (found.isEmpty()) {
      throw new DocsException(404, "no such docs site: " + name);
    }
    JsonArray versions = new JsonArray();
    found.forEach(site -> versions.add(describe(site)));
    respond(rc, 200, new JsonObject().put("name", name).put("versions", versions));
  }

  // --- PUT --------------------------------------------------------------------------------------

  /**
   * {@code PUT …/-/<version>} — the publish. Stream the archive down, stage every file in it, then
   * write the rows.
   *
   * <p>The archive is staged to a temp file rather than read from the socket, because the tar has to
   * be walked and a socket cannot be rewound if the walk fails halfway. It is this store's own temp
   * area — {@code BlobStore.newStagingFile} — and this method owns it: the {@code finally} is the
   * only thing that deletes it, which is why the whole body is inside the {@code try}.
   *
   * <p>The 409 is thrown inside {@code DocsRegistryService.publish}'s transaction, so a re-publish
   * never half-lands. The promoted blobs survive it, which costs nothing: they are content-addressed,
   * so identical bytes promote over themselves and anything genuinely new is a row-less blob the
   * census reports honestly and the sweep reclaims.
   */
  private void publish(RoutingContext rc) {
    String repository = rc.pathParam("repository");
    String name = rc.pathParam("name");
    String version = rc.pathParam("version");
    registry.requireDocsRepository(repository);

    Path archive = blobStore.newStagingFile();
    try {
      long received;
      try (InputStream body = OciRequestBody.open(rc, UPLOAD_IDLE_TIMEOUT.toMillis());
          OutputStream out = Files.newOutputStream(archive)) {
        received = body.transferTo(out);
      } catch (IOException e) {
        throw new DocsException(400, "the upload stream failed: " + e.getMessage());
      }
      if (received == 0) {
        throw new DocsException(400, "an empty body is not a docs bundle");
      }

      List<BundleFile> bundle =
          DocsBundle.stageAll(archive, blobStore, maxBundleSize.asLongValue());
      DocsRegistryService.StoredSite published =
          registry.publish(repository, name, version, bundle);

      // A JsonObject, not a DTO. A type serialised only inside a Vert.x handler is invisible to the
      // native-image build, so this stack adds zero reflection configuration — the rule registry,
      // npm, maven and daemon all follow.
      respond(rc, 201, describe(published));
    } finally {
      deleteQuietly(archive);
    }
  }

  private static JsonObject describe(DocsRegistryService.StoredSite site) {
    return new JsonObject()
        .put("name", site.name())
        .put("version", site.version())
        .put("fileCount", site.fileCount())
        .put("totalBytes", site.totalBytes())
        .put("publishedAt", site.publishedAt().toString());
  }

  private void respond(RoutingContext rc, int status, JsonObject body) {
    byte[] bytes = body.encode().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    rc.response()
        .setStatusCode(status)
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        .putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(bytes.length))
        .end(io.vertx.core.buffer.Buffer.buffer(bytes));
  }

  /** The archive is scratch: a failure to remove it must not become the client's error. */
  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      LOG.warnf(e, "docs: could not remove the staged bundle %s", path);
    }
  }

  /**
   * Wraps a handler so every throwable becomes the plain-text envelope rather than {@code
   * QuarkusErrorHandler}'s HTML.
   */
  private Handler<RoutingContext> guarded(String what, Handler<RoutingContext> handler) {
    return rc -> {
      try {
        handler.handle(rc);
      } catch (Throwable thrown) {
        DocsErrors.fail(rc, what, thrown);
      }
    };
  }
}
