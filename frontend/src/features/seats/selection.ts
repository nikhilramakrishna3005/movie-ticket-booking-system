import type { SeatMapEntry } from '../../api/types.ts';

/** Mirrors HoldService.MAX_SEATS_PER_HOLD; the server enforces it regardless. */
export const MAX_SEATS = 10;

/** Drops selected seats that a refreshed seat map no longer reports as AVAILABLE. */
export function reconcileSelection(
  selected: ReadonlySet<number>,
  seats: SeatMapEntry[],
): { kept: Set<number>; lost: SeatMapEntry[] } {
  const kept = new Set<number>();
  const lost: SeatMapEntry[] = [];
  const byId = new Map(seats.map((seat) => [seat.seatId, seat]));
  for (const seatId of selected) {
    const seat = byId.get(seatId);
    if (seat?.status === 'AVAILABLE') kept.add(seatId);
    else if (seat) lost.push(seat);
  }
  return { kept, lost };
}

export function selectedSeats(selected: ReadonlySet<number>, seats: SeatMapEntry[]): SeatMapEntry[] {
  return seats.filter((seat) => selected.has(seat.seatId));
}

/** Sums in cents so 3 × 12.50 + 16.00 is exact; the server computes the authoritative amount. */
export function total(seats: ReadonlyArray<{ price: number }>): number {
  return seats.reduce((sum, seat) => sum + Math.round(seat.price * 100), 0) / 100;
}

/** Marks seats the server reported as unavailable (409 SEATS_UNAVAILABLE) until the next refresh. */
export function markUnavailable(seats: SeatMapEntry[], seatIds: number[]): SeatMapEntry[] {
  const taken = new Set(seatIds);
  return seats.map((seat) => (taken.has(seat.seatId) && seat.status === 'AVAILABLE' ? { ...seat, status: 'HELD' } : seat));
}
