import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests run against the real Docker Compose stack (`docker compose up --build`), not a
 * dev server, so they exercise nginx, the Spring Boot API and PostgreSQL together.
 * Retries stay at 0: a flaky failure must be visible, not retried away.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
