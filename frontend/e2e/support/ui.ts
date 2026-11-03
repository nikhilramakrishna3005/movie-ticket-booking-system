import { expect, type Page } from '@playwright/test';
import type { DemoUser, FreshShowtime } from './data.ts';

export function usd(amount: number): string {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(amount);
}

/** Mirrors the app's "Tuesday, March 2" and "1:00 PM" formats. */
export function longDate(date: string): string {
  return new Intl.DateTimeFormat('en-US', { timeZone: 'UTC', weekday: 'long', month: 'long', day: 'numeric' }).format(
    new Date(`${date}T00:00:00Z`),
  );
}

export function localTime(time: string): string {
  const [hours = 0, minutes = 0] = time.split(':').map(Number);
  return `${hours % 12 || 12}:${String(minutes).padStart(2, '0')} ${hours >= 12 ? 'PM' : 'AM'}`;
}

/** Discover as an anonymous visitor: search for the movie, then open the given showtime's seat map. */
export async function openShowtimeFromDiscovery(page: Page, showtime: FreshShowtime) {
  await page.goto(`/?city=${showtime.cityId}&date=${showtime.localDate}`);
  await expect(page.getByTestId('nav-login-button')).toBeVisible();
  await expect(page.getByTestId(`date-option-${showtime.localDate}`)).toHaveAttribute('aria-checked', 'true');

  await page.getByTestId('search-input').fill(showtime.movieTitle);
  // The search is debounced into the URL; waiting for it means the chip is clicked in the filtered results.
  await expect(page).toHaveURL((url) => url.searchParams.get('q') === showtime.movieTitle);
  const card = page.getByTestId('movie-card').and(page.locator(`[data-movie-id="${showtime.movieId}"]`));
  await expect(card).toBeVisible();
  await expect(card.getByRole('heading', { name: showtime.movieTitle })).toBeVisible();
  await card.locator(`[data-testid="showtime-chip"][data-showtime-id="${showtime.showtimeId}"]`).click();

  await expect(page).toHaveURL(new RegExp(`/showtimes/${showtime.showtimeId}/seats$`));
  await expect(page.getByTestId('seat-map')).toBeVisible();
}

export function seat(page: Page, name: string) {
  return page.getByTestId('seat-map').locator(`[data-testid="seat"][data-seat-name="${name}"]`);
}

/** Selects seats only after proving they are still available, so a test never picks a taken seat. */
export async function selectSeats(page: Page, names: string[]) {
  for (const name of names) {
    const target = seat(page, name);
    await expect(target).toHaveAttribute('data-status', 'AVAILABLE');
    await target.click();
    await expect(target).toHaveAttribute('aria-pressed', 'true');
  }
}

export async function submitLogin(page: Page, user: DemoUser) {
  const dialog = page.getByTestId('login-dialog');
  await expect(dialog).toBeVisible();
  await dialog.getByTestId('login-username').fill(user.username);
  await dialog.getByTestId('login-password').fill(user.password);
  await dialog.getByTestId('login-submit').click();
  await expect(dialog).toBeHidden();
  await expect(page.getByTestId('nav-user-name')).toBeVisible();
}

export async function chooseScenario(page: Page, scenario: 'SUCCESS' | 'DECLINE' | 'TIMEOUT') {
  const radio = page.getByTestId(`scenario-${scenario}`);
  await page.locator('label').filter({ has: radio }).click();
  await expect(radio).toBeChecked();
}
