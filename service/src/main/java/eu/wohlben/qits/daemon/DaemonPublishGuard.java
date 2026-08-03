package eu.wohlben.qits.daemon;

import eu.wohlben.qits.artifacts.error.DaemonException;
import eu.wohlben.qits.auth.MachineAuth;
import eu.wohlben.qits.auth.MachineIdentity;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticator;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The machine-token guard on the daemon publish {@code PUT} — {@code AdminWriteGuard}'s decision,
 * reached the only way a raw Vert.x route can reach it.
 *
 * <p><b>Why this class exists rather than one more entry in a prefix set.</b>
 * daemon-artifact-identity-plan.md §2.2 says the publish route "must be added to that set
 * deliberately or it ships unguarded", naming the filter it knew as {@code ArtifactsTokenFilter}.
 * That filter is {@link eu.wohlben.qits.artifacts.api.AdminWriteGuard} now, and it is a JAX-RS
 * {@code ContainerRequestFilter}: it sees only what RESTEasy dispatches and runs on <b>no</b> raw
 * Vert.x route — not {@code /v2}, not {@code /artifacts/npm}, not {@code /artifacts/maven}. Adding a
 * prefix to its set would have looked like a guard and guarded nothing. The decision is the same
 * one, so the config keys are the same two constants; only the plumbing differs.
 *
 * <p><b>The rollout gate decides whether it does anything.</b> With {@code
 * qits.auth.machine.required} off — the shipped default — {@link #requirePublisher} returns at once
 * and publishing is open exactly as every other wire route here is, on qits-net trust. On, it
 * demands a validated bearer minted for {@code qits-artifacts}. There is no third state, which is
 * the property {@link MachineAuth} carries and this class must not weaken.
 *
 * <p><b>Why the authenticator is called explicitly.</b> This service ships {@code
 * quarkus.http.auth.proactive=false} on purpose: npm sends a mandatory dummy {@code _authToken} on
 * every publish, and proactive auth would parse that ceremony as an OIDC bearer and reject the
 * request before the tokenless npm route could see it. Lazy auth means nothing resolves an identity
 * unless something asks — and on a raw route nothing does, so {@code rc.user()} is null and a guard
 * reading it would pass everything. {@link HttpAuthenticator#attemptAuthentication} is the ask. It
 * runs the same mechanisms and the same {@code quarkus.oidc.token.audience} check the JSON API's
 * requests go through; only the trigger is ours.
 *
 * <p>{@code HttpAuthenticator} lives in {@code io.quarkus.vertx.http.runtime.security}, which
 * carries no compatibility promise — the same acceptable risk {@code registry/OciRequestBody} takes
 * for {@code VertxInputStream}, and for the same reason: an upgrade that moves it breaks <em>this
 * file</em> at compile time rather than shipping a green build that fails in production.
 *
 * <p>Reads are never guarded. A daemon binary must stay downloadable by a bootstrap script that has
 * no token yet — that is the cold-start path this whole type exists to serve — and it is the same
 * stance every read in this service takes.
 */
@ApplicationScoped
public class DaemonPublishGuard {

  /** Long enough for a JWKS fetch on a cold tenant, short enough that a hung idp is not a hang. */
  private static final Duration AUTHENTICATION_TIMEOUT = Duration.ofSeconds(10);

  /** The rollout gate, spelled by {@link MachineAuth}'s own constant so the two cannot drift. */
  @ConfigProperty(name = MachineAuth.REQUIRED_KEY, defaultValue = "false")
  boolean required;

  /**
   * This service's id, the {@code aud} a token must carry. {@code Optional} rather than {@code
   * String} for the reason the mirror's {@code endpoint-override} is: SmallRye reads a
   * configured-empty value as <b>absent</b>, and a non-optional injection would fail the boot.
   */
  @ConfigProperty(name = MachineAuth.AUDIENCE_KEY)
  Optional<String> audience;

  @Inject HttpAuthenticator authenticator;

  /**
   * Demands a machine token addressed to this service, or throws the status the wire should answer.
   *
   * <p>Must be called from a worker thread — it blocks on the authentication.
   *
   * @throws DaemonException {@code 401} with no machine token, {@code 403} with one addressed
   *     elsewhere
   */
  public void requirePublisher(RoutingContext rc) {
    if (!required) {
      return;
    }
    SecurityIdentity identity = authenticate(rc);
    if (!MachineIdentity.isMachine(identity)) {
      throw new DaemonException(
          401, "publishing a daemon binary needs a machine token minted for qits-artifacts");
    }
    // The gate cannot be on without this key: MachineAuth fails the boot on exactly that
    // combination, so an empty Optional here is unreachable rather than a case to invent an answer
    // for.
    String expected = audience.orElseThrow();
    if (!MachineIdentity.hasAudience(identity, expected)) {
      throw new DaemonException(403, "token audience does not include " + expected);
    }
  }

  /**
   * A rejected bearer — expired, wrongly signed, addressed to another service — arrives here as a
   * thrown authentication failure rather than as a null identity. It is a 401 either way: the
   * caller presented nothing this service will act on, and the difference between "no token" and
   * "not a usable token" is a log line, not a status code.
   */
  private SecurityIdentity authenticate(RoutingContext rc) {
    try {
      return authenticator.attemptAuthentication(rc).await().atMost(AUTHENTICATION_TIMEOUT);
    } catch (RuntimeException refused) {
      throw new DaemonException(401, "the machine token was not accepted: " + refused.getMessage());
    }
  }
}
