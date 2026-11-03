import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react';
import { Ban, CalendarDays, Clapperboard, Lock, LogIn, Plus, Sparkles } from 'lucide-react';
import { useSearchParams } from 'react-router';
import { ApiError } from '../api/client.ts';
import type { CreatedShowtime, ScheduleEntry, ScreenDetail, ScreenSummary, TheaterOverview, TheaterSchedule } from '../api/types.ts';
import { useAuth } from '../state/AuthContext.tsx';
import { useAsync } from '../hooks/useAsync.ts';
import {
  EMPTY_SCREEN,
  EMPTY_SHOWTIME,
  describeAdminError,
  invalidScreenFields,
  invalidShowtimeFields,
  layoutRows,
  listedFields,
  screenBody,
  showtimeBody,
  type ScreenDraft,
  type ShowtimeDraft,
} from '../features/admin/forms.ts';
import { Select } from '../components/CitySelect.tsx';
import { DateStrip } from '../components/DateStrip.tsx';
import { Button } from '../components/ui/Button.tsx';
import { StateMessage } from '../components/ui/StateMessage.tsx';
import { dateRange, isIsoDate, localTime, longDate, todayIn } from '../lib/dates.ts';
import { describeError } from '../lib/errors.ts';
import { price } from '../lib/format.ts';

const fieldClass =
  'mt-1.5 h-11 w-full rounded-xl border border-white/8 bg-ink-850 px-3.5 text-sm text-mist-50 placeholder:text-mist-500 focus:border-violet-400 focus:outline-none disabled:opacity-50';
const fieldClassError = `${fieldClass} border-danger-400/50`;

export function AdminPage() {
  const { user, requestLogin } = useAuth();

  if (!user) {
    return (
      <Shell>
        <StateMessage
          block
          title="Sign in to manage your theaters"
          testId="admin-signed-out"
          action={
            <Button onClick={() => void requestLogin()} icon={<LogIn className="size-4" aria-hidden />} data-testid="admin-sign-in">
              Sign in
            </Button>
          }
        >
          Theater administration is limited to assigned theater-admin accounts. Cinéva keeps your sign-in in memory
          only, so reloading the page always asks you to sign in again.
        </StateMessage>
      </Shell>
    );
  }
  if (user.role !== 'THEATER_ADMIN') {
    return (
      <Shell>
        <StateMessage block title="Theater admin account required" testId="admin-not-admin">
          Customer accounts book tickets; they cannot schedule showtimes or change screens. Sign out and sign in with
          a theater admin demo account.
        </StateMessage>
      </Shell>
    );
  }
  return <Dashboard key={user.id} />;
}

function Shell({ children, subtitle }: { children: ReactNode; subtitle?: string }) {
  return (
    <div className="mx-auto max-w-7xl px-4 py-10 sm:px-6 sm:py-14">
      <p className="eyebrow flex items-center gap-2">
        <Sparkles className="size-3.5 text-violet-300" aria-hidden />
        Theater operations
      </p>
      <h1 className="mt-3 font-display text-4xl font-semibold tracking-tight text-mist-50 sm:text-5xl">Admin</h1>
      {subtitle && <p className="mt-2 text-mist-400">{subtitle}</p>}
      <div className="mt-8">{children}</div>
    </div>
  );
}

