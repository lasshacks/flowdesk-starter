package com.flowdesk.workspace.repository;

import com.flowdesk.workspace.domain.Workspace;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Transactional(readOnly = true)
public interface WorkspaceRepository extends Repository<Workspace, Long> {
    Optional<Workspace> findById(Long id);
}
