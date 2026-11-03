import { ApiError } from '../../api/client.ts';
import type { NewShowtimeRequest, SeatLayoutRow } from '../../api/types.ts';
import { isIsoDate } from '../../lib/dates.ts';
import { describeError } from '../../lib/errors.ts';

export const MAX_PRICE = 1000;
export const MAX_NAME_LENGTH = 40;
export const MAX_ROWS = 26;
export const MAX_SEATS_PER_ROW = 40;
export const MIN_YEAR = 2000;
export const MAX_YEAR = 2100;

export interface ShowtimeDraft {
  movieId: string;
  screenId: string;
  languageCode: string;
  date: string;
  time: string;
  standardPrice: string;
  premiumPrice: string;
}

export const EMPTY_SHOWTIME: ShowtimeDraft = {
  movieId: '',
  screenId: '',
  languageCode: 'en',
  date: '',
  time: '19:00',
  standardPrice: '12.50',
  premiumPrice: '16.00',
};

export interface ScreenDraft {
  name: string;
  rowCount: string;
  seatsPerRow: string;
  premiumRows: string;
}

export const EMPTY_SCREEN: ScreenDraft = {
  name: '',
  rowCount: '6',
  seatsPerRow: '10',
  premiumRows: '2',
};

export const FIELD_LABELS: Record<string, string> = {
  movieId: 'Movie',
  screenId: 'Screen',
  languageCode: 'Language',
  startsAtLocal: 'Date and time',
  standardPrice: 'Standard price',
  premiumPrice: 'Premium price',
  name: 'Screen name',
  rows: 'Rows',
  seatsPerRow: 'Seats per row',
  premiumRows: 'Premium rows',
};

/** Positive, whole cents, and at most $1000, matching NewShowtime on the server. */
export function isValidPrice(text: string): boolean {
  const trimmed = text.trim();
  if (!/^(?:0|[1-9]\d*)(?:\.\d{1,2})?$/.test(trimmed)) return false;
  const amount = Number(trimmed);
  return amount > 0 && amount <= MAX_PRICE;
}

export function isClockTime(time: string): boolean {
  return /^(?:[01]\d|2[0-3]):[0-5]\d$/.test(time);
}

export function invalidShowtimeFields(draft: ShowtimeDraft): string[] {
  const invalid: string[] = [];
  if (!Number.isInteger(Number(draft.movieId)) || Number(draft.movieId) <= 0) invalid.push('movieId');
  if (!Number.isInteger(Number(draft.screenId)) || Number(draft.screenId) <= 0) invalid.push('screenId');
  if (!/^[a-z]{2}$/.test(draft.languageCode)) invalid.push('languageCode');
  if (!isIsoDate(draft.date) || yearOutOfRange(draft.date) || !isClockTime(draft.time)) invalid.push('startsAtLocal');
  if (!isValidPrice(draft.standardPrice)) invalid.push('standardPrice');
  if (!isValidPrice(draft.premiumPrice)) invalid.push('premiumPrice');
  return invalid;
}

export function showtimeBody(draft: ShowtimeDraft): NewShowtimeRequest {
  return {
    movieId: Number(draft.movieId),
    screenId: Number(draft.screenId),
    languageCode: draft.languageCode,
    startsAtLocal: `${draft.date}T${draft.time}:00`,
    standardPrice: Number(draft.standardPrice),
    premiumPrice: Number(draft.premiumPrice),
  };
}

export function invalidScreenFields(draft: ScreenDraft, requireName: boolean): string[] {
  const invalid: string[] = [];
  const name = draft.name.trim();
  if (requireName && (name.length === 0 || name.length > MAX_NAME_LENGTH)) invalid.push('name');
  const rows = integer(draft.rowCount);
  const seats = integer(draft.seatsPerRow);
  const premium = integer(draft.premiumRows);
  if (rows === null || rows < 1 || rows > MAX_ROWS) invalid.push('rows');
  if (seats === null || seats < 1 || seats > MAX_SEATS_PER_ROW) invalid.push('seatsPerRow');
  const maxPremium = rows !== null && rows >= 1 && rows <= MAX_ROWS ? rows : MAX_ROWS;
  if (premium === null || premium < 0 || premium > maxPremium) invalid.push('premiumRows');
  return invalid;
}

export function layoutRows(draft: ScreenDraft): SeatLayoutRow[] {
  const rowCount = Number(draft.rowCount);
  const seatCount = Number(draft.seatsPerRow);
  const premium = Number(draft.premiumRows);
  return Array.from({ length: rowCount }, (_, index) => ({
    rowLabel: String.fromCharCode(65 + index),
    seatCount,
    category: index >= rowCount - premium ? 'PREMIUM' : 'STANDARD',
  }));
}

export function screenBody(draft: ScreenDraft): { name: string; rows: SeatLayoutRow[] } {
  return { name: draft.name.trim(), rows: layoutRows(draft) };
}

export function describeAdminError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'SHOWTIME_OVERLAP':
        return 'That time overlaps another showtime on this screen. Pick a different start time.';
      case 'SCREEN_BUSY':
        return 'This screen is busy with another change. Wait a moment and try again.';
      case 'INVALID_START_TIME':
        return 'That start time is not valid in the theater’s time zone. It may be in the past, or fall into a daylight-saving gap.';
      case 'SCREEN_NAME_TAKEN':
        return 'A screen with that name already exists in this theater.';
      case 'SCREEN_HAS_SHOWTIMES':
        return 'This screen already has showtimes, so its seating plan can no longer change.';
      case 'SHOWTIME_HAS_BOOKINGS':
        return 'This showtime already has confirmed bookings, so it cannot be cancelled. Cinéva does not refund those tickets.';
      case 'SHOWTIME_HAS_UNRESOLVED_PAYMENTS':
        return 'A payment for this showtime is still in progress. Wait for it to settle; Cinéva will not cancel over an unresolved charge.';
      case 'SHOWTIME_HAS_ACTIVE_HOLDS':
        return 'Customers still have seats on hold. Wait until those holds are released or expire, then try again.';
      case 'INVALID_REQUEST': {
        const fields = listedFields(error);
        if (fields.length > 0) {
          return `Please check: ${fields.map((field) => FIELD_LABELS[field] ?? field).join(', ')}.`;
        }
        break;
      }
      default:
        break;
    }
  }
  return describeError(error);
}

export function listedFields(error: unknown): string[] {
  if (error instanceof ApiError && Array.isArray(error.details?.fields)) {
    return error.details.fields.filter((field): field is string => typeof field === 'string');
  }
  return [];
}

function yearOutOfRange(date: string): boolean {
  const year = Number(date.slice(0, 4));
  return year < MIN_YEAR || year > MAX_YEAR;
}

function integer(text: string): number | null {
  const trimmed = text.trim();
  if (!/^-?\d+$/.test(trimmed)) return null;
  return Number(trimmed);
}
