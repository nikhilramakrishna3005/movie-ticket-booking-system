import { expect, test, type Request } from '@playwright/test';
import { USERS, bookingFor, createFreshShowtime, seatName, seatStates } from './support/data.ts';
import { chooseScenario, localTime, longDate, openShowtimeFromDiscovery, seat, selectSeats, submitLogin, usd } from './support/ui.ts';

const BOOKING_REFERENCE = /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$/;

test('a customer books seats and the booked seats become unavailable', async ({ page, request }) => {
  const showtime = await createFreshShowtime(request, 'Inception');
  const seats = ['C5', 'E5'];
  const expectedTotal = usd(showtime.standardPrice + showtime.premiumPrice);

  await openShowtimeFromDiscovery(page, showtime);
  await selectSeats(page, seats);
  await expect(page.getByTestId('selected-seat-summary')).toContainText('C5');
  await expect(page.getByTestId('selected-seat-summary')).toContainText('E5');
  await expect(page.getByTestId('total-price')).toHaveText(expectedTotal);
  await expect(page.getByTestId('continue-button')).toHaveText(`Hold 2 seats · ${expectedTotal}`);

  // Anonymous visitors are asked to sign in when they try to hold seats; the hold follows the sign-in.
  await page.getByTestId('continue-button').click();
  await submitLogin(page, USERS.e2eAlice);
  await expect(page).toHaveURL(/\/checkout\/[0-9a-f-]{36}$/);
  await expect(page.getByTestId('checkout-page')).toBeVisible();
  await expect(page.getByTestId('hold-countdown')).toBeVisible();
  expect((await seatStates(request, showtime.showtimeId)).filter((s) => seats.includes(seatName(s))).map((s) => s.status))
    .toEqual(['HELD', 'HELD']);

  await chooseScenario(page, 'SUCCESS');
  await expect(page.getByTestId('pay-button')).toHaveText(`Pay ${expectedTotal}`);
  await page.getByTestId('pay-button').click();

  await expect(page).toHaveURL(/\/bookings\/[0-9a-f-]{36}$/);
  const confirmation = page.getByTestId('booking-confirmation');
  await expect(confirmation).toBeVisible();
  await expect(page.getByTestId('booking-reference')).toHaveText(BOOKING_REFERENCE);
  await expect(page.getByTestId('ticket-movie')).toHaveText(showtime.movieTitle);
  await expect(page.getByTestId('ticket-theater')).toContainText(showtime.theaterName);
  await expect(page.getByTestId('ticket-theater')).toContainText(showtime.theaterAddress);
  await expect(page.getByTestId('ticket-date')).toHaveText(longDate(showtime.localDate));
  await expect(page.getByTestId('ticket-showtime')).toHaveText(localTime(showtime.localStartTime));
  await expect(page.getByTestId('ticket-seats')).toHaveText('C5, E5');
  await expect(page.getByTestId('ticket-total')).toHaveText(expectedTotal);

  const bookingId = await confirmation.getAttribute('data-booking-id');
  const booking = await bookingFor(request, USERS.e2eAlice, bookingId!);
  expect(booking.status).toBe('CONFIRMED');
  expect(booking.reference).toBe(await page.getByTestId('booking-reference').textContent());

  // A fresh visit (the reload also signs out, since credentials live only in memory) shows the seats as booked.
  await page.goto(`/showtimes/${showtime.showtimeId}/seats`);
  for (const name of seats) {
    await expect(seat(page, name)).toHaveAttribute('data-status', 'BOOKED');
    await expect(seat(page, name)).toHaveAttribute('aria-disabled', 'true');
  }
  await seat(page, 'C5').click({ force: true });
  await expect(seat(page, 'C5')).toHaveAttribute('aria-pressed', 'false');
  await expect(page.getByTestId('total-price')).toHaveText(usd(0));
});

test('a declined payment books nothing and a retry with a new attempt books exactly once', async ({ page, request }) => {
  const showtime = await createFreshShowtime(request, 'Spirited Away');
  const seats = ['D3', 'D4'];
  const expectedTotal = usd(showtime.standardPrice * 2);

  const payments: Array<{ key: string | null; request: Request }> = [];
  page.on('request', (sent) => {
    if (sent.method() === 'POST' && /\/api\/holds\/[^/]+\/payment$/.test(sent.url())) {
      payments.push({ key: sent.headers()['idempotency-key'] ?? null, request: sent });
    }
  });

  await openShowtimeFromDiscovery(page, showtime);
  await page.getByTestId('nav-login-button').click();
  await submitLogin(page, USERS.e2eAlice);
  await selectSeats(page, seats);
  await expect(page.getByTestId('total-price')).toHaveText(expectedTotal);
  await page.getByTestId('continue-button').click();
  await expect(page.getByTestId('checkout-page')).toBeVisible();
  const checkoutUrl = page.url();

  await chooseScenario(page, 'DECLINE');
  await page.getByTestId('pay-button').click();

  await expect(page.getByTestId('payment-status')).toHaveAttribute('data-status', 'declined');
  await expect(page.getByTestId('payment-declined')).toBeVisible();
  await expect(page.getByTestId('booking-confirmation')).toHaveCount(0);
  expect(page.url()).toBe(checkoutUrl);
  await expect(page.getByTestId('pay-button')).toHaveText(`Try again · ${expectedTotal}`);
  // The decline releases nothing and books nothing: the hold still owns the seats.
  const afterDecline = await seatStates(request, showtime.showtimeId);
  expect(afterDecline.filter((s) => s.status === 'BOOKED')).toEqual([]);
  expect(afterDecline.filter((s) => seats.includes(seatName(s))).map((s) => s.status)).toEqual(['HELD', 'HELD']);

  await chooseScenario(page, 'SUCCESS');
  await page.getByTestId('pay-button').click();

  await expect(page).toHaveURL(/\/bookings\/[0-9a-f-]{36}$/);
  await expect(page.getByTestId('booking-confirmation')).toBeVisible();
  await expect(page.getByTestId('booking-reference')).toHaveText(BOOKING_REFERENCE);
  await expect(page.getByTestId('ticket-seats')).toHaveText('D3, D4');
  await expect(page.getByTestId('ticket-total')).toHaveText(expectedTotal);

  expect(payments).toHaveLength(2);
  const [declined, retried] = payments;
  expect(declined!.key).toBeTruthy();
  expect(retried!.key).toBeTruthy();
  expect(retried!.key).not.toBe(declined!.key);
  expect((await declined!.request.response())?.status()).toBe(402);
  expect((await retried!.request.response())?.status()).toBe(201);

  // Exactly one booking: only the two chosen seats are booked on this otherwise untouched showtime.
  const finalSeats = await seatStates(request, showtime.showtimeId);
  expect(finalSeats.filter((s) => s.status === 'BOOKED').map(seatName).sort()).toEqual(seats);
  expect(finalSeats.filter((s) => s.status === 'HELD')).toEqual([]);
  const bookingId = await page.getByTestId('booking-confirmation').getAttribute('data-booking-id');
  const booking = await bookingFor(request, USERS.e2eAlice, bookingId!);
  expect(booking.status).toBe('CONFIRMED');
  expect(booking.seats.map(seatName).sort()).toEqual(seats);
});
