-- LOCAL-ONLY DEMO ACCOUNTS. The passwords are fictional, intentionally public, and documented in
-- the README. They must never be used outside a local assessment environment.
--
-- | username      | password           | role          | assigned theater           |
-- |---------------|--------------------|---------------|----------------------------|
-- | alice         | alice-demo-pass    | CUSTOMER      | -                          |
-- | bob           | bob-demo-pass      | CUSTOMER      | -                          |
-- | admin.dallas  | dallas-admin-demo  | THEATER_ADMIN | Starlight Cinemas Downtown |
-- | admin.houston | houston-admin-demo | THEATER_ADMIN | Bayou Picture House        |
--
-- Hashes are BCrypt (cost 10), produced by Spring Security's BCryptPasswordEncoder.

INSERT INTO app_user (username, password_hash, role, display_name, created_at) VALUES
    ('alice',         '$2a$10$LldGIXOMj2DG5N43aZHWEu5Mb72lWtX4CqpoTy.c7lt0vQ9WM2Biq', 'CUSTOMER',      'Alice (demo customer)',        TIMESTAMPTZ '2026-11-02 00:00:00+00'),
    ('bob',           '$2a$10$booG3mHrC6X//uFIe4EebOTK2qffhVXQVGva4jHp3XuOla/bULvrG', 'CUSTOMER',      'Bob (demo customer)',          TIMESTAMPTZ '2026-11-02 00:00:00+00'),
    ('admin.dallas',  '$2a$10$CtdG8gM/G1KJ5YG8uXe5uOP20KKxT7ERYZs7D4AawZNpGrXZqtQz.', 'THEATER_ADMIN', 'Dallas theater admin (demo)',  TIMESTAMPTZ '2026-11-02 00:00:00+00'),
    ('admin.houston', '$2a$10$/1lHjJxD9hpYV7wGektcqeS.UM1LnU2f6gC64VcJ2UDfR1Rx1r3Z2', 'THEATER_ADMIN', 'Houston theater admin (demo)', TIMESTAMPTZ '2026-11-02 00:00:00+00');

INSERT INTO theater_admin_assignment (user_id, theater_id)
SELECT u.id, t.id
FROM (VALUES
    ('admin.dallas',  'Starlight Cinemas Downtown'),
    ('admin.houston', 'Bayou Picture House')
) AS a (username, theater_name)
JOIN app_user u ON u.username = a.username
JOIN theater t ON t.name = a.theater_name;
