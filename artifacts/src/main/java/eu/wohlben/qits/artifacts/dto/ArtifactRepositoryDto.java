package eu.wohlben.qits.artifacts.dto;

import eu.wohlben.qits.artifacts.entity.RepositoryType;
import java.time.Instant;

public record ArtifactRepositoryDto(String name, RepositoryType type, Instant createdAt) {}
