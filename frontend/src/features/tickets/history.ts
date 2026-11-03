import type { BookingHistoryPage, BookingSummary } from '../../api/types.ts';

export const HISTORY_PAGE_SIZE = 10;

/**
 * The loaded pages in order, each booking once. Pages are fetched by offset, so a booking made
 * while the customer is paging pushes the next page down by one and repeats its first entry.
 */
export function mergePages(pages: readonly BookingHistoryPage[]): BookingSummary[] {
  const seen = new Set<string>();
  const merged: BookingSummary[] = [];
  for (const page of pages) {
    for (const booking of page.items) {
      if (seen.has(booking.bookingId)) continue;
      seen.add(booking.bookingId);
      merged.push(booking);
    }
  }
  return merged;
}

export function hasMorePages(pages: readonly BookingHistoryPage[]): boolean {
  const last = pages.at(-1);
  return !!last && last.page + 1 < last.totalPages;
}

/** A ticket is upcoming until its showtime starts. */
export function isUpcoming(booking: Pick<BookingSummary, 'startsAt'>, nowMs: number): boolean {
  return Date.parse(booking.startsAt) > nowMs;
}
