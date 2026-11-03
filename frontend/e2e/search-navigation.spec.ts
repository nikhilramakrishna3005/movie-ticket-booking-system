import { expect, test, type Page } from '@playwright/test';
import { createFreshShowtime } from './support/data.ts';

/** Must match SEARCH_DEBOUNCE_MS in src/pages/DiscoverPage.tsx. */
const SEARCH_DEBOUNCE_MS = 300;

/**
 * React Router applies navigations inside a React transition, so the page being left stays
 * mounted until React gets to render the next one. React schedules that work through
 * MessagePort messages. Holding them reproduces, deterministically, a device that is still busy
 * rendering when the search debounce expires.
 */
async function installRenderGate(page: Page) {
  await page.addInitScript(() => {
    const post = MessagePort.prototype.postMessage;
    const held: Array<() => void> = [];
    let holding = false;
    MessagePort.prototype.postMessage = function (this: MessagePort, message: unknown) {
      if (holding) held.push(() => post.call(this, message));
      else post.call(this, message);
    };
    Object.assign(window, {
      renderGate: {
        hold: () => {
          holding = true;
        },
        release: () => {
          holding = false;
          held.splice(0).forEach((send) => send());
        },
      },
    });
  });
}

type GatedWindow = Window & { renderGate: { hold: () => void; release: () => void } };

test('a showtime opened while the search debounce is pending stays open, and Back returns to Discover', async ({ page, request }) => {
  const showtime = await createFreshShowtime(request, 'Interstellar');
  const discoverUrl = `/?city=${showtime.cityId}&date=${showtime.localDate}`;
  const seatsUrl = new RegExp(`/showtimes/${showtime.showtimeId}/seats$`);
  await installRenderGate(page);
  await page.clock.install();

  await page.goto(discoverUrl);
  const chip = page.locator(`[data-testid="showtime-chip"][data-showtime-id="${showtime.showtimeId}"]`);
  await expect(chip).toBeVisible();
  // From here on, timers (including the search debounce) only run when the test advances the clock.
  await page.clock.pauseAt((await page.evaluate(() => Date.now())) + 1_000);

  await page.getByTestId('search-input').fill(showtime.movieTitle);
  await expect(page).toHaveURL((url) => !url.searchParams.has('q'));

  await page.evaluate(() => (window as unknown as GatedWindow).renderGate.hold());
  await chip.click();
  await expect(page).toHaveURL(seatsUrl);
  // The debounce expires while Discover is still mounted. It must not navigate back to Discover.
  await page.clock.runFor(SEARCH_DEBOUNCE_MS);
  await expect(page).toHaveURL(seatsUrl);
  await page.evaluate(() => (window as unknown as GatedWindow).renderGate.release());
  await page.clock.resume();

  await expect(page.getByTestId('seat-map')).toBeVisible();
  await expect(page).toHaveURL(seatsUrl);

  await page.goBack();
  await expect(page).toHaveURL((url) => url.pathname === '/'
    && url.searchParams.get('city') === String(showtime.cityId)
    && url.searchParams.get('date') === showtime.localDate);
  await expect(page.getByTestId(`date-option-${showtime.localDate}`)).toHaveAttribute('aria-checked', 'true');
  await expect(chip).toBeVisible();

  await page.goForward();
  await expect(page).toHaveURL(seatsUrl);
  await expect(page.getByTestId('seat-map')).toBeVisible();
});

test('a search typed on Discover still filters the results and is kept in the history entry', async ({ page, request }) => {
  const showtime = await createFreshShowtime(request, 'Interstellar');
  await page.goto(`/?city=${showtime.cityId}&date=${showtime.localDate}`);
  await expect(page.locator('[data-testid="showtime-chip"]').first()).toBeVisible();

  const search = page.getByRole('searchbox', { name: 'Search movies' });
  await search.fill('Intersteller');
  await expect(page).toHaveURL((url) => url.searchParams.get('q') === 'Intersteller');
  await expect(page.getByTestId('movie-card')).toHaveCount(1);
  await expect(page.getByTestId('movie-card').getByRole('heading', { name: showtime.movieTitle })).toBeVisible();

  await page.locator(`[data-testid="showtime-chip"][data-showtime-id="${showtime.showtimeId}"]`).click();
  await expect(page).toHaveURL(new RegExp(`/showtimes/${showtime.showtimeId}/seats$`));
  await page.goBack();
  await expect(page).toHaveURL((url) => url.searchParams.get('q') === 'Intersteller');
  await expect(search).toHaveValue('Intersteller');
  await expect(page.getByTestId('movie-card')).toHaveCount(1);
});
