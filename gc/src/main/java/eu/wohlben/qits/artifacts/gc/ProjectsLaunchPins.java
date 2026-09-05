package eu.wohlben.qits.artifacts.gc;

/**
 * The images an agent or refinement start would pull today — qits-projects answering for itself.
 *
 * <p>{@code GET /projects/api/pins}, read out of that service's own resolved {@code
 * qits.projects.agent-image-repo}/{@code -version} and {@code qits.projects.refinement-image-repo}/
 * {@code -version}. The sixth pin source, on exactly the terms {@link WorkspacesLaunchPins} is on:
 * the consumer that pulls is the only thing that knows the effective value, so it is asked rather
 * than inferred.
 *
 * <p>A separate port from its twin because it is a separate service — its own url, its own entry in
 * a run's pins section, and its own failure. {@link LaunchImagePins} holds the shape they share.
 */
@FunctionalInterface
public interface ProjectsLaunchPins extends LaunchImagePins {}
