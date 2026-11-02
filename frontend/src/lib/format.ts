const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

export function price(amount: number): string {
  return usd.format(amount);
}

/** 148 → "2h 28m" */
export function duration(minutes: number): string {
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours === 0) return `${rest}m`;
  return rest === 0 ? `${hours}h` : `${hours}h ${rest}m`;
}

export function seatName(seat: { rowLabel: string; seatNumber: number }): string {
  return `${seat.rowLabel}${seat.seatNumber}`;
}

/** Sorted, comma-separated seat names: "A3, A4, B1". */
export function seatList(seats: ReadonlyArray<{ rowLabel: string; seatNumber: number }>): string {
  return [...seats]
    .sort((a, b) => a.rowLabel.localeCompare(b.rowLabel) || a.seatNumber - b.seatNumber)
    .map(seatName)
    .join(', ');
}

export function titleCase(value: string): string {
  return value.charAt(0).toUpperCase() + value.slice(1).toLowerCase();
}
