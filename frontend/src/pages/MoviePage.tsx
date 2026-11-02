import { useMemo } from 'react';
import { ArrowLeft, CalendarDays, Clock, Languages, MapPin } from 'lucide-react';
import { motion } from 'motion/react';
import { Link, useParams } from 'react-router';
import { useAuth } from '../state/AuthContext.tsx';
import { useAsync } from '../hooks/useAsync.ts';
import { fetchShowtimes, groupByTheater } from '../features/discovery/showtimes.ts';
import { useCityAndDate } from '../features/discovery/useCityAndDate.ts';
import { addDays, longDate } from '../lib/dates.ts';
import { describeError } from '../lib/errors.ts';
import { duration } from '../lib/format.ts';
import { CitySelect } from '../components/CitySelect.tsx';
import { DateStrip } from '../components/DateStrip.tsx';
import { ShowtimeChip } from '../components/MovieCard.tsx';
import { Poster } from '../components/Poster.tsx';
import { Button, ButtonLink } from '../components/ui/Button.tsx';
import { StateMessage } from '../components/ui/StateMessage.tsx';

export function MoviePage() {
  const movieId = Number(useParams().movieId);
  const { api } = useAuth();
  const cities = useAsync(() => api.cities(), [api]);
  const movies = useAsync(() => api.movies(), [api]);
  const { city, date, today, dates, setCity, setDate } = useCityAndDate(cities.data);
  const movie = movies.data?.find((candidate) => candidate.id === movieId);

  const showtimes = useAsync(
    () => (city ? fetchShowtimes(api, { cityId: city.id, date, movieId }) : new Promise<never>(() => {})),
    [api, city?.id, date, movieId],
  );
  const theaters = useMemo(() => groupByTheater(showtimes.data ?? []), [showtimes.data]);
  const backSearch = city ? `?city=${city.id}&date=${date}` : '';

  if (movies.status === 'error' || cities.status === 'error') {
    return (
      <div className="mx-auto max-w-3xl px-4 py-20">
        <StateMessage block tone="error" title="This movie could not be loaded" action={<Button onClick={() => { movies.reload(); cities.reload(); }}>Try again</Button>}>
          {describeError(movies.error ?? cities.error)}
        </StateMessage>
      </div>
    );
  }
  if (movies.status === 'success' && !movie) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-20">
        <StateMessage block title="Movie not found" action={<ButtonLink to="/">Browse movies</ButtonLink>}>
          This movie is not in the Cinéva catalog.
        </StateMessage>
      </div>
    );
  }

  return (
    <div className="pb-20">
      <section className="relative overflow-hidden border-b border-white/5">
        {movie && <Poster title={movie.title} showTitle={false} fill className="scale-110 opacity-40 blur-2xl" />}
        <div className="absolute inset-0 bg-gradient-to-b from-ink-950/40 via-ink-950/70 to-ink-950" aria-hidden />
        <div className="relative mx-auto flex max-w-7xl flex-col gap-8 px-4 py-10 sm:px-6 md:flex-row md:items-end md:py-14">
          {movie ? (
            <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.3 }} className="w-40 shrink-0 sm:w-52">
              <Poster title={movie.title} className="aspect-[2/3] rounded-2xl shadow-2xl ring-1 ring-white/10" />
            </motion.div>
          ) : (
            <div className="skeleton aspect-[2/3] w-40 sm:w-52" />
          )}
          <div className="min-w-0">
            <Link to={`/${backSearch}`} className="inline-flex items-center gap-1.5 rounded text-sm text-mist-400 hover:text-mist-50">
              <ArrowLeft className="size-4" aria-hidden /> All movies
            </Link>
            {movie ? (
              <>
                <h1 data-testid="movie-title" className="mt-3 font-display text-4xl font-semibold tracking-tight text-mist-50 sm:text-5xl">
                  {movie.title}
                </h1>
                <div className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-2 text-sm text-mist-400">
                  <span className="rounded-md border border-white/15 px-2 py-0.5 font-semibold text-mist-200">{movie.certification}</span>
                  <span className="flex items-center gap-1.5"><Clock className="size-4" aria-hidden />{duration(movie.durationMinutes)}</span>
                  <span className="flex items-center gap-1.5"><Languages className="size-4" aria-hidden />{movie.originalLanguage.name}</span>
                  <span className="flex items-center gap-1.5"><CalendarDays className="size-4" aria-hidden />Released {movie.releaseDate.slice(0, 4)}</span>
                </div>
                <div className="mt-4 flex flex-wrap gap-2">
                  {movie.genres.map((genre) => (
                    <span key={genre} className="rounded-full bg-white/6 px-3 py-1 text-xs font-medium text-mist-200">{genre}</span>
                  ))}
                </div>
              </>
            ) : (
              <div className="mt-3 space-y-3"><div className="skeleton h-12 w-72" /><div className="skeleton h-5 w-96 max-w-full" /></div>
            )}
          </div>
        </div>
      </section>

      <div className="mx-auto max-w-7xl px-4 sm:px-6">
        <section aria-label="Choose a date and city" className="mt-8 flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
          <DateStrip dates={dates} selected={date} today={today} onSelect={setDate} />
          {cities.data && <CitySelect cities={cities.data} value={city?.id} onChange={setCity} />}
        </section>

        <section className="mt-8" aria-live="polite" aria-busy={showtimes.status === 'loading'}>
          <h2 className="mb-4 font-display text-2xl font-semibold text-mist-50">
            Showtimes · {longDate(date)}
          </h2>
          {showtimes.status === 'error' ? (
            <StateMessage tone="error" title="Showtimes could not be loaded" action={<Button size="sm" onClick={showtimes.reload}>Try again</Button>}>
              {describeError(showtimes.error)}
            </StateMessage>
          ) : showtimes.status === 'loading' && !showtimes.data ? (
            <div className="space-y-4">{[0, 1].map((key) => <div key={key} className="skeleton h-32" />)}</div>
          ) : theaters.length === 0 ? (
            <StateMessage block title="No showtimes on this day" testId="movie-empty" action={<Button onClick={() => setDate(addDays(date, 1))}>See {longDate(addDays(date, 1))}</Button>}>
              {movie?.title ?? 'This movie'} has no remaining showtimes{city ? ` in ${city.name}` : ''} on {longDate(date)}.
            </StateMessage>
          ) : (
            <div className="space-y-4">
              {theaters.map((theater) => (
                <article key={theater.theaterId} data-testid="theater-group" className="surface p-5">
                  <h3 className="flex items-center gap-2 font-semibold text-mist-50">
                    <MapPin className="size-4 text-violet-300" aria-hidden />
                    {theater.theaterName}
                  </h3>
                  <div className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-6">
                    {theater.showtimes.map((showtime) => (
                      <ShowtimeChip key={showtime.showtimeId} showtime={showtime} showTheater={false} />
                    ))}
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