function Dashboard() {
  const { api, requestLogin } = useAuth();
  const [params, setParams] = useSearchParams();
  const theaters = useAsync(() => api.adminTheaters(), [api]);
  const movies = useAsync(() => api.movies(), [api]);
  const languages = useAsync(() => api.languages(), [api]);

  const theaterId = Number(params.get('theater')) || theaters.data?.[0]?.theaterId;
  const theater = theaters.data?.find((entry) => entry.theaterId === theaterId);
  const dateParam = params.get('date');
  const date = isIsoDate(dateParam) ? dateParam : undefined;

  const schedule = useAsync(
    () => (theaterId ? api.adminSchedule(theaterId, date || undefined) : new Promise<never>(() => {})),
    [api, theaterId, date],
  );

  const details = useAsync(async () => {
    if (!theater) return [] as ScreenDetail[];
    return Promise.all(theater.screens.map((screen) => api.adminScreen(screen.screenId)));
  }, [api, theater]);

  useEffect(() => {
    if (!theaters.data?.length) return;
    const assigned = theaters.data;
    const fallbackDate = schedule.data?.date;
    setParams((current) => {
      const next = new URLSearchParams(current);
      let changed = false;
      if (!assigned.some((entry) => entry.theaterId === Number(next.get('theater')))) {
        next.set('theater', String(assigned[0]!.theaterId));
        changed = true;
      }
      if (!isIsoDate(next.get('date')) && fallbackDate) {
        next.set('date', fallbackDate);
        changed = true;
      }
      return changed ? next : current;
    }, { replace: true });
  }, [theaters.data, schedule.data?.date, setParams]);

  const setTheater = (id: number) => {
    const next = new URLSearchParams(params);
    next.set('theater', String(id));
    setParams(next);
  };
  const setDate = (nextDate: string) => {
    const next = new URLSearchParams(params);
    next.set('date', nextDate);
    setParams(next);
  };

  if (theaters.status === 'error' && !theaters.data) {
    return (
      <Shell>
        <StateMessage
          block
          tone="error"
          title="Theaters could not be loaded"
          testId="admin-error"
          action={<Button onClick={theaters.reload} data-testid="admin-retry">Try again</Button>}
        >
          {describeError(theaters.error)}
        </StateMessage>
      </Shell>
    );
  }
  if (theaters.data && theaters.data.length === 0) {
    return (
      <Shell>
        <StateMessage block title="No theaters assigned" testId="admin-empty">
          This admin account is not assigned to a theater, so there is nothing to manage.
        </StateMessage>
      </Shell>
    );
  }
  if (!theaters.data || !theater) {
    return (
      <Shell>
        <div className="grid gap-4" aria-busy data-testid="admin-loading">
          <span className="sr-only">Loading theaters</span>
          <div className="skeleton h-24" />
          <div className="grid gap-4 lg:grid-cols-2">
            <div className="skeleton h-64" />
            <div className="skeleton h-64" />
          </div>
        </div>
      </Shell>
    );
  }

  const today = todayIn(theater.city.timeZone);
  const selectedDate = schedule.data?.date ?? date ?? today;
  const stripDates = dateRange(today, 14);

  return (
    <Shell subtitle={`${theater.name} · ${theater.city.name}, ${theater.city.stateCode}`}>
      <div className="mb-8 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between" data-testid="admin-dashboard">
        <div>
          {theaters.data.length > 1 ? (
            <Select
              label="Theater"
              value={String(theater.theaterId)}
              onChange={(value) => setTheater(Number(value))}
              options={theaters.data.map((entry) => ({ value: String(entry.theaterId), label: entry.name }))}
              testId="admin-theater-select"
              className="min-w-72"
            />
          ) : (
            <p data-testid="admin-theater-name" className="font-display text-2xl text-mist-50">
              {theater.name}
            </p>
          )}
          <p className="mt-1 text-sm text-mist-400">{theater.address}</p>
        </div>
      </div>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,22rem)_1fr]">
        <ScreensColumn
          theater={theater}
          details={details.data ?? []}
          detailsLoading={details.status === 'loading' && !details.data}
          onCreated={() => {
            theaters.reload();
            details.reload();
          }}
          onLayoutChanged={() => {
            theaters.reload();
            details.reload();
          }}
          onAuthLost={requestLogin}
        />
        <ScheduleColumn
          theater={theater}
          schedule={schedule.data}
          scheduleError={schedule.status === 'error' ? schedule.error : null}
          loading={schedule.status === 'loading' && !schedule.data}
          today={today}
          selectedDate={selectedDate}
          stripDates={stripDates}
          onSelectDate={setDate}
          onRetry={schedule.reload}
          movies={movies.data ?? []}
          languages={languages.data ?? []}
          onCreated={(created) => {
            const next = new URLSearchParams(params);
            next.set('theater', String(created.theaterId));
            next.set('date', created.localDate);
            setParams(next, { replace: true });
            theaters.reload();
          }}
          onCancelled={schedule.reload}
          onAuthLost={requestLogin}
        />
      </div>
    </Shell>
  );
}

