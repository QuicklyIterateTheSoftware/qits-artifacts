package eu.wohlben.qits.daemon;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * A synthetic daemon-binaries client: PUT, GET, HEAD, DELETE.
 *
 * <p>Same reasoning as {@code maven/MavenClient} and {@code npm/NpmClient}: a plain JDK {@link
 * HttpClient} rather than RestAssured, HTTP/1.1 pinned, paths built by hand so they reach the server
 * exactly as written. There is one extra reason here — {@link #putStreaming} sends the body with
 * <b>no {@code Content-Length}</b>, which is the chunked encoding a real {@code curl --upload-file}
 * of a 43 MB binary uses and the one the global wire ceiling does not gate. A publish test that only
 * ever sent a declared length would prove nothing about the route's own cap.
 */
public final class DaemonClient implements AutoCloseable {

  private final URI base;
  private final HttpClient http =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  public DaemonClient(URI base) {
    this.base = base;
  }

  @Override
  public void close() {
    http.close();
  }

  /** {@code PUT /artifacts/daemons/<name>/<version>} with a declared {@code Content-Length}. */
  public HttpResponse<String> put(String name, String version, byte[] bytes) {
    return send(
        request(name, version).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)),
        HttpResponse.BodyHandlers.ofString());
  }

  /** The same publish, chunked — no {@code Content-Length}, the shape a real upload has. */
  public HttpResponse<String> putStreaming(String name, String version, byte[] bytes) {
    return send(
        request(name, version)
            .PUT(
                HttpRequest.BodyPublishers.ofInputStream(
                    () -> new java.io.ByteArrayInputStream(bytes))),
        HttpResponse.BodyHandlers.ofString());
  }

  /** A publish carrying a bearer — the guarded posture. */
  public HttpResponse<String> putAuthorized(
      String name, String version, byte[] bytes, String bearer) {
    return send(
        request(name, version)
            .header("Authorization", "Bearer " + bearer)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)),
        HttpResponse.BodyHandlers.ofString());
  }

  public HttpResponse<byte[]> get(String name, String version) {
    return send(request(name, version).GET(), HttpResponse.BodyHandlers.ofByteArray());
  }

  public HttpResponse<Void> head(String name, String version) {
    return send(
        request(name, version).method("HEAD", HttpRequest.BodyPublishers.noBody()),
        HttpResponse.BodyHandlers.discarding());
  }

  public HttpResponse<String> delete(String name, String version) {
    return send(request(name, version).DELETE(), HttpResponse.BodyHandlers.ofString());
  }

  /** {@code GET} of an absolute path under the test root — the catch-all cases. */
  public HttpResponse<String> getAbsolute(String path) {
    return send(
        HttpRequest.newBuilder(URI.create(base + path.substring(1))).GET(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder request(String name, String version) {
    // Built by hand rather than through URI.resolve: the path must reach the server exactly as
    // written, and every convenience API in sight would either decode or re-encode it.
    return HttpRequest.newBuilder(
            URI.create(base + "artifacts/daemons/" + name + "/" + version))
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
