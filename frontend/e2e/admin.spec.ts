import { expect, test } from '@playwright/test';
import { USERS, farFutureLocalStart } from './support/data.ts';
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
