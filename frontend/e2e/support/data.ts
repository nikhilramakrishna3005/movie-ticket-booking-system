import { expect, type APIRequestContext } from '@playwright/test';

/**
 * Public, local-only demo accounts seeded by Flyway (listed in the README). Never real credentials.
 * Playwright booking tests use e2e.alice / e2e.bob so Alice and Bob's My Tickets stay for manual use.
 */
export const USERS = {
  alice: { username: 'alice', password: 'alice-demo-pass' },
  bob: { username: 'bob', password: 'bob-demo-pass' },
  e2eAlice: { username: 'e2e.alice', password: 'e2e-alice-demo-pass' },
  e2eBob: { username: 'e2e.bob', password: 'e2e-bob-demo-pass' },
  dallasAdmin: { username: 'admin.dallas', password: 'dallas-admin-demo' },
} as const;

export type DemoUser = (typeof USERS)[keyof typeof USERS];

const THEATER = 'Starlight Cinemas Downtown';
const SCREEN = 'Screen 1';
const DALLAS_CITY_ID = 1;
const SCREEN_LOOKUP_DAYS = 14;
/** Seeded showtimes end on 2027-01-31; test showtimes go after that so they never touch seeded inventory. */
const FIRST_TEST_DAY = Date.UTC(2027, 2, 1);
const TEST_DAYS = 3650;
const MAX_ATTEMPTS = 20;

export interface FreshShowtime {
  showtimeId: number;
  movieId: number;
  movieTitle: string;
  cityId: number;
  theaterId?: number;
  theaterName: string;
  theaterAddress: string;
  localDate: string;
  localStartTime: string;
  standardPrice: number;
  premiumPrice: number;
}

export interface SeatState {
  seatId: number;
  rowLabel: string;
  seatNumber: number;
  status: 'AVAILABLE' | 'HELD' | 'BOOKED';
}

export function basicAuth(user: DemoUser): Record<string, string> {
  return {
    Authorization: `Basic ${Buffer.from(`${user.username}:${user.password}`).toString('base64')}`,
    'X-Requested-With': 'XMLHttpRequest',
  };
}

async function json<T>(request: APIRequestContext, url: string, user?: DemoUser): Promise<T> {
  const response = await request.get(url, { headers: user ? basicAuth(user) : {} });
  expect(response.status(), `GET ${url}`).toBe(200);
  return (await response.json()) as T;
}

/** A wall-clock start in the theater's zone, after the seeded schedule so tests never share inventory. */
export function farFutureLocalStart(): { date: string; time: string; startsAtLocal: string } {
  const startsAtLocal = randomSlot();
  return { date: startsAtLocal.slice(0, 10), time: startsAtLocal.slice(11, 16), startsAtLocal };
}

function randomSlot(): string {
  const day = new Date(FIRST_TEST_DAY + Math.floor(Math.random() * TEST_DAYS) * 86_400_000).toISOString().slice(0, 10);
  const hour = 9 + Math.floor(Math.random() * 12);
  const minute = Math.random() < 0.5 ? '00' : '30';
  return `${day}T${String(hour).padStart(2, '0')}:${minute}:00`;
}

/** Screen ids are not exposed directly, so the large Starlight screen is found through the upcoming seeded showtimes. */
async function findScreenId(request: APIRequestContext): Promise<number> {
  for (let day = 0; day < SCREEN_LOOKUP_DAYS; day++) {
    const date = new Date(Date.now() + day * 86_400_000).toISOString().slice(0, 10);
    const search = await json<{ items: Array<{ theaterName: string; screenName: string; screenId: number; cityId: number }> }>(
      request,
      `/api/showtimes?cityId=${DALLAS_CITY_ID}&date=${date}&size=50`,
    );
    // Unknown query parameters are ignored by the API, so prove the city filter was applied.
    expect(search.items.map((item) => item.cityId).filter((cityId) => cityId !== DALLAS_CITY_ID), `cityId filter on ${date}`)
      .toEqual([]);
    const match = search.items.find((item) => item.theaterName === THEATER && item.screenName === SCREEN);
    if (match) return match.screenId;
  }
  throw new Error(`No showtime for ${THEATER} ${SCREEN} in the next ${SCREEN_LOOKUP_DAYS} days`);
}

/**
 * Schedules a brand-new showtime of a seeded movie through the theater-admin API, so every test
 * gets its own untouched seat inventory: no database reset, no shared seats, no test ordering.
 * A random far-future slot that collides with an earlier run's showtime is retried.
 */
export async function createFreshShowtime(request: APIRequestContext, movieTitle: string): Promise<FreshShowtime> {
  const movies = await json<Array<{ id: number; title: string }>>(request, '/api/movies');
  const movie = movies.find((candidate) => candidate.title === movieTitle);
  if (!movie) throw new Error(`Seeded movie "${movieTitle}" not found`);

  const screenId = await findScreenId(request);

  for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
    const response = await request.post('/api/admin/showtimes', {
      headers: basicAuth(USERS.dallasAdmin),
      data: {
        movieId: movie.id,
        screenId,
        languageCode: 'en',
        startsAtLocal: randomSlot(),
        standardPrice: 12.5,
        premiumPrice: 16,
      },
    });
    if (response.status() === 409) continue;
    expect(response.status(), await response.text()).toBe(201);
    const created = (await response.json()) as { showtimeId: number };
    const detail = await json<{
      localDate: string;
      localStartTime: string;
      theater: { id: number; name: string; address: string; city: { id: number } };
      prices: Array<{ category: string; price: number }>;
    }>(request, `/api/showtimes/${created.showtimeId}`);
    const priceOf = (category: string) => detail.prices.find((entry) => entry.category === category)?.price ?? NaN;
    return {
      showtimeId: created.showtimeId,
      movieId: movie.id,
      movieTitle: movie.title,
      cityId: detail.theater.city.id,
      theaterId: detail.theater.id,
      theaterName: detail.theater.name,
      theaterAddress: detail.theater.address,
      localDate: detail.localDate,
      localStartTime: detail.localStartTime,
      standardPrice: priceOf('STANDARD'),
      premiumPrice: priceOf('PREMIUM'),
    };
  }
  throw new Error(`No free slot found on ${THEATER} ${SCREEN} after ${MAX_ATTEMPTS} attempts`);
}

export async function seatStates(request: APIRequestContext, showtimeId: number): Promise<SeatState[]> {
  const map = await json<{ seats: SeatState[] }>(request, `/api/showtimes/${showtimeId}/seats`);
  return map.seats;
}

export function seatName(seat: Pick<SeatState, 'rowLabel' | 'seatNumber'>): string {
  return `${seat.rowLabel}${seat.seatNumber}`;
}

export async function holdSeatsViaApi(request: APIRequestContext, user: DemoUser, showtimeId: number, seatIds: number[]) {
  const response = await request.post(`/api/showtimes/${showtimeId}/holds`, {
    headers: basicAuth(user),
    data: { seatIds },
  });
  expect(response.status(), await response.text()).toBe(201);
  return (await response.json()) as { holdId: string };
}

export async function bookingFor(request: APIRequestContext, user: DemoUser, bookingId: string) {
  return json<{ bookingId: string; reference: string; status: string; seats: Array<{ rowLabel: string; seatNumber: number }>; totalAmount: number }>(
    request,
    `/api/bookings/${bookingId}`,
    user,
  );
}
