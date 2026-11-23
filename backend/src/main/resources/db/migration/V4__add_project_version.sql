-- V1/V2/V3 and existing Project fields remain unchanged.
ALTER TABLE flowdesk.projects
    ADD COLUMN version bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_projects_version_nonnegative CHECK (version >= 0);
