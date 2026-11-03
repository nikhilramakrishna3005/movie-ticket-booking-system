import type { ApiClient } from '../../api/client.ts';
import type { Movie, ShowtimeSearchParams, ShowtimeSearchResult } from '../../api/types.ts';

const PAGE_SIZE = 50;
const MAX_PAGES = 6;

/**
 * One city-day of showtimes is small (the seed has at most a few dozen), so discovery reads all
 * pages and groups on the client. The page cap keeps an unexpectedly large result bounded.
 */
export async function fetchShowtimes(api: ApiClient, params: ShowtimeSearchParams): Promise<ShowtimeSearchResult[]> {
  const items: ShowtimeSearchResult[] = [];
  for (let page = 0; page < MAX_PAGES; page++) {
    const result = await api.searchShowtimes({ ...params, page, size: PAGE_SIZE });
    items.push(...result.items);
    if (page + 1 >= result.totalPages) break;
  }
  return items;
}

export interface MovieShowtimes {
  movieId: number;
  title: string;
  movie: Movie | undefined;
  showtimes: ShowtimeSearchResult[];
}

/** Groups showtimes by movie, in order of each movie's earliest show, then title. */
export function groupByMovie(showtimes: ShowtimeSearchResult[], movies: Movie[]): MovieShowtimes[] {
  const byId = new Map(movies.map((movie) => [movie.id, movie]));
  const groups = new Map<number, MovieShowtimes>();
  for (const showtime of [...showtimes].sort(compareShowtimes)) {
    let group = groups.get(showtime.movieId);
    if (!group) {
      group = { movieId: showtime.movieId, title: showtime.movieTitle, movie: byId.get(showtime.movieId), showtimes: [] };
      groups.set(showtime.movieId, group);
    }
    group.showtimes.push(showtime);
  }
  return [...groups.values()];
}

export interface TheaterShowtimes {
  theaterId: number;
  theaterName: string;
  showtimes: ShowtimeSearchResult[];
}

export function groupByTheater(showtimes: ShowtimeSearchResult[]): TheaterShowtimes[] {
  const groups = new Map<number, TheaterShowtimes>();
  for (const showtime of [...showtimes].sort(compareShowtimes)) {
    let group = groups.get(showtime.theaterId);
    if (!group) {
      group = { theaterId: showtime.theaterId, theaterName: showtime.theaterName, showtimes: [] };
      groups.set(showtime.theaterId, group);
    }
    group.showtimes.push(showtime);
  }
  return [...groups.values()].sort((a, b) => a.theaterName.localeCompare(b.theaterName));
}

function compareShowtimes(a: ShowtimeSearchResult, b: ShowtimeSearchResult): number {
  return a.startsAt.localeCompare(b.startsAt) || a.movieTitle.localeCompare(b.movieTitle) || a.showtimeId - b.showtimeId;
}

/** 1, 1 → "1 movie · 1 showtime"; 2, 5 → "2 movies · 5 showtimes" */
export function resultSummary(movieCount: number, showtimeCount: number): string {
  const count = (n: number, noun: string) => `${n} ${n === 1 ? noun : `${noun}s`}`;
  return `${count(movieCount, 'movie')} · ${count(showtimeCount, 'showtime')}`;
}

export function uniqueGenres(movies: Movie[]): string[] {
  return [...new Set(movies.flatMap((movie) => movie.genres))].sort();
}

export function uniqueLanguages(movies: Movie[]): Array<{ code: string; name: string }> {
  const byCode = new Map(movies.map((movie) => [movie.originalLanguage.code, movie.originalLanguage]));
  return [...byCode.values()].sort((a, b) => a.name.localeCompare(b.name));
}
