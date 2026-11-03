import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createApiClient } from '../../api/client.ts';
import type { Movie, ShowtimeSearchResult } from '../../api/types.ts';
import { fetchShowtimes, groupByMovie, groupByTheater, resultSummary, uniqueGenres } from './showtimes.ts';

const show = (id: number, movieId: number, theaterId: number, startsAt: string): ShowtimeSearchResult =>
  ({
    showtimeId: id,
    movieId,
    movieTitle: `Movie ${movieId}`,
    theaterId,
    theaterName: `Theater ${theaterId}`,
    startsAt,
  }) as ShowtimeSearchResult;

const movie = (id: number, genres: string[]): Movie => ({ id, title: `Movie ${id}`, genres }) as Movie;

describe('discovery grouping', () => {
  it('orders movies by their earliest show and keeps showtimes chronological', () => {
    const groups = groupByMovie(
      [
        show(1, 2, 1, '2026-11-03T22:30:00Z'),
        show(2, 1, 1, '2026-11-03T19:00:00Z'),
        show(3, 2, 2, '2026-11-03T19:00:00Z'),
      ],
      [movie(1, []), movie(2, [])],
    );
    assert.deepEqual(groups.map((group) => group.movieId), [1, 2]);
    assert.deepEqual(groups[1]!.showtimes.map((s) => s.showtimeId), [3, 1]);
    assert.equal(groups[0]!.movie?.id, 1);
  });

  it('groups a movie by theater', () => {
    const groups = groupByTheater([show(1, 1, 2, '2026-11-03T19:00:00Z'), show(2, 1, 1, '2026-11-03T22:30:00Z')]);
    assert.deepEqual(groups.map((group) => group.theaterName), ['Theater 1', 'Theater 2']);
  });

  it('summarizes results with singular and plural nouns', () => {
    assert.equal(resultSummary(1, 1), '1 movie · 1 showtime');
    assert.equal(resultSummary(0, 0), '0 movies · 0 showtimes');
    assert.equal(resultSummary(1, 3), '1 movie · 3 showtimes');
    assert.equal(resultSummary(4, 12), '4 movies · 12 showtimes');
  });

  it('lists unique genres', () => {
    assert.deepEqual(uniqueGenres([movie(1, ['Drama', 'Action']), movie(2, ['Drama'])]), ['Action', 'Drama']);
  });

  it('reads every page of a search', async () => {
    const requested: string[] = [];
    const api = createApiClient({
      getCredentials: () => null,
      fetch: (async (input: RequestInfo | URL) => {
        requested.push(String(input));
        const page = Number(new URL(String(input), 'http://x').searchParams.get('page'));
        return new Response(JSON.stringify({ items: [show(page, 1, 1, 'x')], page, size: 50, totalItems: 2, totalPages: 2 }));
      }) as typeof fetch,
    });

    const items = await fetchShowtimes(api, { cityId: 1, date: '2026-11-03' });

    assert.equal(items.length, 2);
    assert.deepEqual(requested, [
      '/api/showtimes?cityId=1&date=2026-11-03&page=0&size=50',
      '/api/showtimes?cityId=1&date=2026-11-03&page=1&size=50',
    ]);
  });
});
