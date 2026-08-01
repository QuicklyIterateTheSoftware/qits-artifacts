package eu.wohlben.qits.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Where each of the three prefilled upstreams is actually dialled.
 *
 * <p>A plain JUnit test with no application behind it, and that is the point: the derivation is the
 * one part of the miss path the stub registry cannot prove, because the suite redirects every
 * upstream to it. Without this, "table-driven" would be a claim rather than a measurement — the
 * fetch tests would pass identically if every domain resolved to the same hardcoded host.
 */
class MirrorEndpointsTest {

  @Test
  void dockerHubIsDialledAtTheHostEveryRegistryClientHardcodes() {
    // docker.io is what a reference says; registry-1.docker.io is what answers. The hop lives in
    // code rather than in the table because it is a fact about Hub, not a deployment decision.
    assertEquals(
        "https://registry-1.docker.io", MirrorEndpoints.apiBase("docker.io", ""));
  }

  @Test
  void everyOtherUpstreamIsItsOwnDomainOverTls() {
    assertEquals("https://quay.io", MirrorEndpoints.apiBase("quay.io", ""));
    assertEquals(
        "https://registry.access.redhat.com",
        MirrorEndpoints.apiBase("registry.access.redhat.com", ""));
    // And an upstream registered later needs no code change at all — that is what table-driven buys.
    assertEquals("https://ghcr.io", MirrorEndpoints.apiBase("ghcr.io", null));
  }

  @Test
  void theOverrideReplacesTheDerivationForEveryUpstreamAndToleratesATrailingSlash() {
    assertEquals("http://127.0.0.1:9", MirrorEndpoints.apiBase("docker.io", "http://127.0.0.1:9"));
    assertEquals("http://127.0.0.1:9", MirrorEndpoints.apiBase("quay.io", "http://127.0.0.1:9/"));
  }
}
