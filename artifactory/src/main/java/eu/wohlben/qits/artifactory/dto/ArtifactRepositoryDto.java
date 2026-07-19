package eu.wohlben.qits.artifactory.dto;

import eu.wohlben.qits.artifactory.entity.RepositoryType;
import java.time.Instant;

public record ArtifactRepositoryDto(String name, RepositoryType type, Instant createdAt) {}
