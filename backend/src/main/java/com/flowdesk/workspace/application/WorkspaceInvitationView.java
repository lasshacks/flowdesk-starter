package com.flowdesk.workspace.application;

import com.flowdesk.workspace.domain.WorkspaceRole;
import java.time.Instant;

/** Contains the raw invite code only in the one-time issue response. */
public record WorkspaceInvitationView(String id, WorkspaceRole role, Instant expiresAt, String code) { }
