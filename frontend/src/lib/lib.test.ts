import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { addDays, countdown, dateRange, dayLabel, instantIn, isIsoDate, localTime, remainingMs, todayIn } from './dates.ts';
import { duration, price, seatList } from './format.ts';
import { newIdempotencyKey } from './idempotency.ts';
import { monogram, posterFor } from './posters.ts';

describe('dates', () => {
  it('computes today in the theater zone, not the browser zone', () => {
    // 03:30 UTC on Nov 3 is still Nov 2 in Chicago.
    assert.equal(todayIn('America/Chicago', new Date('2026-11-03T03:30:00Z')), '2026-11-02');
    assert.equal(todayIn('UTC', new Date('2026-11-03T03:30:00Z')), '2026-11-03');
  });

  it('adds days across month and DST boundaries', () => {
    assert.equal(addDays('2026-10-31', 1), '2026-11-01');
    assert.equal(addDays('2026-11-01', 1), '2026-11-02');
    assert.deepEqual(dateRange('2026-12-30', 3), ['2026-12-30', '2026-12-31', '2027-01-01']);
  });

  it('validates ISO dates', () => {
    assert.equal(isIsoDate('2026-11-03'), true);
    assert.equal(isIsoDate('2026-13-03'), false);
    assert.equal(isIsoDate('11/03/2026'), false);
    assert.equal(isIsoDate(null), false);
  });

  it('labels days and local times', () => {
    assert.deepEqual(dayLabel('2026-11-03'), { weekday: 'Tue', day: '3', month: 'Nov' });
    assert.equal(localTime('13:00:00'), '1:00 PM');
    assert.equal(localTime('00:05:00'), '12:05 AM');
    assert.equal(instantIn('2026-11-03T19:00:00Z', 'America/Chicago'), 'Tue, Nov 3 · 1:00 PM CST');
  });

  it('corrects the hold countdown for client clock skew', () => {
    const expiresAt = '2026-11-03T18:08:00Z';
    const serverNow = Date.parse('2026-11-03T18:00:00Z');
    // The client clock runs 30 s fast: skew (server - client) is -30 s.
    assert.equal(remainingMs(expiresAt, serverNow + 30_000, -30_000), 8 * 60_000);
    assert.equal(remainingMs(expiresAt, serverNow + 9 * 60_000, 0), 0);
    assert.equal(countdown(125_900), '2:05');
    assert.equal(countdown(480_300), '8:00');
    assert.equal(countdown(400), '0:01');
    assert.equal(countdown(0), '0:00');
  });
});

describe('format', () => {
  it('formats prices, durations and seat lists', () => {
    assert.equal(price(28.5), '$28.50');
    assert.equal(duration(148), '2h 28m');
    assert.equal(duration(120), '2h');
    assert.equal(duration(45), '45m');
    assert.equal(
      seatList([
        { rowLabel: 'B', seatNumber: 1 },
        { rowLabel: 'A', seatNumber: 10 },
        { rowLabel: 'A', seatNumber: 9 },
      ]),
      'A9, A10, B1',
    );
  });
});

describe('idempotency keys', () => {
  it('uses randomUUID when available and never repeats', () => {
    const keys = new Set(Array.from({ length: 50 }, () => newIdempotencyKey()));
    assert.equal(keys.size, 50);
  });

  it('falls back to a v4 UUID from getRandomValues', () => {
    const fallback = { getRandomValues: globalThis.crypto.getRandomValues.bind(globalThis.crypto) } as Crypto;
    assert.match(newIdempotencyKey(fallback), /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });
});

describe('posters', () => {
  it('derives a stable poster from the title', () => {
    assert.deepEqual(posterFor('Inception'), posterFor('Inception'));
    assert.equal(monogram('The Dark Knight'), 'DK');
    assert.equal(monogram('Spirited Away'), 'SA');
    assert.equal(monogram('3 Idiots'), '3I');
  });
});
