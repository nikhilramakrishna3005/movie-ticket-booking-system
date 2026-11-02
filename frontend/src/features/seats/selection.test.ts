import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import type { SeatMapEntry, SeatStatus } from '../../api/types.ts';
import { markUnavailable, reconcileSelection, total } from './selection.ts';

const seat = (seatId: number, status: SeatStatus = 'AVAILABLE', price = 12.5): SeatMapEntry => ({
  seatId,
  rowLabel: 'A',
  seatNumber: seatId,
  category: 'STANDARD',
  price,
  status,
});

describe('seat selection', () => {
  it('drops selected seats that became held or booked', () => {
    const { kept, lost } = reconcileSelection(new Set([1, 2, 3]), [seat(1), seat(2, 'HELD'), seat(3, 'BOOKED')]);
    assert.deepEqual([...kept], [1]);
    assert.deepEqual(lost.map((s) => s.seatId), [2, 3]);
  });

  it('totals prices exactly', () => {
    assert.equal(total([seat(1, 'AVAILABLE', 12.5), seat(2, 'AVAILABLE', 12.5), seat(3, 'AVAILABLE', 16)]), 41);
    assert.equal(total([{ price: 0.1 }, { price: 0.2 }]), 0.3);
  });

  it('marks conflicting seats unavailable', () => {
    const seats = markUnavailable([seat(1), seat(2), seat(3, 'BOOKED')], [2, 3]);
    assert.deepEqual(seats.map((s) => s.status), ['AVAILABLE', 'HELD', 'BOOKED']);
  });
});
