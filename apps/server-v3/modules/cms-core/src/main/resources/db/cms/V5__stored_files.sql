CREATE TABLE cms.stored_file (
    id text PRIMARY KEY,
    object_key text NOT NULL UNIQUE,
    original_name text NOT NULL,
    media_type text,
    byte_size bigint,
    sha256 text,
    status text NOT NULL CHECK (status IN ('UPLOADING','ACTIVE','FAILED','ABANDONED','DELETE_PENDING')),
    created_by text NOT NULL,
    created_at timestamp(3) NOT NULL,
    delete_attempts integer NOT NULL DEFAULT 0 CHECK (delete_attempts >= 0),
    CHECK (status NOT IN ('ACTIVE','DELETE_PENDING') OR (media_type IS NOT NULL AND byte_size > 0 AND sha256 IS NOT NULL))
);
CREATE INDEX stored_file_status_time ON cms.stored_file (status, created_at, id);
