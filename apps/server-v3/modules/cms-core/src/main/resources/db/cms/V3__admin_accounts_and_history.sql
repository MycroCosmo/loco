CREATE TABLE cms.admin_role (
    id text PRIMARY KEY, code text NOT NULL UNIQUE, name text NOT NULL
);
INSERT INTO cms.admin_role VALUES
    ('00000000-0000-4000-8000-000000000001', 'SYSTEM_ADMIN', '최고관리자');

CREATE TABLE cms.admin_account (
    id text PRIMARY KEY, login_id text NOT NULL UNIQUE, password_hash text NOT NULL,
    display_name text NOT NULL, active boolean NOT NULL, must_change_password boolean NOT NULL,
    auth_version bigint NOT NULL, created_at timestamp(3) NOT NULL, updated_at timestamp(3) NOT NULL
);
CREATE TABLE cms.admin_account_role (
    account_id text NOT NULL REFERENCES cms.admin_account(id),
    role_id text NOT NULL REFERENCES cms.admin_role(id),
    PRIMARY KEY (account_id, role_id)
);
CREATE TABLE cms.administration_state (
    id integer PRIMARY KEY CHECK (id = 1), bootstrap_completed boolean NOT NULL
);
INSERT INTO cms.administration_state VALUES (1, false);

CREATE TABLE cms.change_history (
    id text PRIMARY KEY, actor_id text, action text NOT NULL,
    resource_type text NOT NULL, resource_id text NOT NULL,
    occurred_at timestamp(3) NOT NULL, before_data jsonb, after_data jsonb,
    payload_version integer NOT NULL
);
CREATE INDEX change_history_time ON cms.change_history (occurred_at, id);
CREATE INDEX change_history_resource ON cms.change_history (resource_type, resource_id, occurred_at);
