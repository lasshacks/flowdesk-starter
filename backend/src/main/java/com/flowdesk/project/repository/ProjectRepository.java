package com.flowdesk.project.repository;
import com.flowdesk.project.domain.Project;
import org.springframework.data.repository.Repository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
@Transactional(readOnly=true)
public interface ProjectRepository extends Repository<Project,Long> {
    Slice<Project> findByWorkspaceIdOrderByIdAsc(Long workspaceId,Pageable pageable);
    Slice<Project> findByWorkspaceIdAndArchivedAtIsNullOrderByIdAsc(Long workspaceId,Pageable pageable);
    Optional<Project> findByIdAndWorkspaceId(Long id,Long workspaceId);
}
