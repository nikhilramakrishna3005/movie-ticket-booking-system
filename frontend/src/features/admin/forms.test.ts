import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError } from '../../api/client.ts';
import {
  EMPTY_SCREEN,
  EMPTY_SHOWTIME,
  describeAdminError,
  invalidScreenFields,
  invalidShowtimeFields,
  isValidPrice,
  layoutRows,
  listedFields,
  screenBody,
  showtimeBody,
} from './forms.ts';

function showtime(overrides: Partial<typeof EMPTY_SHOWTIME> = {}) {
  return {
    ...EMPTY_SHOWTIME,
    movieId: '7',
    screenId: '2',
    languageCode: 'en',
    date: '2027-03-14',
    time: '20:00',
    standardPrice: '12.50',
    premiumPrice: '16',
    ...overrides,
  };
}

describe('showtime draft', () => {
  it('accepts a complete draft and builds the API body with seconds zeroed', () => {
    const draft = showtime();

    assert.deepEqual(invalidShowtimeFields(draft), []);
    assert.deepEqual(showtimeBody(draft), {
      movieId: 7,
      screenId: 2,
      languageCode: 'en',
      startsAtLocal: '2027-03-14T20:00:00',
      standardPrice: 12.5,
      premiumPrice: 16,
    });
  });

  it('names every missing or malformed field', () => {
    assert.deepEqual(
      invalidShowtimeFields({
        movieId: '',
        screenId: 'x',
        languageCode: 'EN',
        date: '14/03/2027',
        time: '8pm',
        standardPrice: '0',
        premiumPrice: '12.501',
      }),
      ['movieId', 'screenId', 'languageCode', 'startsAtLocal', 'standardPrice', 'premiumPrice'],
    );
  });

  it('rejects a date outside 2000–2100 and midnight that is not HH:mm', () => {
    assert.ok(invalidShowtimeFields(showtime({ date: '1999-12-31' })).includes('startsAtLocal'));
    assert.ok(invalidShowtimeFields(showtime({ date: '2101-01-01' })).includes('startsAtLocal'));
    assert.ok(invalidShowtimeFields(showtime({ time: '24:00' })).includes('startsAtLocal'));
    assert.ok(invalidShowtimeFields(showtime({ time: '19:00:00' })).includes('startsAtLocal'));
    assert.equal(invalidShowtimeFields(showtime({ time: '00:00' })).includes('startsAtLocal'), false);
  });

  it('accepts prices that are whole cents up to 1000, matching the server', () => {
    assert.equal(isValidPrice('0.01'), true);
    assert.equal(isValidPrice('1000'), true);
    assert.equal(isValidPrice('1000.00'), true);
    assert.equal(isValidPrice('12.5'), true);
    assert.equal(isValidPrice('0'), false);
    assert.equal(isValidPrice('1000.01'), false);
    assert.equal(isValidPrice('12.501'), false);
    assert.equal(isValidPrice('-1'), false);
    assert.equal(isValidPrice('1e2'), false);
  });
});

describe('screen draft', () => {
  it('builds alphabetical rows with premium seats at the back', () => {
    const draft = { ...EMPTY_SCREEN, name: '  Screen 3  ', rowCount: '4', seatsPerRow: '8', premiumRows: '1' };

    assert.deepEqual(invalidScreenFields(draft, true), []);
    assert.deepEqual(screenBody(draft), {
      name: 'Screen 3',
      rows: [
        { rowLabel: 'A', seatCount: 8, category: 'STANDARD' },
        { rowLabel: 'B', seatCount: 8, category: 'STANDARD' },
        { rowLabel: 'C', seatCount: 8, category: 'STANDARD' },
        { rowLabel: 'D', seatCount: 8, category: 'PREMIUM' },
      ],
    });
    assert.equal(layoutRows({ ...draft, premiumRows: '0' }).every((row) => row.category === 'STANDARD'), true);
  });

  it('does not require a name when replacing a layout', () => {
    assert.deepEqual(invalidScreenFields({ name: '', rowCount: '2', seatsPerRow: '4', premiumRows: '0' }, false), []);
    assert.deepEqual(invalidScreenFields({ name: '', rowCount: '2', seatsPerRow: '4', premiumRows: '0' }, true), ['name']);
  });

  it('rejects bounds the server would reject', () => {
    assert.ok(invalidScreenFields({ name: 'x'.repeat(41), rowCount: '6', seatsPerRow: '10', premiumRows: '2' }, true).includes('name'));
    assert.ok(invalidScreenFields({ ...EMPTY_SCREEN, rowCount: '0' }, false).includes('rows'));
    assert.ok(invalidScreenFields({ ...EMPTY_SCREEN, rowCount: '27' }, false).includes('rows'));
    assert.ok(invalidScreenFields({ ...EMPTY_SCREEN, seatsPerRow: '41' }, false).includes('seatsPerRow'));
    assert.ok(invalidScreenFields({ ...EMPTY_SCREEN, seatsPerRow: '0' }, false).includes('seatsPerRow'));
    assert.ok(invalidScreenFields({ ...EMPTY_SCREEN, premiumRows: '7' }, false).includes('premiumRows'));
    assert.ok(invalidScreenFields({ ...EMPTY_SCREEN, premiumRows: '-1' }, false).includes('premiumRows'));
  });
});

describe('admin errors', () => {
  it('explains overlap, busy screens, taken names and locked layouts', () => {
    assert.match(describeAdminError(new ApiError(409, 'SHOWTIME_OVERLAP', 'overlap')), /overlap/i);
    assert.match(describeAdminError(new ApiError(409, 'SCREEN_BUSY', 'busy')), /busy/i);
    assert.match(describeAdminError(new ApiError(409, 'SCREEN_NAME_TAKEN', 'taken')), /already exists/i);
    assert.match(describeAdminError(new ApiError(409, 'SCREEN_HAS_SHOWTIMES', 'locked')), /can no longer change/i);
    assert.match(describeAdminError(new ApiError(400, 'INVALID_START_TIME', 'past')), /daylight-saving|past/i);
  });

  it('lists the invalid fields from the API', () => {
    const error = new ApiError(400, 'INVALID_REQUEST', 'bad', { fields: ['movieId', 'standardPrice'] });

    assert.deepEqual(listedFields(error), ['movieId', 'standardPrice']);
    assert.equal(describeAdminError(error), 'Please check: Movie, Standard price.');
  });

  it('falls back to the shared customer-facing sentences', () => {
    assert.match(describeAdminError(new ApiError(403, 'FORBIDDEN', 'no')), /not allowed/);
    assert.match(describeAdminError(new ApiError(401, 'UNAUTHENTICATED', 'no')), /sign in/i);
  });
});
