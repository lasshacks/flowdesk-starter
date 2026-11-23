package com.flowdesk.workspace.application;

import com.flowdesk.identity.application.DevActorProvider;
import com.flowdesk.identity.repository.UserRepository;
import com.flowdesk.workspace.domain.WorkspaceInvitation;
import com.flowdesk.workspace.domain.WorkspaceInvitationStatus;
import com.flowdesk.workspace.domain.WorkspaceMembership;
import com.flowdesk.workspace.domain.WorkspaceRole;
import com.flowdesk.workspace.repository.WorkspaceInvitationCommandRepository;
import com.flowdesk.workspace.repository.WorkspaceMembershipCommandRepository;
import com.flowdesk.workspace.repository.WorkspaceMembershipRepository;
import com.flowdesk.workspace.repository.WorkspaceWriteLockRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

@Service
@Profile("dev-actor")
@ConditionalOnProperty(name = "flowdesk.dev.workspace-write-enabled", havingValue = "true")
public class WorkspaceMembershipManagementService {
    private static final Duration INVITATION_LIFETIME = Duration.ofDays(7);
    private final DevActorProvider actors;
    private final UserRepository users;
    private final WorkspaceMembershipRepository memberships;
    private final WorkspaceMembershipCommandRepository membershipCommands;
    private final WorkspaceInvitationCommandRepository invitations;
    private final WorkspaceWriteLockRepository locks;

