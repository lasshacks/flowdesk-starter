package com.flowdesk.workspace.repository;

import com.flowdesk.workspace.domain.WorkspaceMembership;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Transactional(readOnly = true)
public interface WorkspaceMembershipRepository extends Repository<WorkspaceMembership, Long> {
    Optional<WorkspaceMembership> findByWorkspace_IdAndUser_Id(Long workspaceId, Long userId);
    boolean existsByWorkspace_IdAndUser_Id(Long workspaceId, Long userId);
    @EntityGraph(attributePaths = "workspace")
    Slice<WorkspaceMembership> findByUser_IdOrderByWorkspace_IdAsc(Long userId, Pageable pageable);

    interface MemberProjection {
        Long getId();
        String getDisplayName();
        String getRole();
    }

    @Query(value = """
            SELECT u.id AS id, u.display_name AS \"displayName\", m.role AS role
            FROM flowdesk.workspace_memberships m
            JOIN flowdesk.users u ON u.id = m.user_id
            WHERE m.workspace_id = :workspaceId
            ORDER BY u.id ASC
            """, nativeQuery = true)
    Slice<MemberProjection> findMembersByWorkspaceId(@Param("workspaceId") Long workspaceId, Pageable pageable);
}
