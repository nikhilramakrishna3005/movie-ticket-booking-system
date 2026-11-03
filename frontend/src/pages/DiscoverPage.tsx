import { useEffect, useMemo, useState } from 'react';
import { Languages, Search, Sparkles, Tags, X } from 'lucide-react';
import { motion } from 'motion/react';
import { useAuth } from '../state/AuthContext.tsx';
import { useAsync } from '../hooks/useAsync.ts';
import { fetchShowtimes, groupByMovie, resultSummary, uniqueGenres, uniqueLanguages } from '../features/discovery/showtimes.ts';
import { useCityAndDate } from '../features/discovery/useCityAndDate.ts';
import { addDays, longDate } from '../lib/dates.ts';
import { describeError } from '../lib/errors.ts';
import { CitySelect, Select } from '../components/CitySelect.tsx';
import { DateStrip } from '../components/DateStrip.tsx';
import { MovieCard, MovieCardSkeleton } from '../components/MovieCard.tsx';
import { Poster } from '../components/Poster.tsx';
import { Button } from '../components/ui/Button.tsx';
import { StateMessage } from '../components/ui/StateMessage.tsx';

const SEARCH_DEBOUNCE_MS = 300;

export function DiscoverPage() {
  const { api } = useAuth();
  const cities = useAsync(() => api.cities(), [api]);
  const movies = useAsync(() => api.movies(), [api]);
  const { city, date, today, dates, params, update, setCity, setDate } = useCityAndDate(cities.data);

  const q = params.get('q') ?? '';
  const genre = params.get('genre') ?? '';
  const language = params.get('language') ?? '';
  const [searchText, setSearchText] = useState(q);

  // Follows back/forward navigation without trimming what the user is still typing.
  useEffect(() => {
    setSearchText((current) => (current.trim() === q ? current : q));
  }, [q]);
  useEffect(() => {
    if (searchText.trim() === q) return;
    const timer = setTimeout(() => update({ q: searchText.trim() }), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [searchText, q, update]);

  const showtimes = useAsync(
    () =>
      city
        ? fetchShowtimes(api, { cityId: city.id, date, q: q || undefined, genre: genre || undefined, language: language || undefined })
        : new Promise<never>(() => {}),
    [api, city?.id, date, q, genre, language],
  );

  const groups = useMemo(() => groupByMovie(showtimes.data ?? [], movies.data ?? []), [showtimes.data, movies.data]);
  const genres = useMemo(() => uniqueGenres(movies.data ?? []), [movies.data]);
  const languages = useMemo(() => uniqueLanguages(movies.data ?? []), [movies.data]);
  const filtered = Boolean(q || genre || language);
  const linkSearch = city ? `?city=${city.id}&date=${date}` : '';
  const featured = movies.data?.slice(0, 3) ?? [];

  if (cities.status === 'error' && !cities.data) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-20">
        <StateMessage block tone="error" title="Cinéva is not reachable" testId="discover-error" action={<Button onClick={cities.reload}>Try again</Button>}>
          {describeError(cities.error)}
        </StateMessage>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-7xl px-4 pb-20 sm:px-6">
      <section className="relative grid items-center gap-10 py-12 md:grid-cols-[1.2fr_1fr] md:py-16">
        <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.35 }}>
          <p className="eyebrow flex items-center gap-2">
            <Sparkles className="size-3.5 text-violet-300" aria-hidden />
            Now showing{city ? ` in ${city.name}` : ''}
          </p>
          <h1 className="mt-4 font-display text-4xl leading-[1.05] font-semibold tracking-tight text-mist-50 sm:text-6xl">
            Every seat,
            <br />
            <span className="bg-gradient-to-r from-violet-300 to-violet-500 bg-clip-text text-transparent">a premiere.</span>
          </h1>
          <p className="mt-5 max-w-lg text-base leading-relaxed text-mist-400">
            Find a showtime, pick the exact seats you want, and hold them for eight minutes while you check out.
            Live availability, no surprises.
          </p>
        </motion.div>
        <div className="relative hidden h-80 md:block" aria-hidden>
          {featured.map((movie, index) => (
            <motion.div
              key={movie.id}
              initial={{ opacity: 0, y: 16, rotate: 0 }}
              animate={{ opacity: 1, y: 0, rotate: (index - 1) * 7 }}
              transition={{ duration: 0.4, delay: index * 0.05 }}
              className="absolute top-0 left-1/2 w-44 origin-bottom"
              style={{ marginLeft: `${(index - 1) * 120 - 88}px`, zIndex: index === 1 ? 2 : 1 }}
            >
              <Poster title={movie.title} className="aspect-[2/3] rounded-2xl shadow-2xl ring-1 ring-white/10" />
            </motion.div>
          ))}
        </div>
      </section>

      <section aria-label="Find showtimes" className="surface z-20 space-y-4 p-4 sm:p-5 lg:sticky lg:top-16">
        <div className="flex flex-col gap-3 lg:flex-row lg:items-center">
          {cities.data ? (
            <CitySelect cities={cities.data} value={city?.id} onChange={setCity} />
          ) : (
            <div className="skeleton h-11 w-48" />
          )}
          <label className="relative flex flex-1 items-center">
            <span className="sr-only">Search movies</span>
            <Search className="pointer-events-none absolute left-3 size-4 text-mist-500" aria-hidden />
            <input
              type="search"
              value={searchText}
              onChange={(event) => setSearchText(event.target.value)}
              placeholder="Search movies, e.g. Inception"
              data-testid="search-input"
              className="h-11 w-full rounded-xl border border-white/8 bg-ink-850 pr-3 pl-9 text-sm text-mist-50 placeholder:text-mist-500 hover:border-white/15 focus:border-violet-400 focus:outline-none"
            />
          </label>
          <div className="grid grid-cols-2 gap-3 lg:flex">
            <Select
              label="Genre"
              value={genre}
              onChange={(value) => update({ genre: value })}
              options={[{ value: '', label: 'All genres' }, ...genres.map((name) => ({ value: name, label: name }))]}
              icon={<Tags className="size-4" aria-hidden />}
              testId="genre-filter"
              className="lg:w-44"
            />
            <Select
              label="Language"
              value={language}
              onChange={(value) => update({ language: value })}
              options={[{ value: '', label: 'All languages' }, ...languages.map((l) => ({ value: l.code, label: l.name }))]}
              icon={<Languages className="size-4" aria-hidden />}
              testId="language-filter"
              className="lg:w-44"
            />
          </div>
        </div>
        <DateStrip dates={dates} selected={date} today={today} onSelect={setDate} />
      </section>

      <section aria-live="polite" aria-busy={showtimes.status === 'loading'} className="mt-8">
        <div className="mb-4 flex items-baseline justify-between gap-4">
          <h2 className="font-display text-2xl font-semibold text-mist-50">{longDate(date)}</h2>
          {showtimes.status === 'success' && (
            <p className="hidden text-sm text-mist-500 sm:block">
              {resultSummary(groups.length, showtimes.data.length)}
            </p>
          )}
        </div>

        {showtimes.status === 'error' ? (
          <StateMessage tone="error" title="Showtimes could not be loaded" testId="discover-error" action={<Button size="sm" onClick={showtimes.reload}>Try again</Button>}>
            {describeError(showtimes.error)}
          </StateMessage>
        ) : showtimes.status === 'loading' && !showtimes.data ? (
          <div className="grid gap-4 lg:grid-cols-2">
            {[0, 1, 2, 3].map((key) => (
              <MovieCardSkeleton key={key} />
            ))}
          </div>
        ) : groups.length === 0 ? (
          <StateMessage
            block
            title={filtered ? 'No showtimes match your filters' : `Nothing showing on ${longDate(date)}`}
            testId="discover-empty"
            action={
              <>
                {filtered && (
                  <Button variant="secondary" icon={<X className="size-4" aria-hidden />} onClick={() => update({ q: null, genre: null, language: null })}>
                    Clear filters
                  </Button>
                )}
                <Button onClick={() => setDate(addDays(date, 1))}>See {longDate(addDays(date, 1))}</Button>
              </>
            }
          >
            {filtered
              ? 'Try a different search, genre or language.'
              : `There are no remaining showtimes${city ? ` in ${city.name}` : ''} for this day.`}
          </StateMessage>
        ) : (
          <div className={`grid gap-4 transition-opacity lg:grid-cols-2 ${showtimes.status === 'loading' ? 'opacity-60' : ''}`}>
            {groups.map((group, index) => (
              <MovieCard key={group.movieId} group={group} index={index} search={linkSearch} />
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