    public WorkspaceMembershipManagementService(DevActorProvider actors, UserRepository users,
            WorkspaceMembershipRepository memberships, WorkspaceMembershipCommandRepository membershipCommands,
            WorkspaceInvitationCommandRepository invitations, WorkspaceWriteLockRepository locks) {
        this.actors = actors;
        this.users = users;
        this.memberships = memberships;
        this.membershipCommands = membershipCommands;
        this.invitations = invitations;
        this.locks = locks;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public WorkspaceInvitationView issue(long workspaceId, String email, WorkspaceRole role) {
        locks.lockForMembershipChange(workspaceId);
        long actorId = actors.current().userId();
        requireManager(workspaceId, actorId);
        if (role == null || role == WorkspaceRole.OWNER) throw new IllegalArgumentException("role");
        String normalizedEmail = normalizeEmail(email);
        var target = users.findByEmail(normalizedEmail).orElseThrow(WorkspaceMemberUnavailableException::new);
        if (memberships.existsByWorkspace_IdAndUser_Id(workspaceId, target.getId()))
            throw new WorkspaceMembershipConflictException();

        var prior = invitations.findPendingTargetForUpdate(workspaceId, target.getId());
        if (prior.isPresent()) {
            prior.get().revoke();
            invitations.flush();
        }
        String code = WorkspaceInvitationCodes.create();
        Instant expiresAt = Instant.now().plus(INVITATION_LIFETIME);
        var invitation = WorkspaceInvitation.pending(workspaceId, target.getId(), actorId, role,
                WorkspaceInvitationCodes.digest(code), expiresAt);
        invitations.persist(invitation);
        return new WorkspaceInvitationView(invitation.getId().toString(), role, expiresAt, code);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void cancel(long workspaceId, long invitationId) {
        locks.lockForMembershipChange(workspaceId);
        requireManager(workspaceId, actors.current().userId());
        var invitation = invitations.findByIdForUpdate(workspaceId, invitationId)
                .orElseThrow(WorkspaceInvitationUnavailableException::new);
        if (invitation.getStatus() != WorkspaceInvitationStatus.PENDING)
            throw new WorkspaceMembershipConflictException();
        invitation.revoke();
        invitations.flush();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public WorkspaceMemberView accept(String code) {
        if (!WorkspaceInvitationCodes.validShape(code)) throw new WorkspaceInvitationUnavailableException();
        String digest = WorkspaceInvitationCodes.digest(code);
        var hint = invitations.findByDigest(digest).orElseThrow(WorkspaceInvitationUnavailableException::new);
        locks.lockForMembershipChange(hint.getWorkspaceId());
        long actorId = actors.current().userId();
        var invitation = invitations.findByDigestForUpdate(digest)
                .orElseThrow(WorkspaceInvitationUnavailableException::new);
        Instant now = Instant.now();
        if (invitation.getStatus() != WorkspaceInvitationStatus.PENDING || !now.isBefore(invitation.getExpiresAt())
                || invitation.getTargetUserId() != actorId) {
            throw new WorkspaceInvitationUnavailableException();
        }
        if (memberships.existsByWorkspace_IdAndUser_Id(invitation.getWorkspaceId(), actorId))
            throw new WorkspaceMembershipConflictException();
        var member = membershipCommands.add(invitation.getWorkspaceId(), actorId, invitation.getRole());
        invitation.accept(now);
        invitations.flush();
        return WorkspaceMemberView.from(invitation.getWorkspaceId(), member);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public WorkspaceMemberView changeRole(long workspaceId, long targetUserId, WorkspaceRole role) {
        locks.lockForMembershipChange(workspaceId);
        long actorId = actors.current().userId();
        requireManager(workspaceId, actorId);
        if (role == null || role == WorkspaceRole.OWNER) throw new IllegalArgumentException("role");
        var target = membershipCommands.findForUpdate(workspaceId, targetUserId)
                .orElseThrow(WorkspaceMemberUnavailableException::new);
        if (target.getRole() == WorkspaceRole.OWNER) throw new WorkspaceMembershipConflictException();
        if (target.getRole() != role) {
            target.changeRole(role);
            membershipCommands.flush();
        }
        return WorkspaceMemberView.from(workspaceId, target);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void remove(long workspaceId, long targetUserId) {
        locks.lockForMembershipChange(workspaceId);
        long actorId = actors.current().userId();
        requireManager(workspaceId, actorId);
        var target = membershipCommands.findForUpdate(workspaceId, targetUserId)
                .orElseThrow(WorkspaceMemberUnavailableException::new);
        if (target.getRole() == WorkspaceRole.OWNER) throw new WorkspaceMembershipConflictException();
        membershipCommands.remove(target);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public WorkspaceOwnershipView transferOwnership(long workspaceId, long targetUserId) {
        locks.lockForMembershipChange(workspaceId);
        long actorId = actors.current().userId();
        var owner = membershipCommands.findForUpdate(workspaceId, actorId)
                .orElseThrow(WorkspaceNotFoundException::new);
        if (owner.getRole() != WorkspaceRole.OWNER) throw new WorkspaceForbiddenException();
        if (targetUserId == actorId) throw new WorkspaceMembershipConflictException();
        var target = membershipCommands.findForUpdate(workspaceId, targetUserId)
                .orElseThrow(WorkspaceMemberUnavailableException::new);
        if (target.getRole() == WorkspaceRole.OWNER) throw new WorkspaceMembershipConflictException();

        owner.changeRole(WorkspaceRole.ADMIN);
        membershipCommands.flush(); // Free the partial unique OWNER index before promoting the target.
        target.changeRole(WorkspaceRole.OWNER);
        membershipCommands.flush();
        if (membershipCommands.ownerCount(workspaceId) != 1) throw new WorkspaceMembershipConflictException();
        return new WorkspaceOwnershipView(Long.toString(workspaceId), Long.toString(targetUserId));
    }

    private WorkspaceMembership requireManager(long workspaceId, long actorId) {
        var actor = membershipCommands.findForUpdate(workspaceId, actorId).orElseThrow(WorkspaceNotFoundException::new);
        if (actor.getRole() != WorkspaceRole.OWNER && actor.getRole() != WorkspaceRole.ADMIN)
            throw new WorkspaceForbiddenException();
        return actor;
    }

    private static String normalizeEmail(String email) {
        if (email == null) throw new IllegalArgumentException("email");
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 254 || normalized.codePoints().anyMatch(Character::isISOControl)
                || normalized.codePoints().anyMatch(Character::isWhitespace)) throw new IllegalArgumentException("email");
        return normalized;
    }
}
