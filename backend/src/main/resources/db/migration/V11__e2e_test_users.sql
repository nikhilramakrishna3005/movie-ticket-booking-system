-- LOCAL-ONLY AUTOMATED-TEST ACCOUNTS. The passwords are fictional, intentionally public, and
-- documented in the README. They exist so Playwright can book without polluting Alice and Bob's
-- My Tickets. They must never be used outside a local assessment environment.
--
-- | username  | password             | role     |
-- |-----------|----------------------|----------|
-- | e2e.alice | e2e-alice-demo-pass  | CUSTOMER |
-- | e2e.bob   | e2e-bob-demo-pass    | CUSTOMER |
--
-- Hashes are BCrypt (cost 10), produced by Spring Security's BCrypt.hashpw.

INSERT INTO app_user (username, password_hash, role, display_name, created_at) VALUES
    ('e2e.alice', '$2a$10$9ylpgocHHd7WzDT4GwNJqOGXPS7ZXYbX.SLzI6u7h2AvJAgsPBJhy', 'CUSTOMER',
     'E2E Alice (automated tests)', TIMESTAMPTZ '2026-11-03 00:00:00+00'),
    ('e2e.bob',   '$2a$10$4.N7EnO7L34prT5/A7FWEeMUMb.e3f6JPjjmuZtneI.9EaV1xcKbK', 'CUSTOMER',
     'E2E Bob (automated tests)',   TIMESTAMPTZ '2026-11-03 00:00:00+00');
