-- Owned by the security package: users and theater-admin assignments.
-- Demo users are not seeded here; they are added together with the HTTP Basic configuration.

CREATE TABLE app_user (
    id            bigint GENERATED ALWAYS AS IDENTITY CONSTRAINT app_user_pk PRIMARY KEY,
    username      text        NOT NULL,
    password_hash text        NOT NULL,
    role          text        NOT NULL,
    display_name  text        NOT NULL,
    created_at    timestamptz NOT NULL,
    CONSTRAINT app_user_username_uk UNIQUE (username),
    -- Target of theater_admin_assignment's composite foreign key.
    CONSTRAINT app_user_id_role_uk UNIQUE (id, role),
    CONSTRAINT app_user_username_ck CHECK (username ~ '^[a-z0-9._-]{3,50}$'),
    CONSTRAINT app_user_password_hash_ck CHECK (btrim(password_hash) <> ''),
    CONSTRAINT app_user_role_ck CHECK (role IN ('CUSTOMER', 'THEATER_ADMIN')),
    CONSTRAINT app_user_display_name_ck CHECK (btrim(display_name) <> '')
);

-- user_role is fixed to THEATER_ADMIN so the composite foreign key guarantees that only
-- theater admins can be assigned to theaters.
CREATE TABLE theater_admin_assignment (
    user_id    bigint NOT NULL,
    user_role  text   NOT NULL DEFAULT 'THEATER_ADMIN',
    theater_id bigint NOT NULL CONSTRAINT theater_admin_assignment_theater_fk REFERENCES theater (id),
    CONSTRAINT theater_admin_assignment_pk PRIMARY KEY (user_id, theater_id),
    CONSTRAINT theater_admin_assignment_role_ck CHECK (user_role = 'THEATER_ADMIN'),
    CONSTRAINT theater_admin_assignment_user_fk FOREIGN KEY (user_id, user_role) REFERENCES app_user (id, role)
);

CREATE INDEX theater_admin_assignment_theater_idx ON theater_admin_assignment (theater_id);