function ScreensColumn({
  theater,
  details,
  detailsLoading,
  onCreated,
  onLayoutChanged,
  onAuthLost,
}: {
  theater: TheaterOverview;
  details: ScreenDetail[];
  detailsLoading: boolean;
  onCreated: () => void;
  onLayoutChanged: () => void;
  onAuthLost: () => Promise<unknown>;
}) {
  const [adding, setAdding] = useState(false);
  return (
    <section className="space-y-4" aria-labelledby="admin-screens-heading">
      <div className="flex items-center justify-between gap-3">
        <h2 id="admin-screens-heading" className="font-display text-2xl text-mist-50">
          Screens
        </h2>
        <Button
          size="sm"
          variant="secondary"
          onClick={() => setAdding((open) => !open)}
          icon={<Plus className="size-4" aria-hidden />}
          data-testid="admin-add-screen-toggle"
        >
          Add screen
        </Button>
      </div>
      {adding && (
        <ScreenForm
          theaterId={theater.theaterId}
          requireName
          submitLabel="Create screen"
          testId="admin-add-screen"
          onDone={() => {
            setAdding(false);
            onCreated();
          }}
          onAuthLost={onAuthLost}
        />
      )}
      <div className="grid gap-3" data-testid="admin-screens">
        {theater.screens.map((screen) => (
          <ScreenCard
            key={screen.screenId}
            screen={screen}
            detail={details.find((entry) => entry.screenId === screen.screenId)}
            loading={detailsLoading}
            onLayoutChanged={onLayoutChanged}
            onAuthLost={onAuthLost}
          />
        ))}
        {theater.screens.length === 0 && (
          <StateMessage title="No screens yet">Add a screen with a seating plan before you schedule a showtime.</StateMessage>
        )}
      </div>
    </section>
  );
}

function ScreenCard({
  screen,
  detail,
  loading,
  onLayoutChanged,
  onAuthLost,
}: {
  screen: ScreenSummary;
  detail: ScreenDetail | undefined;
  loading: boolean;
  onLayoutChanged: () => void;
  onAuthLost: () => Promise<unknown>;
}) {
  const [editing, setEditing] = useState(false);
  const rows = detail ? rowsFromSeats(detail.seats) : [];
  return (
    <article
      className="surface p-4 sm:p-5"
      data-testid="admin-screen-card"
      data-screen-id={screen.screenId}
      data-layout-locked={screen.layoutLocked}
    >
      <div className="flex items-start justify-between gap-3">
        <div>
          <h3 className="font-semibold text-mist-50">{screen.name}</h3>
          <p className="mt-1 text-sm text-mist-400">
            {screen.rowCount} {screen.rowCount === 1 ? 'row' : 'rows'} · {screen.seatCount} seats
            {screen.premiumSeats > 0 ? ` · ${screen.premiumSeats} premium` : ''}
          </p>
        </div>
        {screen.layoutLocked ? (
          <span className="inline-flex items-center gap-1 rounded-full bg-white/6 px-2.5 py-0.5 text-[0.7rem] font-semibold tracking-wide text-mist-400 uppercase">
            <Lock className="size-3" aria-hidden />
            Layout locked
          </span>
        ) : (
          <Button size="sm" variant="ghost" onClick={() => setEditing((open) => !open)} data-testid="admin-edit-layout">
            {editing ? 'Cancel' : 'Edit layout'}
          </Button>
        )}
      </div>
      <div className="mt-4 overflow-x-auto">
        {loading && !detail ? (
          <div className="skeleton h-16" />
        ) : (
          <LayoutDots rows={rows} />
        )}
      </div>
      {screen.layoutLocked && (
        <p className="mt-3 text-xs text-mist-500">
          Showtimes already exist for this screen, so its seats cannot change. Tickets and inventory point at these
          seats.
        </p>
      )}
      {editing && !screen.layoutLocked && (
        <div className="mt-4">
          <ScreenForm
            screenId={screen.screenId}
            requireName={false}
            submitLabel="Replace layout"
            testId="admin-replace-layout"
            onDone={() => {
              setEditing(false);
              onLayoutChanged();
            }}
            onAuthLost={onAuthLost}
          />
        </div>
      )}
    </article>
  );
}

