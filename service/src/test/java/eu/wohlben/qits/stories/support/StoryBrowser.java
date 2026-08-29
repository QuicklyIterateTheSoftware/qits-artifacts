package eu.wohlben.qits.stories.support;

import eu.wohlben.qits.userflows.Flow;
import java.util.Map;

/** The gateway's job, played for a browser story. */
public final class StoryBrowser {

  /** The signed-in operator every explorer story browses as. */
  public static final String OPERATOR = "story-operator";

  private StoryBrowser() {}

  /**
   * Assert the header pair qits-gateway would, on the browser context, <b>before the first
   * navigate</b>.
   *
   * <p>Every JSON read the SPA makes is {@code @RolesAllowed("qits:admin")}, answered from the
   * {@code X-Qits-User}/{@code X-Qits-Roles} pair qits-gateway injects for a signed-in operator —
   * and the packaged process runs {@code LaunchMode.NORMAL}, where {@code ForwardAuthMechanism}
   * deliberately stays anonymous and there is no {@code %test} synthetic identity to answer
   * instead. Without these headers every fetch is an anonymous 401 and the page renders its error
   * states, which is a screenshot of the harness rather than of the product.
   *
   * <p>{@code Flow.page()} records nothing, and that is right here: this is harness plumbing, not a
   * step in anybody's story.
   */
  public static void asOperator(Flow flow) {
    flow.page()
        .setExtraHTTPHeaders(Map.of("X-Qits-User", OPERATOR, "X-Qits-Roles", "qits:admin"));
  }
}
