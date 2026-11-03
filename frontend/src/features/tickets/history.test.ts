import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import type { BookingHistoryPage, BookingSummary } from '../../api/types.ts';
import { hasMorePages, isUpcoming, mergePages } from './history.ts';

function booking(bookingId: string, startsAt = '2026-11-03T19:00:00Z'): BookingSummary {
  return {
    bookingId,
    reference: bookingId.toUpperCase().padEnd(8, 'X'),
    status: 'CONFIRMED',
    showtimeId: 1,
    movieTitle: 'Inception',
    theaterName: 'Starlight Cinemas Downtown',
    screenName: 'Screen 1',
    city: 'Dallas, TX',
    startsAt,
    localDate: '2026-11-03',
    localStartTime: '13:00:00',
    timeZone: 'America/Chicago',
    seats: [],
    totalAmount: 12.5,
    confirmedAt: '2026-11-02T12:00:00Z',
  };
}

function page(number: number, totalPages: number, ids: string[]): BookingHistoryPage {
  return { items: ids.map((id) => booking(id)), page: number, size: 2, totalItems: totalPages * 2, totalPages };
}

describe('booking history', () => {
  it('concatenates pages in order', () => {
    const merged = mergePages([page(0, 2, ['d', 'c']), page(1, 2, ['b', 'a'])]);

    assert.deepEqual(merged.map((b) => b.bookingId), ['d', 'c', 'b', 'a']);
  });

  it('drops the repeat caused by a booking made between two page loads', () => {
    // 'e' was booked after page 0 loaded, so 'c' slid onto page 1.
    const merged = mergePages([page(0, 3, ['d', 'c']), page(1, 3, ['c', 'b'])]);

    assert.deepEqual(merged.map((b) => b.bookingId), ['d', 'c', 'b']);
  });

  it('knows whether another page exists', () => {
    assert.equal(hasMorePages([]), false);
    assert.equal(hasMorePages([page(0, 2, ['b', 'a'])]), true);
    assert.equal(hasMorePages([page(0, 2, ['d', 'c']), page(1, 2, ['b', 'a'])]), false);
    assert.equal(hasMorePages([{ items: [], page: 0, size: 10, totalItems: 0, totalPages: 0 }]), false);
  });

  it('treats a ticket as upcoming until its showtime starts', () => {
    const startsAt = Date.parse('2026-11-03T19:00:00Z');

    assert.equal(isUpcoming(booking('a'), startsAt - 1), true);
    assert.equal(isUpcoming(booking('a'), startsAt), false);
    assert.equal(isUpcoming(booking('a'), startsAt + 60_000), false);
  });
});
