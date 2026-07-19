package eu.wohlben.qits.artifacts.mapper;

import eu.wohlben.qits.artifacts.dto.ArtifactRepositoryDto;
import eu.wohlben.qits.artifacts.entity.ArtifactRepository;
import org.mapstruct.Mapper;

@Mapper(componentModel = "jakarta")
public interface ArtifactRepositoryMapper {

  ArtifactRepositoryDto toDto(ArtifactRepository entity);
}
