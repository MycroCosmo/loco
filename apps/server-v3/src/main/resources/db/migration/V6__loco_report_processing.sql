-- Host-owned CMS extension; no FK or change to public.reports.
CREATE TABLE cms.loco_report_processing (
    report_id text PRIMARY KEY,
    status text NOT NULL CHECK (status IN ('RECEIVED','IN_REVIEW','RESOLVED','DISMISSED')),
    note text,
    handled_by text,
    handled_at timestamp(3),
    CHECK (note IS NULL OR (char_length(note) BETWEEN 1 AND 1000)),
    CHECK ((status = 'RECEIVED' AND note IS NULL AND handled_by IS NULL AND handled_at IS NULL)
        OR (status <> 'RECEIVED' AND handled_by IS NOT NULL AND handled_at IS NOT NULL)),
    CHECK (status NOT IN ('RESOLVED','DISMISSED') OR note IS NOT NULL)
);
