package eu.wohlben.qits.sbom;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * A synthetic SBOM-store client: PUT, GET, HEAD, DELETE and the version listing.
 *
 * <p>Same reasoning as {@code daemon/DaemonClient}, {@code maven/MavenClient} and {@code
 * npm/NpmClient}: a plain JDK {@link HttpClient} rather than RestAssured, HTTP/1.1 pinned, paths
 * built by hand so they reach the server exactly as written. That matters more here than anywhere
 * else on this plane — a maven {@code packageName} carries a <b>colon</b> ({@code
 * eu.wohlben.qits:qits-eventstream}), and RestAssured percent-encodes one, so a suite driven through
 * it would be testing RestAssured rather than the route grammar.
 *
 * <p>{@link #putStreaming} sends the body with <b>no {@code Content-Length}</b>, which is the
 * chunked encoding a piped {@code curl --upload-file} uses and the one the global wire ceiling does
 * not gate. A publish test that only ever sent a declared length would prove nothing about the
 * route's own cap.
 */
public final class SbomClient implements AutoCloseable {

  private final URI base;
  private final HttpClient http =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  public SbomClient(URI base) {
    this.base = base;
  }

  @Override
  public void close() {
    http.close();
  }

  /** {@code PUT /artifacts/sboms/<type>/<name>/-/<version>} with a declared {@code Content-Length}. */
  public HttpResponse<String> put(String packageType, String name, String version, byte[] bytes) {
    return send(
        request(packageType, name, version).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)),
        HttpResponse.BodyHandlers.ofString());
  }

  /** The same publish, chunked — no {@code Content-Length}, the shape a real upload has. */
  public HttpResponse<String> putStreaming(
      String packageType, String name, String version, byte[] bytes) {
    return send(
        request(packageType, name, version)
            .PUT(
                HttpRequest.BodyPublishers.ofInputStream(
                    () -> new java.io.ByteArrayInputStream(bytes))),
        HttpResponse.BodyHandlers.ofString());
  }

  /** A publish carrying a bearer — the guarded posture. */
  public HttpResponse<String> putAuthorized(
      String packageType, String name, String version, byte[] bytes, String bearer) {
    return send(
        request(packageType, name, version)
            .header("Authorization", "Bearer " + bearer)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)),
        HttpResponse.BodyHandlers.ofString());
  }

  public HttpResponse<byte[]> get(String packageType, String name, String version) {
    return send(
        request(packageType, name, version).GET(), HttpResponse.BodyHandlers.ofByteArray());
  }

  public HttpResponse<Void> head(String packageType, String name, String version) {
    return send(
        request(packageType, name, version).method("HEAD", HttpRequest.BodyPublishers.noBody()),
        HttpResponse.BodyHandlers.discarding());
  }

  public HttpResponse<String> delete(String packageType, String name, String version) {
    return send(
        request(packageType, name, version).DELETE(), HttpResponse.BodyHandlers.ofString());
  }

  /** {@code GET /artifacts/sboms/<type>/<name>} — the version listing. */
  public HttpResponse<String> get(String packageType, String name) {
    return send(
        HttpRequest.newBuilder(URI.create(base + "artifacts/sboms/" + packageType + "/" + name))
            .timeout(Duration.ofMinutes(1))
            .GET(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** {@code GET} of an absolute path under the test root — the catch-all cases. */
  public HttpResponse<String> getAbsolute(String path) {
    return send(
        HttpRequest.newBuilder(URI.create(base + path.substring(1))).GET(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder request(String packageType, String name, String version) {
    // Built by hand rather than through URI.resolve: the path must reach the server exactly as
    // written — colon and all — and every convenience API in sight would either decode or re-encode
    // it.
    return HttpRequest.newBuilder(
            URI.create(base + "artifacts/sboms/" + packageType + "/" + name + "/-/" + version))
        .timeout(Duration.ofMinutes(1));
  }

  private <T> HttpResponse<T> send(
      HttpRequest.Builder builder, HttpResponse.BodyHandler<T> bodyHandler) {
    try {
      return http.send(builder.build(), bodyHandler);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
