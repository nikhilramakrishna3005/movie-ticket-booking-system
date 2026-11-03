import { expect, test } from '@playwright/test';
import { USERS, createFreshShowtime, farFutureLocalStart } from './support/data.ts';
import { localTime, openShowtimeFromDiscovery, submitLogin } from './support/ui.ts';

const MAX_ATTEMPTS = 20;
const MOVIE = 'Parasite';
const SCREEN = 'Screen 1';

test('a customer cannot open theater administration', async ({ page }) => {
  await page.goto('/admin');
  await expect(page.getByTestId('admin-signed-out')).toBeVisible();
  await page.getByTestId('admin-sign-in').click();
  await submitLogin(page, USERS.alice);
  await expect(page.getByTestId('admin-not-admin')).toBeVisible();
  await expect(page.getByTestId('nav-my-tickets')).toBeVisible();
  await expect(page.getByTestId('nav-admin')).toHaveCount(0);
});

test('an admin schedules a showtime that then appears in public discovery', async ({ page }) => {
  await page.goto('/admin');
  await page.getByTestId('admin-sign-in').click();
  await submitLogin(page, USERS.dallasAdmin);
  await expect(page.getByTestId('admin-dashboard')).toBeVisible();
  await expect(page.getByTestId('nav-admin')).toBeVisible();
  await expect(page.getByTestId('nav-my-tickets')).toHaveCount(0);
  await expect(page.getByTestId('admin-theater-name').or(page.getByTestId('admin-theater-select')))
    .toContainText(/Starlight Cinemas Downtown|Starlight/);

  await expect(page.getByTestId('admin-create-showtime')).toBeVisible();
  await page.getByTestId('admin-showtime-movie').selectOption({ label: MOVIE });
  await page.getByTestId('admin-showtime-screen').selectOption({ label: SCREEN });
  await page.getByTestId('admin-showtime-language').selectOption({ label: 'English' });
  await page.getByTestId('admin-showtime-standard').fill('12.50');
  await page.getByTestId('admin-showtime-premium').fill('16.00');

  let created: { showtimeId: number; movieId: number; movieTitle: string; cityId: number; localDate: string; localStartTime: string } | null =
    null;
  for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
    const slot = farFutureLocalStart();
    await page.getByTestId('admin-showtime-date').fill(slot.date);
    await page.getByTestId('admin-showtime-time').fill(slot.time);
    await page.getByTestId('admin-showtime-submit').click();

    const success = page.getByTestId('admin-showtime-success');
    const error = page.getByTestId('admin-showtime-error');
    await expect(success.or(error)).toBeVisible();
    if (await error.isVisible()) {
      await expect(error).toContainText(/overlap|busy|start time/i);
      continue;
    }

    await expect(success).toBeVisible();
    const card = page.locator('[data-testid="admin-showtime"]').filter({ hasText: MOVIE }).filter({
      hasText: localTime(`${slot.time}:00`),
    });
    await expect(card).toBeVisible();
    const showtimeId = Number(await card.getAttribute('data-showtime-id'));
    const movieId = Number(await card.getAttribute('data-movie-id'));
    created = {
      showtimeId,
      movieId,
      movieTitle: MOVIE,
      cityId: 1,
      localDate: slot.date,
      localStartTime: `${slot.time}:00`,
    };
    break;
  }
  expect(created, `No free slot after ${MAX_ATTEMPTS} attempts`).not.toBeNull();

  await page.getByTestId('nav-logout-button').click();
  await expect(page.getByTestId('nav-login-button')).toBeVisible();

  await openShowtimeFromDiscovery(page, {
    ...created!,
    theaterName: 'Starlight Cinemas Downtown',
    theaterAddress: '',
    standardPrice: 12.5,
    premiumPrice: 16,
  });
  await expect(page.getByTestId('continue-button')).toBeVisible();
});

