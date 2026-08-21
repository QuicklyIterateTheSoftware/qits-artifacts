package eu.wohlben.qits.artifacts.gc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.artifacts.gc.dto.GcPinSource;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins handed in with the request, against pins fetched over the wire.
 *
 * <p>qits-platform-orchestrator reads the platform's pins once per run and gives the same set to
 * every deleter. The property that makes that safe is the one this suite is about: the supplied
 * documents are read by the <b>same parsers</b> as the fetched ones, so a keep-set does not change
 * because of how it travelled. Only the provenance differs — the source's name, and an empty url.
 *
 * <p>The other half is that supplying pins can never widen a run. A member the caller left out is
 * that source <b>unanswered</b>, which fails the run closed exactly as an unreachable service does,
 * and a document this cannot read is refused rather than read as "nothing is pinned".
 */
class GcSuppliedPinsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String DEPLOYMENTS =
      """
      {"pins":[
        {"applicationName":"qits-artifacts","shas":["aaaa","bbbb"]},
        {"applicationName":"qits-ci","shas":["cccc"]}
      ]}
      """;

  private static final String CI_DAEMON =
      """
      {"daemonName":"qits-ci-daemon","daemonVersion":"2026.805.1",
       "previousDaemonVersion":"2026.804.9","source":"adopted"}
      """;

  @Test
  void suppliedDocumentsProduceTheSameKeepsAsFetchedOnes() throws IOException {
    // The equivalence the orchestrator's whole design rests on: one document, two ways in, one
    // keep-set. Anything read differently here would be a run planning against pins the reviewer
    // never saw.
    try (StubPinService cd = StubPinService.serving("/pins", DEPLOYMENTS);
        StubPinService ci = StubPinService.serving("/daemon", CI_DAEMON)) {
      GcPins fetched = httpSources(cd.baseUrl(), ci.baseUrl()).fetch();
      GcPins supplied = new GcPinSources().fetch(documents(DEPLOYMENTS, CI_DAEMON));

      assertTrue(supplied.complete());
      assertEquals(fetched.deployments(), supplied.deployments());
      assertEquals(fetched.daemonName(), supplied.daemonName());
      assertEquals(fetched.daemonVersions(), supplied.daemonVersions());
      assertEquals(fetched.blobs(), supplied.blobs());
      assertEquals(GcPins.BY_CD, supplied.pinsImageTag("qits-artifacts", "bbbb"));
      assertEquals(GcPins.BY_CI, supplied.pinsDaemonVersion("qits-ci-daemon", "2026.804.9"));
      assertEquals(
          keepsOf(fetched, "qits-platform-deployments"),
          keepsOf(supplied, GcSuppliedPins.CD_SOURCE),
          "the same identities, whichever way the document arrived");
      assertEquals(keepsOf(fetched, "qits-ci"), keepsOf(supplied, GcSuppliedPins.CI_SOURCE));
    }
  }

  @Test
  void aSuppliedSourceIsNamedAsSuppliedAndReportsNoUrl() throws IOException {
    // Provenance is the half of a keep-set a reviewer cannot check from the plan alone, so a run
    // that was handed its pins must not read as one that went and asked. There is no url to repeat
    // by hand, and inventing this service's own would name a call it never made.
    GcPins pins = new GcPinSources().fetch(documents(DEPLOYMENTS, CI_DAEMON));

    GcPinSource cd = source(pins, GcSuppliedPins.CD_SOURCE);
    assertTrue(cd.answered());
    assertEquals("", cd.url());
    assertEquals(2, cd.pinCount());
    assertEquals("", source(pins, GcSuppliedPins.CI_SOURCE).url());
  }

  @Test
  void aMissingMemberIsAnUnansweredSourceRatherThanNothingPinned() throws IOException {
    // The fail-closed rule, unchanged: a caller that supplied one document supplied half a keep-set,
    // and half a keep-set condemns whatever the missing half protected. The source that DID arrive
    // still answers, so a report says exactly what was missing.
    GcPins noCi = new GcPinSources().fetch(documents(DEPLOYMENTS, null));

    assertFalse(noCi.complete());
    assertEquals(1, noCi.failures().size());
    assertTrue(noCi.whyIncomplete().contains(GcSuppliedPins.CI_SOURCE), noCi.whyIncomplete());
    assertTrue(noCi.whyIncomplete().contains("ciDaemon"), noCi.whyIncomplete());
    assertTrue(source(noCi, GcSuppliedPins.CD_SOURCE).answered());
    assertFalse(source(noCi, GcSuppliedPins.CI_SOURCE).answered());

    GcPins neither = new GcPinSources().fetch(new GcSuppliedPins(null, null));
    assertFalse(neither.complete());
    assertEquals(2, neither.failures().size(), "both sources are asked, so neither outage hides");
  }

  @Test
  void anEmptyPinsArrayIsAnAnswerAndPinsNothing() throws IOException {
    // A platform with nothing deployed. It is the opposite of a missing member: the source answered,
    // the answer is "no application is serving", and the run may proceed — which means every tag
    // old enough is a candidate. The orchestrator has to know these two states read differently.
    GcPins pins = new GcPinSources().fetch(documents("{\"pins\":[]}", CI_DAEMON));

    assertTrue(pins.complete());
    assertEquals(0, source(pins, GcSuppliedPins.CD_SOURCE).pinCount());
    assertTrue(source(pins, GcSuppliedPins.CD_SOURCE).answered());
    assertEquals(Set.of(), pins.deploymentShas("qits-artifacts"));
  }

  @Test
  void aSuppliedDocumentThisCannotReadIsRefusedRatherThanReadAsNoPins() throws IOException {
    // The same refusal the HTTP reader makes, from the same code. A body with no 'pins' array read
    // as an empty keep-set would condemn every sha tag on the platform.
    GcPins pins = new GcPinSources().fetch(documents("{\"deployments\":[]}", CI_DAEMON));

    assertFalse(pins.complete());
    assertTrue(pins.whyIncomplete().contains("'pins' array"), pins.whyIncomplete());
  }

  @Test
  void noBodyAndNoPinsMemberBothMeanReadThemOverHttp() throws IOException {
    // Every caller that exists today sends nothing, and nothing must keep meaning what it meant.
    assertEquals(Optional.empty(), GcSuppliedPins.inRequestBody(null));
    assertEquals(Optional.empty(), GcSuppliedPins.inRequestBody(json("{}")));
    assertEquals(Optional.empty(), GcSuppliedPins.inRequestBody(json("{\"pins\":null}")));

    GcSuppliedPins both =
        GcSuppliedPins.inRequestBody(
                json("{\"pins\":{\"deployments\":" + DEPLOYMENTS + ",\"ciDaemon\":" + CI_DAEMON
                    + "}}"))
            .orElseThrow();
    assertEquals(2, both.deploymentPins().size());
    assertEquals("qits-ci-daemon", both.daemonPin().daemonName());

    // Present but empty is a supplied set with nothing in it — two unanswered sources, not a
    // no-body call, because the caller said it was supplying pins and then supplied none.
    assertTrue(GcSuppliedPins.inRequestBody(json("{\"pins\":{}}")).isPresent());
    assertThrows(
        IllegalArgumentException.class, () -> GcSuppliedPins.inRequestBody(json("{\"pins\":5}")));
  }

  private static GcSuppliedPins documents(String deployments, String ciDaemon) throws IOException {
    return new GcSuppliedPins(
        deployments == null ? null : json(deployments), ciDaemon == null ? null : json(ciDaemon));
  }

  private static JsonNode json(String document) throws IOException {
    return MAPPER.readTree(document);
  }

  private static GcPinSource source(GcPins pins, String named) {
    return pins.sources().stream()
        .filter(source -> source.source().equals(named))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no pin source named " + named));
  }

  private static java.util.List<String> keepsOf(GcPins pins, String named) {
    return source(pins, named).keeps();
  }

  /** The two HTTP adapters as CDI would wire them, pointed at the stubs. */
  private static GcPinSources httpSources(String cdBaseUrl, String ciBaseUrl) {
    GcPinSources sources = new GcPinSources();
    CdHttpDeploymentPins cd = new CdHttpDeploymentPins();
    cd.baseUrl = cdBaseUrl;
    cd.timeout = Duration.ofSeconds(5);
    cd.objectMapper = MAPPER;
    sources.cd = cd;
    CiHttpDaemonPins ci = new CiHttpDaemonPins();
    ci.baseUrl = ciBaseUrl;
    ci.timeout = Duration.ofSeconds(5);
    ci.objectMapper = MAPPER;
    sources.ci = ci;
    return sources;
  }
}