function ScreenForm({
  theaterId,
  screenId,
  requireName,
  submitLabel,
  testId,
  onDone,
  onAuthLost,
}: {
  theaterId?: number;
  screenId?: number;
  requireName: boolean;
  submitLabel: string;
  testId: string;
  onDone: () => void;
  onAuthLost: () => Promise<unknown>;
}) {
  const { api } = useAuth();
  const [draft, setDraft] = useState<ScreenDraft>(EMPTY_SCREEN);
  const [fields, setFields] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const preview = invalidScreenFields(draft, requireName).length === 0 ? layoutRows(draft) : [];

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const invalid = invalidScreenFields(draft, requireName);
    setFields(invalid);
    setError(null);
    if (invalid.length > 0) return;
    setSaving(true);
    try {
      const body = screenBody(draft);
      if (requireName && theaterId !== undefined) {
        await api.createScreen(theaterId, body.name, body.rows);
      } else if (screenId !== undefined) {
        await api.replaceLayout(screenId, body.rows);
      }
      onDone();
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) {
        const user = await onAuthLost();
        if (!user) return;
        setError('Please try again now that you are signed in.');
        return;
      }
      setFields(listedFields(caught));
      setError(describeAdminError(caught));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={submit} className="surface space-y-3 p-4" data-testid={testId}>
      {requireName && (
        <Field label="Screen name" error={fields.includes('name')}>
          <input
            value={draft.name}
            onChange={(event) => setDraft({ ...draft, name: event.target.value })}
            className={fields.includes('name') ? fieldClassError : fieldClass}
            maxLength={40}
            data-testid="admin-screen-name"
          />
        </Field>
      )}
      <div className="grid grid-cols-3 gap-3">
        <Field label="Rows" error={fields.includes('rows')}>
          <input
            inputMode="numeric"
            value={draft.rowCount}
            onChange={(event) => setDraft({ ...draft, rowCount: event.target.value })}
            className={fields.includes('rows') ? fieldClassError : fieldClass}
            data-testid="admin-screen-rows"
          />
        </Field>
        <Field label="Seats / row" error={fields.includes('seatsPerRow')}>
          <input
            inputMode="numeric"
            value={draft.seatsPerRow}
            onChange={(event) => setDraft({ ...draft, seatsPerRow: event.target.value })}
            className={fields.includes('seatsPerRow') ? fieldClassError : fieldClass}
            data-testid="admin-screen-seats"
          />
        </Field>
        <Field label="Premium at back" error={fields.includes('premiumRows')}>
          <input
            inputMode="numeric"
            value={draft.premiumRows}
            onChange={(event) => setDraft({ ...draft, premiumRows: event.target.value })}
            className={fields.includes('premiumRows') ? fieldClassError : fieldClass}
            data-testid="admin-screen-premium"
          />
        </Field>
      </div>
      {preview.length > 0 && (
        <div className="overflow-x-auto rounded-xl bg-ink-850/80 p-3">
          <LayoutDots rows={preview} />
        </div>
      )}
      {error && (
        <p role="alert" data-testid={`${testId}-error`} className="rounded-xl bg-danger-400/10 px-3.5 py-2.5 text-sm text-danger-400">
          {error}
        </p>
      )}
      <Button type="submit" className="w-full" loading={saving} data-testid={`${testId}-submit`}>
        {submitLabel}
      </Button>
    </form>
  );
}

