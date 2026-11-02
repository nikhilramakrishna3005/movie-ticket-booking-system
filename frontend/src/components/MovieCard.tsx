import { ArrowRight, Clock } from 'lucide-react';
import { motion } from 'motion/react';
import { Link } from 'react-router';
import type { ShowtimeSearchResult } from '../api/types.ts';
import type { MovieShowtimes } from '../features/discovery/showtimes.ts';
import { localTime } from '../lib/dates.ts';
import { duration } from '../lib/format.ts';
import { Poster } from './Poster.tsx';

export function ShowtimeChip({ showtime, showTheater = true }: { showtime: ShowtimeSearchResult; showTheater?: boolean }) {
  return (
    <Link
      to={`/showtimes/${showtime.showtimeId}/seats`}
      data-testid="showtime-chip"
      data-showtime-id={showtime.showtimeId}
      className="group flex flex-col rounded-xl border border-white/8 bg-ink-850 px-3 py-2 transition-colors hover:border-violet-400/50 hover:bg-violet-500/10"
      aria-label={`${showtime.movieTitle}, ${localTime(showtime.localStartTime)} at ${showtime.theaterName}, ${showtime.screenName}, ${showtime.language.name}`}
    >
      <span className="text-sm font-semibold text-mist-50 group-hover:text-violet-300">
        {localTime(showtime.localStartTime)}
      </span>
      <span className="truncate text-[0.7rem] text-mist-500">
        {showTheater ? showtime.theaterName : showtime.screenName} · {showtime.language.code.toUpperCase()}
      </span>
    </Link>
  );
}

const CHIPS_ON_CARD = 4;

export function MovieCard({ group, index, search }: { group: MovieShowtimes; index: number; search: string }) {
  const { movie, title, showtimes } = group;
  const hidden = showtimes.length - CHIPS_ON_CARD;
  return (
    <motion.article
      layout="position"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.25, delay: Math.min(index, 8) * 0.03 }}
      data-testid="movie-card"
      data-movie-id={group.movieId}
      className="surface group/card flex flex-col overflow-hidden sm:flex-row"
    >
      <Link
        to={`/movies/${group.movieId}${search}`}
        className="relative block aspect-[16/7] shrink-0 overflow-hidden sm:aspect-auto sm:w-40"
        tabIndex={-1}
        aria-hidden
      >
        <Poster title={title} showTitle={false} fill className="transition-transform duration-500 group-hover/card:scale-[1.03]" />
      </Link>
      <div className="flex min-w-0 flex-1 flex-col p-5">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h3 className="font-display text-xl leading-tight font-semibold text-mist-50">
              <Link to={`/movies/${group.movieId}${search}`} className="rounded hover:text-violet-300">
                {title}
              </Link>
            </h3>
            <p className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-mist-400">
              {movie && (
                <>
                  <span className="rounded border border-white/12 px-1.5 py-px font-semibold text-mist-200">
                    {movie.certification}
                  </span>
                  <span className="flex items-center gap-1">
                    <Clock className="size-3.5" aria-hidden />
                    {duration(movie.durationMinutes)}
                  </span>
                  <span>{movie.genres.join(' · ')}</span>
                </>
              )}
            </p>
          </div>
        </div>
        <div className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-3">
          {showtimes.slice(0, CHIPS_ON_CARD).map((showtime) => (
            <ShowtimeChip key={showtime.showtimeId} showtime={showtime} />
          ))}
        </div>
        <Link
          to={`/movies/${group.movieId}${search}`}
          className="mt-4 inline-flex items-center gap-1.5 self-start rounded text-sm font-medium text-violet-300 hover:text-violet-400"
        >
          {hidden > 0 ? `All ${showtimes.length} showtimes` : 'Details and theaters'}
          <ArrowRight className="size-4" aria-hidden />
        </Link>
      </div>
    </motion.article>
  );
}

export function MovieCardSkeleton() {
  return (
    <div className="surface flex flex-col overflow-hidden sm:flex-row" aria-hidden>
      <div className="skeleton aspect-[16/7] rounded-none sm:aspect-auto sm:h-52 sm:w-40" />
      <div className="flex-1 space-y-3 p-5">
        <div className="skeleton h-6 w-2/3" />
        <div className="skeleton h-4 w-1/2" />
        <div className="grid grid-cols-2 gap-2 pt-2 sm:grid-cols-4">
          {[0, 1, 2, 3].map((key) => (
            <div key={key} className="skeleton h-12" />
          ))}
        </div>
      </div>
    </div>
  );
}
