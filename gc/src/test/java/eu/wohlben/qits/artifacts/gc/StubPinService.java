package eu.wohlben.qits.artifacts.gc;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * An in-process stand-in for qits-cd or qits-ci, one path and one canned answer.
 *
 * <p>There is no network in this repository's suite and no qits-cd or qits-ci to dial, so the two
 * HTTP adapters would otherwise only ever be tested on their failure path. What they have to get
 * right is the <b>shape</b> — which key holds the array, which field is blank, what a non-200 means
 * — and that can only be proved against something that answers.
 *
 * <p>Driven over real HTTP rather than by calling the parser directly, for the reason the npm proxy
 * suite records: an adapter tested past its transport passes just as well when the transport is
 * wrong.
 */
final class StubPinService implements AutoCloseable {

  private final HttpServer server;

  private StubPinService(HttpServer server) {
    this.server = server;
  }

  /** Answers 200 with this body on the given path, and 404 everywhere else. */
  static StubPinService serving(String path, String body) throws IOException {
    return answering(path, 200, body);
  }

  /** Answers the given status and body on the given path. */
  static StubPinService answering(String path, int status, String body) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        path,
        exchange -> {
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    return new StubPinService(server);
  }

  /** The base url an adapter is configured with — no trailing slash, as the real ones have none. */
  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
