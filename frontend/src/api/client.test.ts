import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, createApiClient, encodeBasic } from './client.ts';

interface Recorded {
  url: string;
  init: RequestInit;
}

function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

function fakeFetch(responses: Array<Response | Error>) {
  const calls: Recorded[] = [];
  const impl = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    calls.push({ url: String(input), init: init ?? {} });
    const next = responses.shift();
    if (!next) throw new Error('unexpected request');
    if (next instanceof Error) throw next;
    return next;
  };
  return { calls, impl: impl as typeof fetch };
}

const header = (call: Recorded, name: string) => (call.init.headers as Record<string, string>)[name];
const noSleep = async () => {};

describe('api client', () => {
  it('sends in-memory credentials as Basic auth and marks requests as XHR', async () => {
    const { calls, impl } = fakeFetch([json(200, [])]);
    const api = createApiClient({ fetch: impl, getCredentials: () => ({ username: 'alice', password: 'pw' }) });

    await api.cities();

    assert.equal(header(calls[0]!, 'Authorization'), `Basic ${btoa('alice:pw')}`);
    assert.equal(header(calls[0]!, 'X-Requested-With'), 'XMLHttpRequest');
    assert.equal(calls[0]!.init.credentials, 'omit');
  });

  it('encodes non-ASCII credentials as UTF-8', () => {
    assert.equal(encodeBasic({ username: 'josé', password: 'ñ' }), 'Basic am9zw6k6w7E=');
  });

  it('omits Authorization when anonymous and builds the search query without empty params', async () => {
    const { calls, impl } = fakeFetch([json(200, { items: [], page: 0, size: 50, totalItems: 0, totalPages: 0 })]);
    const api = createApiClient({ fetch: impl, getCredentials: () => null });

    await api.searchShowtimes({ cityId: 1, date: '2026-11-03', q: '', genre: undefined, size: 50 });

    assert.equal(calls[0]!.url, '/api/showtimes?cityId=1&date=2026-11-03&size=50');
    assert.equal(header(calls[0]!, 'Authorization'), undefined);
  });

  it('turns backend error bodies into typed ApiErrors', async () => {
    const { impl } = fakeFetch([
      json(409, { code: 'SEATS_UNAVAILABLE', message: 'Seats taken', details: { seatIds: [4, 5] } }),
    ]);
    const api = createApiClient({ fetch: impl, getCredentials: () => null });

    await assert.rejects(api.createHold(7, [4, 5]), (error: unknown) => {
      assert.ok(error instanceof ApiError);
      assert.equal(error.status, 409);
      assert.equal(error.code, 'SEATS_UNAVAILABLE');
      assert.deepEqual(error.details, { seatIds: [4, 5] });
      return true;
    });
  });

  it('reports a non-JSON error with a generic code', async () => {
    const { impl } = fakeFetch([new Response('<html>oops</html>', { status: 500 })]);
    const api = createApiClient({ fetch: impl, getCredentials: () => null });

    await assert.rejects(api.movies(), { code: 'HTTP_500', status: 500 });
  });

  it('retries a payment after a network failure by resending the same Idempotency-Key', async () => {
    const { calls, impl } = fakeFetch([
      new TypeError('Failed to fetch'),
      json(503, { code: 'HTTP_503', message: 'busy', details: null }),
      json(201, { paymentId: 'p1', holdId: 'h1', status: 'SUCCEEDED', amount: 25, booking: null }),
    ]);
    const api = createApiClient({ fetch: impl, getCredentials: () => null, sleep: noSleep });

    const response = await api.pay('h1', 'SUCCESS', 'key-1');

    assert.equal(response.status, 201);
    assert.equal(calls.length, 3);
    assert.deepEqual(calls.map((call) => header(call, 'Idempotency-Key')), ['key-1', 'key-1', 'key-1']);
    assert.deepEqual(calls.map((call) => call.init.body), Array(3).fill('{"scenario":"SUCCESS"}'));
  });

  it('gives up with NETWORK_ERROR once retries are exhausted', async () => {
    const { calls, impl } = fakeFetch([new TypeError('x'), new TypeError('x'), new TypeError('x')]);
    const api = createApiClient({ fetch: impl, getCredentials: () => null, sleep: noSleep });

    await assert.rejects(api.pay('h1', 'SUCCESS', 'key-1'), (error: unknown) => {
      assert.ok(error instanceof ApiError && error.isNetworkError);
      assert.equal(error.code, 'NETWORK_ERROR');
      return true;
    });
    assert.equal(calls.length, 3);
  });

  it('does not retry hold creation, which has no idempotency key', async () => {
    const { calls, impl } = fakeFetch([new TypeError('x')]);
    const api = createApiClient({ fetch: impl, getCredentials: () => null, sleep: noSleep });

    await assert.rejects(api.createHold(1, [1]), { code: 'NETWORK_ERROR' });
    assert.equal(calls.length, 1);
  });

  it('never retries a definitive answer such as a decline', async () => {
    const { calls, impl } = fakeFetch([
      json(402, { code: 'PAYMENT_DECLINED', message: 'declined', details: { paymentId: 'p1', status: 'DECLINED' } }),
    ]);
    const api = createApiClient({ fetch: impl, getCredentials: () => null, sleep: noSleep });

    await assert.rejects(api.pay('h1', 'DECLINE', 'key-1'), { code: 'PAYMENT_DECLINED', status: 402 });
    assert.equal(calls.length, 1);
  });

  it('exposes the server Date header and handles 204 responses', async () => {
    const { impl } = fakeFetch([
      json(201, { holdId: 'h1' }, { Date: 'Tue, 03 Nov 2026 18:00:00 GMT' }),
      new Response(null, { status: 204 }),
    ]);
    const api = createApiClient({ fetch: impl, getCredentials: () => null });

    const hold = await api.createHold(1, [1]);
    assert.equal(hold.serverDate?.toISOString(), '2026-11-03T18:00:00.000Z');
    await api.releaseHold('h1');
  });

  it('requests the booking history with in-memory credentials and no customer parameter', async () => {
    const emptyPage = { items: [], page: 0, size: 10, totalItems: 0, totalPages: 0 };
    const { calls, impl } = fakeFetch([json(200, emptyPage), json(200, { ...emptyPage, page: 2 })]);
    const api = createApiClient({ fetch: impl, getCredentials: () => ({ username: 'alice', password: 'pw' }) });

    assert.deepEqual(await api.bookings(), emptyPage);
    await api.bookings({ page: 2, size: 10 });

    assert.equal(calls[0]!.url, '/api/bookings');
    assert.equal(calls[1]!.url, '/api/bookings?page=2&size=10');
    assert.equal(header(calls[1]!, 'Authorization'), `Basic ${btoa('alice:pw')}`);
    assert.equal(calls[1]!.init.method, 'GET');
  });

  it('reports a rejected booking-history request as a typed 401', async () => {
    const { impl } = fakeFetch([json(401, { code: 'UNAUTHENTICATED', message: 'Valid credentials are required', details: null })]);
    const api = createApiClient({ fetch: impl, getCredentials: () => null });

    await assert.rejects(api.bookings(), { code: 'UNAUTHENTICATED', status: 401 });
  });

  it('validates login credentials explicitly rather than from the store', async () => {
    const { calls, impl } = fakeFetch([json(200, { id: 1, username: 'bob', displayName: 'Bob', role: 'CUSTOMER' })]);
    const api = createApiClient({ fetch: impl, getCredentials: () => null });

    const user = await api.login({ username: 'bob', password: 'secret' });

    assert.equal(user.username, 'bob');
    assert.equal(header(calls[0]!, 'Authorization'), `Basic ${btoa('bob:secret')}`);
  });

  it('loads a hold by id without treating the GET as a write', async () => {
    const hold = { holdId: 'h1', showtimeId: 8, seatIds: [1], status: 'ACTIVE', expiresAt: '2026-11-03T12:08:00Z' };
    const { calls, impl } = fakeFetch([json(200, hold)]);
    const api = createApiClient({ fetch: impl, getCredentials: () => ({ username: 'alice', password: 'pw' }) });

    const response = await api.getHold('h1');

    assert.equal(response.data.status, 'ACTIVE');
    assert.equal(calls[0]!.url, '/api/holds/h1');
    assert.equal(calls[0]!.init.method, 'GET');
  });

  it('loads notifications with page and size and no customer parameter', async () => {
    const { calls, impl } = fakeFetch([
      json(200, { items: [], page: 0, size: 10, totalItems: 0, totalPages: 0 }),
    ]);
    const api = createApiClient({ fetch: impl, getCredentials: () => ({ username: 'alice', password: 'pw' }) });

    await api.notifications({ page: 0, size: 10 });

    assert.equal(calls[0]!.url, '/api/notifications?page=0&size=10');
    assert.equal(calls[0]!.init.method, 'GET');
  });

  it('retries an idempotent showtime cancellation after a gateway error', async () => {
    const { calls, impl } = fakeFetch([
      json(503, { code: 'HTTP_503', message: 'busy', details: null }),
      json(200, { showtimeId: 9, theaterId: 2, screenId: 1, status: 'CANCELLED' }),
    ]);
    const api = createApiClient({ fetch: impl, getCredentials: () => ({ username: 'admin.dallas', password: 'pw' }), sleep: noSleep });

    const response = await api.cancelShowtime(9);

    assert.equal(response.data.status, 'CANCELLED');
    assert.equal(calls.length, 2);
    assert.equal(calls[0]!.url, '/api/admin/showtimes/9/cancel');
    assert.equal(calls[0]!.init.method, 'POST');
  });
});
