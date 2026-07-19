package eu.wohlben.qits.artifactory.mapper;

import eu.wohlben.qits.artifactory.dto.ArtifactRepositoryDto;
import eu.wohlben.qits.artifactory.entity.ArtifactRepository;
import org.mapstruct.Mapper;

@Mapper(componentModel = "jakarta")
public interface ArtifactRepositoryMapper {

  ArtifactRepositoryDto toDto(ArtifactRepository entity);
}
