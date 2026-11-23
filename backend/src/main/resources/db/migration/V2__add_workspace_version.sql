-- Additive change: V1 and all business rows remain intact.
ALTER TABLE flowdesk.workspaces
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_workspaces_version_nonnegative CHECK (version >= 0);
