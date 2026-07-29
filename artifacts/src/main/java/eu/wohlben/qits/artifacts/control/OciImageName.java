package eu.wohlben.qits.artifacts.control;

import eu.wohlben.qits.artifacts.error.OciCode;
import eu.wohlben.qits.artifacts.error.OciException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * An OCI {@code <name>} split into the artifact repository and the image within it.
 *
 * <p>A registry name may contain slashes, and this deployment reads the <b>first</b> segment as an
 * {@code OCI_IMAGES} row in {@code artifact_repository} and everything after it as the image. So
 * {@code qits/build-images/ci-base} is repository {@code qits}, image {@code build-images/ci-base}.
 *
 * <p>That has a consequence worth stating rather than discovering: a <b>single-segment</b> reference
 * has no image name at all. {@code docker push <host>/alpine:latest} is a reference docker will
 * happily emit, and here it is unpushable by design — so it is a named failure with a message that
 * says what to write instead, not a 404 that reads as "your registry is broken".
 */
public record OciImageName(String repository, String image, String full) {

  /** One path component of a name, per the Distribution spec's grammar. */
  private static final String COMPONENT = "[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*";

  private static final Pattern NAME =
      Pattern.compile(COMPONENT + "(?:/" + COMPONENT + ")*");

  /**
   * Splits a name.
   *
   * @throws OciException {@code NAME_INVALID} if the name fails the grammar or has no image segment
   */
  public static OciImageName parse(String name) {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new OciException(
          OciCode.NAME_INVALID,
          "image name is not a valid OCI repository name",
          Map.of("name", String.valueOf(name)));
    }
    int slash = name.indexOf('/');
    if (slash < 0) {
      throw new OciException(
          OciCode.NAME_INVALID,
          "image references must be <repository>/<image>, e.g. qits/alpine",
          Map.of("name", name));
    }
    return new OciImageName(name.substring(0, slash), name.substring(slash + 1), name);
  }
}
