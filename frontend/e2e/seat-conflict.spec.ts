import { expect, test } from '@playwright/test';
import { USERS, createFreshShowtime, holdSeatsViaApi, seatName, seatStates } from './support/data.ts';
import { chooseScenario, openShowtimeFromDiscovery, seat, selectSeats, submitLogin, usd } from './support/ui.ts';

test('a seat taken by another customer is removed from the selection and the rest can still be booked', async ({ page, request }) => {
  const showtime = await createFreshShowtime(request, 'Coco');

  await openShowtimeFromDiscovery(page, showtime);
  await page.getByTestId('nav-login-button').click();
  await submitLogin(page, USERS.e2eAlice);
  await selectSeats(page, ['B2', 'B3']);

  // Bob holds B2 after Alice's map was loaded, so her view of B2 is stale when she continues.
  const b2 = (await seatStates(request, showtime.showtimeId)).find((s) => seatName(s) === 'B2')!;
  await holdSeatsViaApi(request, USERS.e2eBob, showtime.showtimeId, [b2.seatId]);

  await page.getByTestId('continue-button').click();
  await expect(page.getByTestId('seat-error')).toContainText('Someone just took some of those seats');
  await expect(seat(page, 'B2')).toHaveAttribute('aria-pressed', 'false');
  await expect(seat(page, 'B2')).toHaveAttribute('data-status', 'HELD');
  await expect(seat(page, 'B3')).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByTestId('total-price')).toHaveText(usd(showtime.standardPrice));

  await page.getByTestId('continue-button').click();
  await expect(page.getByTestId('checkout-page')).toBeVisible();
  await chooseScenario(page, 'SUCCESS');
  await page.getByTestId('pay-button').click();
  await expect(page.getByTestId('booking-confirmation')).toBeVisible();
  await expect(page.getByTestId('ticket-seats')).toHaveText('B3');

  const states = await seatStates(request, showtime.showtimeId);
  expect(states.filter((s) => s.status === 'BOOKED').map(seatName)).toEqual(['B3']);
  expect(states.filter((s) => s.status === 'HELD').map(seatName)).toEqual(['B2']);
});