function ScheduleColumn({
  theater,
  schedule,
  scheduleError,
  loading,
  today,
  selectedDate,
  stripDates,
  onSelectDate,
  onRetry,
  movies,
  languages,
  onCreated,
  onCancelled,
  onAuthLost,
}: {
  theater: TheaterOverview;
  schedule: TheaterSchedule | undefined;
  scheduleError: unknown;
  loading: boolean;
  today: string;
  selectedDate: string;
  stripDates: string[];
  onSelectDate: (date: string) => void;
  onRetry: () => void;
  movies: Array<{ id: number; title: string }>;
  languages: Array<{ code: string; name: string }>;
  onCreated: (created: CreatedShowtime) => void;
  onCancelled: () => void;
  onAuthLost: () => Promise<unknown>;
}) {
  return (
    <section className="space-y-4" aria-labelledby="admin-schedule-heading">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h2 id="admin-schedule-heading" className="font-display text-2xl text-mist-50">
            Showtimes
          </h2>
          <p className="mt-1 text-sm text-mist-400">{longDate(selectedDate)} · {theater.city.timeZone.replace(/_/g, ' ')}</p>
        </div>
        <label className="block text-sm font-medium text-mist-200">
          <span className="sr-only">Jump to date</span>
          <input
            type="date"
            value={selectedDate}
            onChange={(event) => isIsoDate(event.target.value) && onSelectDate(event.target.value)}
            data-testid="admin-date-input"
            style={{ colorScheme: 'dark' }}
            className="h-11 rounded-xl border border-white/8 bg-ink-850 px-3 text-sm text-mist-50 focus:border-violet-400 focus:outline-none"
          />
        </label>
      </div>
      {stripDates.includes(selectedDate) && (
        <DateStrip dates={stripDates} selected={selectedDate} today={today} onSelect={onSelectDate} />
      )}

      <CreateShowtimeForm
        theater={theater}
        date={selectedDate}
        movies={movies}
        languages={languages}
        onCreated={onCreated}
        onAuthLost={onAuthLost}
      />

      <div data-testid="admin-schedule">
        {scheduleError && !schedule ? (
          <StateMessage
            block
            tone="error"
            title="Schedule could not be loaded"
            testId="admin-schedule-error"
            action={<Button onClick={onRetry}>Try again</Button>}
          >
            {describeError(scheduleError)}
          </StateMessage>
        ) : loading ? (
          <div className="space-y-3" aria-busy>
            <div className="skeleton h-24" />
            <div className="skeleton h-24" />
          </div>
        ) : schedule && schedule.showtimes.length === 0 ? (
          <StateMessage title="No showtimes on this day" testId="admin-schedule-empty">
            Schedule a showtime below, or pick another date.
          </StateMessage>
        ) : (
          <ol className="grid gap-3">
            {schedule?.showtimes.map((entry) => (
              <ShowtimeCard key={entry.showtimeId} entry={entry} onCancelled={onCancelled} onAuthLost={onAuthLost} />
            ))}
          </ol>
        )}
      </div>
    </section>
  );
}

function ShowtimeCard({
  entry,
  onCancelled,
  onAuthLost,
}: {
  entry: ScheduleEntry;
  onCancelled: () => void;
  onAuthLost: () => Promise<unknown>;
}) {
  const { api } = useAuth();
  const [cancelling, setCancelling] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const open = entry.seatCount - entry.bookedSeats - entry.heldSeats;
  const cancelled = entry.status === 'CANCELLED';

  const cancel = async () => {
    setError(null);
    setCancelling(true);
    try {
      await api.cancelShowtime(entry.showtimeId);
      onCancelled();
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) {
        const user = await onAuthLost();
        if (!user) return;
        setError('Please try again now that you are signed in.');
        return;
      }
      setError(describeAdminError(caught));
    } finally {
      setCancelling(false);
    }
  };

  return (
    <li
      data-testid="admin-showtime"
      data-showtime-id={entry.showtimeId}
      data-movie-id={entry.movieId}
      data-screen-id={entry.screenId}
      data-status={entry.status}
      className={`surface p-4 sm:p-5 ${cancelled ? 'opacity-70' : ''}`}
    >
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <p className="eyebrow">{entry.screenName}</p>
          <h3 className="mt-1 font-display text-xl text-mist-50">{entry.movieTitle}</h3>
          <p className="mt-1 flex items-center gap-2 text-sm text-mist-300">
            <CalendarDays className="size-4 text-violet-300" aria-hidden />
            {localTime(entry.localStartTime)} – {localTime(entry.localEndTime)}
            <span className="text-mist-500">· {entry.languageCode.toUpperCase()}</span>
            {cancelled && (
              <span className="text-danger-400" data-testid="admin-showtime-cancelled">
                Cancelled
              </span>
            )}
          </p>
        </div>
        <div className="flex flex-col items-stretch gap-2 sm:items-end">
          <dl className="grid grid-cols-2 gap-x-6 gap-y-1 text-sm sm:text-right">
            <div>
              <dt className="text-mist-500">Standard</dt>
              <dd className="text-mist-50">{entry.standardPrice == null ? '—' : price(entry.standardPrice)}</dd>
            </div>
            <div>
              <dt className="text-mist-500">Premium</dt>
              <dd className="text-gold-300">{entry.premiumPrice == null ? '—' : price(entry.premiumPrice)}</dd>
            </div>
          </dl>
          {!cancelled && (
            <Button
              size="sm"
              variant="danger"
              loading={cancelling}
              onClick={() => void cancel()}
              icon={<Ban className="size-4" aria-hidden />}
              data-testid="admin-cancel-showtime"
            >
              Cancel showtime
            </Button>
          )}
        </div>
      </div>
      <p className="mt-3 text-xs text-mist-500">
        {entry.bookedSeats} booked · {entry.heldSeats} held · {open} open · {entry.seatCount} seats
      </p>
      {error && (
        <p role="alert" data-testid="admin-cancel-error" className="mt-3 rounded-xl bg-danger-400/10 px-3.5 py-2.5 text-sm text-danger-400">
          {error}
        </p>
      )}
    </li>
  );
}

