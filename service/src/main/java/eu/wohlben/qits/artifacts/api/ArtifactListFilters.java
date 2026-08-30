package eu.wohlben.qits.artifacts.api;

import eu.wohlben.qits.blobstore.control.ArtifactListFilter;
import eu.wohlben.qits.blobstore.error.BadRequestException;
import java.time.Instant;
import java.time.format.DateTimeParseException;

final class ArtifactListFilters {
  private ArtifactListFilters() {}

  static ArtifactListFilter parse(
      String accessedAfter,
      String accessedBefore,
      String createdAfter,
      String createdBefore,
      String minSize,
      String maxSize,
      String neverAccessed) {
    return new ArtifactListFilter(
        instant("accessed-after", accessedAfter),
        instant("accessed-before", accessedBefore),
        instant("created-after", createdAfter),
        instant("created-before", createdBefore),
        number("min-size", minSize),
        number("max-size", maxSize),
        bool("never-accessed", neverAccessed));
  }

  private static Instant instant(String name, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException invalid) {
      throw new BadRequestException(name + " must be an ISO-8601 instant");
    }
  }

  private static Long number(String name, String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException invalid) {
      throw new BadRequestException(name + " must be an integer");
    }
  }

  private static Boolean bool(String name, String value) {
    if (value == null || value.isBlank()) return null;
    if ("true".equalsIgnoreCase(value)) return true;
    if ("false".equalsIgnoreCase(value)) return false;
    throw new BadRequestException(name + " must be true or false");
  }
}
