import { useMemo, useState, type KeyboardEvent } from 'react';
import type { SeatMapEntry } from '../api/types.ts';
import { price, seatName, titleCase } from '../lib/format.ts';

interface SeatMapProps {
  seats: SeatMapEntry[];
  selected: ReadonlySet<number>;
  onToggle: (seat: SeatMapEntry) => void;
  disabled?: boolean;
}

interface Row {
  label: string;
  seats: SeatMapEntry[];
}

export function seatRows(seats: SeatMapEntry[]): Row[] {
  const rows = new Map<string, SeatMapEntry[]>();
  for (const seat of seats) {
    rows.set(seat.rowLabel, [...(rows.get(seat.rowLabel) ?? []), seat]);
  }
  return [...rows.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([label, rowSeats]) => ({ label, seats: rowSeats.sort((a, b) => a.seatNumber - b.seatNumber) }));
}

function seatClass(seat: SeatMapEntry, isSelected: boolean): string {
  if (isSelected) return 'bg-violet-500 border-violet-300 text-white shadow-glow';
  if (seat.status !== 'AVAILABLE') return 'cursor-not-allowed border-white/5 bg-ink-700/70 text-mist-500/70';
  return seat.category === 'PREMIUM'
    ? 'border-gold-300/45 bg-gold-300/8 text-gold-300/80 hover:bg-gold-300/20 hover:text-gold-300'
    : 'border-violet-400/35 bg-violet-400/6 text-violet-300/70 hover:bg-violet-400/20 hover:text-violet-300';
}

/**
 * Seats are buttons with a roving tabindex: Tab enters the map once, arrow keys move between
 * seats (unavailable seats stay focusable so screen-reader users hear why they are skipped).
 */
export function SeatMap({ seats, selected, onToggle, disabled }: SeatMapProps) {
  const rows = useMemo(() => seatRows(seats), [seats]);
  const [focusId, setFocusId] = useState<number | null>(null);
  const firstAvailable = seats.find((seat) => seat.status === 'AVAILABLE')?.seatId ?? seats[0]?.seatId;
  const tabbableId = focusId !== null && seats.some((seat) => seat.seatId === focusId) ? focusId : firstAvailable;

  const move = (event: KeyboardEvent, rowIndex: number, colIndex: number) => {
    const deltas: Record<string, [number, number]> = {
      ArrowUp: [-1, 0],
      ArrowDown: [1, 0],
      ArrowLeft: [0, -1],
      ArrowRight: [0, 1],
    };
    const delta = deltas[event.key];
    if (!delta) return;
    event.preventDefault();
    const row = rows[Math.min(Math.max(rowIndex + delta[0], 0), rows.length - 1)];
    if (!row) return;
    const target = row.seats[Math.min(Math.max(colIndex + delta[1], 0), row.seats.length - 1)];
    if (!target) return;
    setFocusId(target.seatId);
    document.querySelector<HTMLButtonElement>(`[data-seat-id="${target.seatId}"]`)?.focus();
  };

  return (
    <div data-testid="seat-map" className="overflow-x-auto pb-2">
      <div className="mx-auto w-max min-w-full">
        <div className="mx-auto mb-10 max-w-md" aria-hidden>
          <div className="h-2 rounded-[50%] bg-gradient-to-r from-transparent via-violet-300/80 to-transparent shadow-[0_10px_40px_6px_rgb(139_92_246/0.35)]" />
          <p className="mt-3 text-center text-[0.65rem] font-semibold tracking-[0.4em] text-mist-500">SCREEN</p>
        </div>
        <div role="group" aria-label="Seats" className="space-y-2">
          {rows.map((row, rowIndex) => {
            const aisleAfter = Math.ceil(row.seats.length / 2);
            return (
              <div key={row.label} className="flex items-center justify-center gap-1 sm:gap-2">
                <span className="w-5 text-center text-xs font-semibold text-mist-500" aria-hidden>{row.label}</span>
                {row.seats.map((seat, colIndex) => {
                  const isSelected = selected.has(seat.seatId);
                  const unavailable = seat.status !== 'AVAILABLE';
                  const state = isSelected ? 'selected' : unavailable ? (seat.status === 'BOOKED' ? 'booked' : 'held') : 'available';
                  return (
                    <span key={seat.seatId} className={`flex ${colIndex === aisleAfter ? 'ml-3 sm:ml-6' : ''}`}>
                      <button
                        type="button"
                        data-testid="seat"
                        data-seat-id={seat.seatId}
                        data-seat-name={seatName(seat)}
                        data-status={seat.status}
                        data-selected={isSelected}
                        tabIndex={seat.seatId === tabbableId ? 0 : -1}
                        aria-pressed={isSelected}
                        aria-disabled={unavailable || disabled || undefined}
                        aria-label={`Row ${seat.rowLabel}, seat ${seat.seatNumber}, ${titleCase(seat.category)}, ${price(seat.price)}, ${state}`}
                        onFocus={() => setFocusId(seat.seatId)}
                        onKeyDown={(event) => move(event, rowIndex, colIndex)}
                        onClick={() => !unavailable && !disabled && onToggle(seat)}
                        className={`grid size-6 place-items-center rounded-t-lg rounded-b-sm border text-[0.6rem] font-semibold transition-[background-color,color,box-shadow,transform] duration-150 sm:size-8 ${seatClass(seat, isSelected)} ${
                          isSelected ? 'scale-105' : ''
                        }`}
                      >
                        {unavailable && !isSelected ? '×' : seat.seatNumber}
                      </button>
                    </span>
                  );
                })}
                <span className="w-5 text-center text-xs font-semibold text-mist-500" aria-hidden>{row.label}</span>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

export function SeatLegend({ prices }: { prices: Array<{ category: string; price: number }> }) {
  const swatch = 'inline-block size-4 rounded-t-md rounded-b-sm border';
  return (
    <ul className="flex flex-wrap items-center justify-center gap-x-5 gap-y-2 text-xs text-mist-400">
      {prices.map((tier) => (
        <li key={tier.category} className="flex items-center gap-2">
          <span
            className={`${swatch} ${tier.category === 'PREMIUM' ? 'border-gold-300/45 bg-gold-300/8' : 'border-violet-400/35 bg-violet-400/6'}`}
            aria-hidden
          />
          {titleCase(tier.category)} · {price(tier.price)}
        </li>
      ))}
      <li className="flex items-center gap-2">
        <span className={`${swatch} border-violet-300 bg-violet-500`} aria-hidden /> Selected
      </li>
      <li className="flex items-center gap-2">
        <span className={`${swatch} grid place-items-center border-white/5 bg-ink-700/70 text-[0.6rem] leading-none text-mist-500/70`} aria-hidden>×</span> Unavailable
      </li>
    </ul>
  );
}