function CreateShowtimeForm({
  theater,
  date,
  movies,
  languages,
  onCreated,
  onAuthLost,
}: {
  theater: TheaterOverview;
  date: string;
  movies: Array<{ id: number; title: string }>;
  languages: Array<{ code: string; name: string }>;
  onCreated: (created: CreatedShowtime) => void;
  onAuthLost: () => Promise<unknown>;
}) {
  const { api } = useAuth();
  const [draft, setDraft] = useState<ShowtimeDraft>({
    ...EMPTY_SHOWTIME,
    date,
    screenId: theater.screens[0] ? String(theater.screens[0].screenId) : '',
  });
  const [fields, setFields] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    setDraft((current) => {
      const screenStillThere = theater.screens.some((screen) => String(screen.screenId) === current.screenId);
      return {
        ...current,
        date: current.date || date,
        screenId: screenStillThere ? current.screenId : theater.screens[0] ? String(theater.screens[0].screenId) : '',
      };
    });
  }, [theater, date]);

  const movieOptions = useMemo(
    () => [{ value: '', label: 'Choose a movie' }, ...movies.map((movie) => ({ value: String(movie.id), label: movie.title }))],
    [movies],
  );

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const invalid = invalidShowtimeFields(draft);
    setFields(invalid);
    setError(null);
    setNotice(null);
    if (invalid.length > 0) return;
    setSaving(true);
    try {
      const created = (await api.createShowtime(showtimeBody(draft))).data;
      setNotice(`${created.seatCount} seats scheduled for ${localTime(created.localStartTime)} on ${created.localDate}.`);
      setDraft((current) => ({ ...current, time: EMPTY_SHOWTIME.time }));
      onCreated(created);
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) {
        const user = await onAuthLost();
        if (!user) return;
        setError('Please try again now that you are signed in.');
        return;
      }
      setFields(listedFields(caught));
      setError(describeAdminError(caught));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={submit} className="surface space-y-3 p-4 sm:p-5" data-testid="admin-create-showtime">
      <p className="eyebrow">Schedule a showtime</p>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Movie" error={fields.includes('movieId')}>
          <select
            value={draft.movieId}
            onChange={(event) => setDraft({ ...draft, movieId: event.target.value })}
            className={fields.includes('movieId') ? fieldClassError : fieldClass}
            data-testid="admin-showtime-movie"
          >
            {movieOptions.map((option) => (
              <option key={option.value || 'none'} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Screen" error={fields.includes('screenId')}>
          <select
            value={draft.screenId}
            onChange={(event) => setDraft({ ...draft, screenId: event.target.value })}
            className={fields.includes('screenId') ? fieldClassError : fieldClass}
            data-testid="admin-showtime-screen"
            disabled={theater.screens.length === 0}
          >
            {theater.screens.length === 0 ? (
              <option value="">Add a screen first</option>
            ) : (
              theater.screens.map((screen) => (
                <option key={screen.screenId} value={screen.screenId}>
                  {screen.name}
                </option>
              ))
            )}
          </select>
        </Field>
        <Field label="Language" error={fields.includes('languageCode')}>
          <select
            value={draft.languageCode}
            onChange={(event) => setDraft({ ...draft, languageCode: event.target.value })}
            className={fields.includes('languageCode') ? fieldClassError : fieldClass}
            data-testid="admin-showtime-language"
          >
            {languages.map((language) => (
              <option key={language.code} value={language.code}>
                {language.name}
              </option>
            ))}
          </select>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Date" error={fields.includes('startsAtLocal')}>
            <input
              type="date"
              value={draft.date}
              onChange={(event) => setDraft({ ...draft, date: event.target.value })}
              className={fields.includes('startsAtLocal') ? fieldClassError : fieldClass}
              style={{ colorScheme: 'dark' }}
              data-testid="admin-showtime-date"
            />
          </Field>
          <Field label="Start time" error={fields.includes('startsAtLocal')}>
            <input
              type="time"
              step={60}
              value={draft.time}
              onChange={(event) => setDraft({ ...draft, time: event.target.value.slice(0, 5) })}
              className={fields.includes('startsAtLocal') ? fieldClassError : fieldClass}
              style={{ colorScheme: 'dark' }}
              data-testid="admin-showtime-time"
            />
          </Field>
        </div>
        <Field label="Standard price" error={fields.includes('standardPrice')}>
          <input
            inputMode="decimal"
            value={draft.standardPrice}
            onChange={(event) => setDraft({ ...draft, standardPrice: event.target.value })}
            className={fields.includes('standardPrice') ? fieldClassError : fieldClass}
            data-testid="admin-showtime-standard"
          />
        </Field>
        <Field label="Premium price" error={fields.includes('premiumPrice')}>
          <input
            inputMode="decimal"
            value={draft.premiumPrice}
            onChange={(event) => setDraft({ ...draft, premiumPrice: event.target.value })}
            className={fields.includes('premiumPrice') ? fieldClassError : fieldClass}
            data-testid="admin-showtime-premium"
          />
        </Field>
      </div>
      {error && (
        <p role="alert" data-testid="admin-showtime-error" className="rounded-xl bg-danger-400/10 px-3.5 py-2.5 text-sm text-danger-400">
          {error}
        </p>
      )}
      {notice && (
        <p role="status" data-testid="admin-showtime-success" className="rounded-xl bg-success-400/10 px-3.5 py-2.5 text-sm text-success-400">
          {notice}
        </p>
      )}
      <Button
        type="submit"
        className="w-full"
        loading={saving}
        disabled={theater.screens.length === 0}
        icon={<Clapperboard className="size-4" aria-hidden />}
        data-testid="admin-showtime-submit"
      >
        Schedule showtime
      </Button>
    </form>
  );
}

function Field({ label, error, children }: { label: string; error: boolean; children: ReactNode }) {
  return (
    <label className="block text-sm font-medium text-mist-200">
      {label}
      {children}
      {error && <span className="mt-1 block text-xs text-danger-400">Check this field</span>}
    </label>
  );
}

function LayoutDots({ rows }: { rows: Array<{ rowLabel: string; seatCount: number; category: string }> }) {
  if (rows.length === 0) return null;
  return (
    <div className="space-y-1" aria-hidden>
      {rows.map((row) => (
        <div key={row.rowLabel} className="flex items-center gap-1.5">
          <span className="w-4 shrink-0 text-[0.65rem] text-mist-500">{row.rowLabel}</span>
          <div className="flex flex-wrap gap-0.5">
            {Array.from({ length: row.seatCount }, (_, index) => (
              <span
                key={index}
                className={`size-1.5 rounded-[2px] ${row.category === 'PREMIUM' ? 'bg-gold-300' : 'bg-violet-400/55'}`}
              />
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

function rowsFromSeats(seats: ScreenDetail['seats']) {
  const rows = new Map<string, { rowLabel: string; seatCount: number; category: ScreenDetail['seats'][number]['category'] }>();
  for (const seat of seats) {
    const row = rows.get(seat.rowLabel);
    if (row) row.seatCount += 1;
    else rows.set(seat.rowLabel, { rowLabel: seat.rowLabel, seatCount: 1, category: seat.category });
  }
  return [...rows.values()];
}
