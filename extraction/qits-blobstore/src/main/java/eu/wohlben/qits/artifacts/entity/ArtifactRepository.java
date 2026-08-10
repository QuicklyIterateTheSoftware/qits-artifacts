package eu.wohlben.qits.artifacts.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A named, typed container for blobs. The name is the natural key (repositories are addressed by
 * name in the API); the {@link RepositoryType} selects the validation profile enforced on upload.
 */
@Entity
@Table(name = "artifact_repository")
public class ArtifactRepository extends PanacheEntityBase {

  @Id public String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public RepositoryType type;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;
}
