import { expect, test } from '@playwright/test';
import { USERS, basicAuth, createFreshShowtime } from './support/data.ts';
import { chooseScenario, localTime, openShowtimeFromDiscovery, selectSeats, submitLogin, usd } from './support/ui.ts';

/** Mirrors the app's ticketDate: "Fri, Feb 5, 2027". */
function ticketDate(date: string): string {
  return new Intl.DateTimeFormat('en-US', { timeZone: 'UTC', weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' })
    .format(new Date(`${date}T00:00:00Z`));
}

test('a successful booking appears in My Tickets, opens its ticket, and the list is gone after reload or sign-out', async ({ page, request }) => {
  const showtime = await createFreshShowtime(request, 'Coco');
  const seats = ['F7', 'F8'];

  await openShowtimeFromDiscovery(page, showtime);
  await selectSeats(page, seats);
  await page.getByTestId('continue-button').click();
  await submitLogin(page, USERS.e2eAlice);
  await expect(page.getByTestId('checkout-page')).toBeVisible();
  await chooseScenario(page, 'SUCCESS');
  await page.getByTestId('pay-button').click();
  const confirmation = page.getByTestId('booking-confirmation');
  await expect(confirmation).toBeVisible();
  const bookingId = (await confirmation.getAttribute('data-booking-id'))!;
  const reference = (await page.getByTestId('booking-reference').textContent())!;

  await page.getByTestId('nav-my-tickets').click();
  await expect(page).toHaveURL(/\/tickets$/);
  const card = page.locator(`[data-testid="ticket-card"][data-booking-id="${bookingId}"]`);
  await expect(card).toBeVisible();
  await expect(card.getByTestId('ticket-card-reference')).toHaveText(reference);
  await expect(card.getByTestId('ticket-card-movie')).toHaveText(showtime.movieTitle);
  await expect(card.getByTestId('ticket-card-when')).toHaveText(`${ticketDate(showtime.localDate)} · ${localTime(showtime.localStartTime)}`);
  await expect(card.getByTestId('ticket-card-theater')).toContainText(showtime.theaterName);
  await expect(card.getByTestId('ticket-card-seats')).toHaveText('F7, F8');
  await expect(card.getByTestId('ticket-card-total')).toHaveText(usd(showtime.premiumPrice * 2));
  await expect(card.getByTestId('ticket-card-timing')).toHaveText('Upcoming');

  // The cards are the server's own list, newest first. Other specs may book as e2e.alice in parallel;
  // those bookings can only be newer, so the order of the shown cards must match the API's order.
  const shown = await page.getByTestId('ticket-card').evaluateAll((cards) => cards.map((c) => c.getAttribute('data-booking-id')));
  const api = await request.get('/api/bookings?size=50', { headers: basicAuth(USERS.e2eAlice) });
  expect(api.status()).toBe(200);
  const apiIds = ((await api.json()) as { items: Array<{ bookingId: string }> }).items.map((item) => item.bookingId);
  expect(shown).toContain(bookingId);
  expect(apiIds.filter((id) => shown.includes(id))).toEqual(shown);

  await card.getByTestId('ticket-card-link').click();
  await expect(page).toHaveURL(new RegExp(`/bookings/${bookingId}$`));
  await expect(page.getByTestId('booking-reference')).toHaveText(reference);
  await expect(page.getByTestId('ticket-seats')).toHaveText('F7, F8');
  await page.goBack();
  await expect(card).toBeVisible();

  // Nothing about the session or the bookings is persisted in the browser.
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length, document.cookie])).toEqual([0, 0, '']);

  // A reload drops the in-memory credentials, so the page asks to sign in and shows no tickets.
  await page.reload();
  await expect(page.getByTestId('tickets-signed-out')).toBeVisible();
  await expect(page.getByTestId('ticket-card')).toHaveCount(0);

  // e2e.bob signs in on the same page and never sees e2e.alice's booking.
  await page.getByTestId('tickets-sign-in').click();
  await submitLogin(page, USERS.e2eBob);
  await expect(page.getByTestId('ticket-list').or(page.getByTestId('tickets-empty'))).toBeVisible();
  await expect(card).toHaveCount(0);
  await expect(page.getByText(reference)).toHaveCount(0);

  await page.getByTestId('nav-logout-button').click();
  await expect(page).toHaveURL((url) => url.pathname === '/');
  await page.getByTestId('nav-my-tickets').click();
  await expect(page.getByTestId('tickets-signed-out')).toBeVisible();
  await expect(page.getByTestId('ticket-card')).toHaveCount(0);
});
