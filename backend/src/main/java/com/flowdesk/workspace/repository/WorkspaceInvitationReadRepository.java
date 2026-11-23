package com.flowdesk.workspace.repository;

import com.flowdesk.workspace.domain.WorkspaceInvitation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Transactional(readOnly = true)
public interface WorkspaceInvitationReadRepository extends Repository<WorkspaceInvitation, Long> {
    interface InvitationProjection {
        Long getId();
        String getTargetDisplayName();
        String getTargetEmail();
        String getRole();
        Instant getCreatedAt();
        Instant getExpiresAt();
        Boolean getExpired();
    }

    @Query(value = """
            SELECT i.id AS id, u.display_name AS "targetDisplayName", u.email AS "targetEmail",
                   i.role AS role, i.created_at AS "createdAt", i.expires_at AS "expiresAt",
                   (i.expires_at <= :now) AS expired
            FROM flowdesk.workspace_invitations i
            JOIN flowdesk.users u ON u.id = i.target_user_id
            WHERE i.workspace_id = :workspaceId AND i.status = 'PENDING'
            ORDER BY i.id DESC
            """, nativeQuery = true)
    Slice<InvitationProjection> findPendingByWorkspaceId(
            @Param("workspaceId") long workspaceId, @Param("now") Instant now, Pageable pageable);
}
