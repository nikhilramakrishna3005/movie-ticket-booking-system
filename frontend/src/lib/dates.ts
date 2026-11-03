// Calendar dates are plain "yyyy-MM-dd" strings in the theater's time zone, exactly as the API
// uses them. Arithmetic on them is done in UTC so the browser's own zone never shifts a day.

const DAY_MS = 86_400_000;

export function todayIn(timeZone: string, now: Date = new Date()): string {
  // en-CA formats as yyyy-MM-dd.
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
}

export function addDays(date: string, days: number): string {
  return new Date(Date.parse(`${date}T00:00:00Z`) + days * DAY_MS).toISOString().slice(0, 10);
}

export function dateRange(start: string, count: number): string[] {
  return Array.from({ length: count }, (_, index) => addDays(start, index));
}

export function isIsoDate(value: string | null | undefined): value is string {
  return !!value && /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(`${value}T00:00:00Z`));
}

export interface DayLabel {
  weekday: string;
  day: string;
  month: string;
}

export function dayLabel(date: string): DayLabel {
  const instant = new Date(`${date}T00:00:00Z`);
  const part = (options: Intl.DateTimeFormatOptions) =>
    new Intl.DateTimeFormat('en-US', { timeZone: 'UTC', ...options }).format(instant);
  return { weekday: part({ weekday: 'short' }), day: part({ day: 'numeric' }), month: part({ month: 'short' }) };
}

/** "Tuesday, November 3" */
export function longDate(date: string): string {
  return new Intl.DateTimeFormat('en-US', { timeZone: 'UTC', weekday: 'long', month: 'long', day: 'numeric' }).format(
    new Date(`${date}T00:00:00Z`),
  );
}

/** "Tue, Nov 3, 2026": with the year, for lists that span more than one year. */
export function ticketDate(date: string): string {
  return new Intl.DateTimeFormat('en-US', { timeZone: 'UTC', weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' })
    .format(new Date(`${date}T00:00:00Z`));
}

/** "13:00:00" → "1:00 PM" */
export function localTime(time: string): string {
  const [hours = 0, minutes = 0] = time.split(':').map(Number);
  const suffix = hours >= 12 ? 'PM' : 'AM';
  return `${hours % 12 || 12}:${String(minutes).padStart(2, '0')} ${suffix}`;
}

/** An instant shown in a theater's zone, e.g. "Tue, Nov 3 · 1:00 PM CST". */
export function instantIn(iso: string, timeZone: string): string {
  const instant = new Date(iso);
  const date = new Intl.DateTimeFormat('en-US', { timeZone, weekday: 'short', month: 'short', day: 'numeric' }).format(
    instant,
  );
  const time = new Intl.DateTimeFormat('en-US', { timeZone, hour: 'numeric', minute: '2-digit', timeZoneName: 'short' })
    .format(instant);
  return `${date} · ${time}`;
}

/**
 * Milliseconds left on a hold. {@code skewMs} is server time minus client time, measured from
 * the Date header of the response that created the hold, so a wrong client clock does not
 * shorten or extend the countdown.
 */
export function remainingMs(expiresAt: string, nowMs: number, skewMs: number): number {
  return Math.max(0, Date.parse(expiresAt) - (nowMs + skewMs));
}

/**
 * 125_900 → "2:05". Rounds down so sub-second skew never shows a fresh 8-minute hold as "8:01",
 * but shows "0:01" until the hold has actually run out.
 */
export function countdown(ms: number): string {
  const totalSeconds = ms <= 0 ? 0 : Math.max(1, Math.floor(ms / 1000));
  return `${Math.floor(totalSeconds / 60)}:${String(totalSeconds % 60).padStart(2, '0')}`;
}
