CREATE TYPE replay_status AS ENUM
  ('INITIALIZING', 'RECORDING', 'FINALIZING', 'AVAILABLE', 'FAILED', 'DELETING');

CREATE TYPE replay_completion_reason AS ENUM
  ('MANUAL', 'SERVER_SHUTDOWN', 'DURATION_LIMIT', 'BYTE_LIMIT', 'PACKET_LIMIT', 'SEGMENT_LIMIT');

CREATE TYPE replay_failure_code AS ENUM
  ('SERVER_CRASH', 'QUEUE_OVERFLOW', 'ADAPTER_ERROR', 'STORAGE_ERROR',
   'DATABASE_ERROR', 'CORRUPT_DATA', 'INCOMPATIBLE_ADAPTER', 'INTERNAL_ERROR');

CREATE TYPE replay_storage_backend AS ENUM ('LOCAL', 'S3', 'SFTP');

CREATE TYPE replay_metadata_revision_operation AS ENUM ('SET', 'REMOVE', 'REPLACE');

CREATE TABLE replay (
    id UUID PRIMARY KEY,
    title TEXT NOT NULL CHECK (length(btrim(title)) > 0),
    description TEXT NOT NULL,
    status replay_status NOT NULL,
    completion_reason replay_completion_reason,
    failure_code replay_failure_code,
    failure_description TEXT,
    adapter_id TEXT NOT NULL CHECK (length(btrim(adapter_id)) > 0),
    protocol_version INTEGER NOT NULL CHECK (protocol_version >= 0),
    format_revision INTEGER NOT NULL CHECK (format_revision >= 0),
    storage_backend replay_storage_backend NOT NULL,
    storage_key TEXT NOT NULL CHECK (length(btrim(storage_key)) > 0),
    duration_nanos BIGINT NOT NULL DEFAULT 0 CHECK (duration_nanos >= 0),
    total_bytes BIGINT NOT NULL DEFAULT 0 CHECK (total_bytes >= 0),
    packet_count BIGINT NOT NULL DEFAULT 0 CHECK (packet_count >= 0),
    segment_count BIGINT NOT NULL DEFAULT 0 CHECK (segment_count >= 0),
    checkpoint_count BIGINT NOT NULL DEFAULT 0 CHECK (checkpoint_count >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(metadata) = 'object'),
    metadata_revision BIGINT NOT NULL DEFAULT 0
        CHECK (metadata_revision >= 0),
    entity_version BIGINT NOT NULL DEFAULT 0
        CHECK (entity_version >= 0),
    CONSTRAINT replay_time_order CHECK (
        (started_at IS NULL OR started_at >= created_at)
        AND (completed_at IS NULL OR completed_at >= created_at)
        AND (completed_at IS NULL OR started_at IS NULL OR completed_at >= started_at)
    ),
    CONSTRAINT replay_available_complete CHECK (
        status <> 'AVAILABLE'
        OR (completion_reason IS NOT NULL AND completed_at IS NOT NULL)
    ),
    CONSTRAINT replay_failed_diagnosed CHECK (
        status <> 'FAILED'
        OR (failure_code IS NOT NULL AND failure_description IS NOT NULL AND completed_at IS NOT NULL)
    ),
    CONSTRAINT replay_completion_only_published CHECK (
        completion_reason IS NULL OR status IN ('AVAILABLE', 'DELETING')
    ),
    CONSTRAINT replay_failure_only_failed CHECK (
        failure_code IS NULL OR status = 'FAILED'
    )
);

CREATE TABLE metadata_revision (
    id UUID PRIMARY KEY,
    replay_id UUID NOT NULL REFERENCES replay(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL CHECK (revision >= 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    operation replay_metadata_revision_operation NOT NULL,
    snapshot JSONB NOT NULL,
    CONSTRAINT metadata_revision_unique_number UNIQUE (replay_id, revision),
    CONSTRAINT metadata_revision_snapshot_shape CHECK (
        jsonb_typeof(snapshot) = 'object'
        AND snapshot ? 'title'
        AND jsonb_typeof(snapshot -> 'title') = 'string'
        AND length(btrim(snapshot ->> 'title')) > 0
        AND snapshot ? 'description'
        AND jsonb_typeof(snapshot -> 'description') = 'string'
        AND snapshot ? 'values'
        AND jsonb_typeof(snapshot -> 'values') = 'object'
    )
);

CREATE TABLE recording_lease (
    replay_id UUID PRIMARY KEY REFERENCES replay(id) ON DELETE CASCADE,
    runtime_instance_id UUID NOT NULL,
    acquired_at TIMESTAMPTZ NOT NULL,
    heartbeat_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    entity_version BIGINT NOT NULL DEFAULT 0 CHECK (entity_version >= 0),
    CONSTRAINT recording_lease_time_order CHECK (
        heartbeat_at >= acquired_at AND expires_at > heartbeat_at
    )
);

CREATE INDEX replay_status_idx ON replay (status);
CREATE INDEX replay_created_at_idx ON replay (created_at DESC, id ASC);
CREATE INDEX replay_adapter_idx ON replay (adapter_id);
CREATE INDEX replay_metadata_gin_idx ON replay USING GIN (metadata);

CREATE INDEX metadata_revision_replay_idx
    ON metadata_revision (replay_id, revision DESC);

CREATE INDEX recording_lease_expiry_idx
    ON recording_lease (expires_at);

CREATE INDEX recording_lease_runtime_idx
    ON recording_lease (runtime_instance_id);