test('a showtime scheduled on the date being viewed appears in that schedule immediately', async ({ page }) => {
  await page.goto('/admin');
  await page.getByTestId('admin-sign-in').click();
  await submitLogin(page, USERS.dallasAdmin);
  await expect(page.getByTestId('admin-create-showtime')).toBeVisible();
  await page.getByTestId('admin-showtime-movie').selectOption({ label: MOVIE });
  await page.getByTestId('admin-showtime-screen').selectOption({ label: SCREEN });
  await page.getByTestId('admin-showtime-language').selectOption({ label: 'English' });

  let showtimeId: number | null = null;
  for (let attempt = 0; attempt < MAX_ATTEMPTS && showtimeId === null; attempt++) {
    const slot = farFutureLocalStart();
    await page.getByTestId('admin-date-input').fill(slot.date);
    await expect(page).toHaveURL(new RegExp(`date=${slot.date}`));
    await expect(page.getByTestId('admin-schedule-empty').or(page.getByTestId('admin-showtime').first())).toBeVisible();

    await page.getByTestId('admin-showtime-date').fill(slot.date);
    await page.getByTestId('admin-showtime-time').fill(slot.time);
    const response = page.waitForResponse((r) => r.url().endsWith('/api/admin/showtimes') && r.request().method() === 'POST');
    await page.getByTestId('admin-showtime-submit').click();
    const created = await response;
    if (created.status() === 409) continue;
    expect(created.status(), await created.text()).toBe(201);
    showtimeId = ((await created.json()) as { showtimeId: number }).showtimeId;

    await expect(page).toHaveURL(new RegExp(`date=${slot.date}`));
    await expect(page.locator(`[data-testid="admin-showtime"][data-showtime-id="${showtimeId}"]`)).toBeVisible();
  }
  expect(showtimeId, `No free slot after ${MAX_ATTEMPTS} attempts`).not.toBeNull();
});

test('the screens and schedule columns never overlap at any viewport width', async ({ page }) => {
  await page.goto('/admin');
  await page.getByTestId('admin-sign-in').click();
  await submitLogin(page, USERS.dallasAdmin);
  // The 14-day date strip is what used to squeeze the screens column, so it must be on screen.
  await expect(page.getByRole('radiogroup', { name: 'Show date' })).toBeVisible();
  await expect(page.getByTestId('admin-screen-card').first()).toBeVisible();

  const screens = page.getByRole('region', { name: 'Screens' });
  const schedule = page.getByRole('region', { name: 'Showtimes' });
  for (const width of [375, 768, 1024, 1280, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    const screensBox = (await screens.boundingBox())!;
    const scheduleBox = (await schedule.boundingBox())!;
    const sideBySide = screensBox.x + screensBox.width <= scheduleBox.x;
    const stacked = screensBox.y + screensBox.height <= scheduleBox.y;
    expect(sideBySide || stacked, `columns overlap at ${width}px`).toBe(true);
    expect(screensBox.width, `screens column width at ${width}px`).toBeGreaterThanOrEqual(Math.min(352, width - 32));

    for (const card of await page.getByTestId('admin-screen-card').all()) {
      const box = (await card.boundingBox())!;
      expect(box.x, `screen card left edge at ${width}px`).toBeGreaterThanOrEqual(screensBox.x - 0.5);
      expect(box.x + box.width, `screen card right edge at ${width}px`).toBeLessThanOrEqual(screensBox.x + screensBox.width + 0.5);
    }
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow, `horizontal page overflow at ${width}px`).toBeLessThanOrEqual(0);
  }
});

test('an admin cancels a showtime and a customer cannot book it', async ({ page, request }) => {
  const showtime = await createFreshShowtime(request, 'Parasite');

  await page.goto(`/admin?theater=${showtime.theaterId}&date=${showtime.localDate}`);
  await page.getByTestId('admin-sign-in').click();
  await submitLogin(page, USERS.dallasAdmin);
  await expect(page.getByTestId('admin-dashboard')).toBeVisible();

  const card = page.locator(`[data-testid="admin-showtime"][data-showtime-id="${showtime.showtimeId}"]`);
  await expect(card).toBeVisible();
  await card.getByTestId('admin-cancel-showtime').click();
  await expect(card.getByTestId('admin-showtime-cancelled')).toBeVisible();
  await expect(card.getByTestId('admin-cancel-showtime')).toHaveCount(0);

  await page.getByTestId('nav-logout-button').click();
  await expect(page.getByTestId('nav-login-button')).toBeVisible();

  const listed = await request.get(
    `/api/showtimes?cityId=${showtime.cityId}&date=${showtime.localDate}&movieId=${showtime.movieId}&size=50`,
  );
  expect(listed.status()).toBe(200);
  const ids = ((await listed.json()) as { items: Array<{ showtimeId: number }> }).items.map((item) => item.showtimeId);
  expect(ids).not.toContain(showtime.showtimeId);

  await page.goto(`/showtimes/${showtime.showtimeId}/seats`);
  await expect(page.getByTestId('showtime-cancelled')).toBeVisible();
  await expect(page.getByTestId('continue-button')).toBeDisabled();
});
