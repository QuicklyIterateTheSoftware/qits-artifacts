package eu.wohlben.qits.artifacts.gc.dto;

import java.time.Instant;
import java.util.List;

/**
 * One pin source, as this run read it: who was asked, at what url, what came back, and which
 * keep-identities the answer resolved to.
 *
 * <p>Pins are the one keep-class no timestamp in this store implies, and they arrive over the
 * network from a service this one does not own. So the report cannot state them as a fact and leave
 * it there: a reviewer has to be able to check that the shas here are the shas their deployments are
 * running, and to see whether a source answered <b>slowly</b> — a pin source that took nine seconds
 * today is the outage that aborts every run next week.
 *
 * <p>The keeps are the resolved identities rather than the raw payload, because that is the form a
 * reviewer can compare against the {@code kept} lists further down the report. An empty keeps list
 * beside {@code answered: true} is an answer too: qits-ci saying no daemon is pinned.
 *
 * @param source the service asked — {@code qits-platform-deployments} or {@code qits-ci}
 * @param url the full url this run called, so a reviewer can repeat the request by hand
 * @param answered whether it answered at all. False is what makes the whole run refuse: a sweep
 *     aborts before the census and a plan reports itself non-executable.
 * @param outcome one sentence — what it answered, or why it could not
 * @param readAt when the call was made. Pins are never cached, so this is always within this run.
 * @param tookMillis how long it took, including a connect that failed
 * @param pinCount how many pins the source reported: applications for qits-platform-deployments, ladder rungs for
 *     qits-ci
 * @param keeps the keep-identities the answer resolved to, sorted — {@code qits-ci:3ff84c05…} per
 *     pinned image sha, {@code qits-ci-daemon@2026.802.40} per rung, and {@code blob <digest>} for a
 *     rung spelled as a sha256, which pins bytes no row may exist for
 */
public record GcPinSource(
    String source,
    String url,
    boolean answered,
    String outcome,
    Instant readAt,
    long tookMillis,
    int pinCount,
    List<String> keeps) {}
