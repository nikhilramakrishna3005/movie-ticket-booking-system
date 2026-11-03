import { useState, type ReactNode } from 'react';
import { ArrowRight, CalendarDays, Clapperboard, LogIn, MapPin, Ticket } from 'lucide-react';
import { motion } from 'motion/react';
import { Link } from 'react-router';
import type { BookingHistoryPage, BookingSummary } from '../api/types.ts';
import { useAuth } from '../state/AuthContext.tsx';
import { useAsync } from '../hooks/useAsync.ts';
import { useNow } from '../hooks/useNow.ts';
import { HISTORY_PAGE_SIZE, hasMorePages, isUpcoming, mergePages } from '../features/tickets/history.ts';
import { localTime, ticketDate } from '../lib/dates.ts';
import { describeError } from '../lib/errors.ts';
import { price, seatList } from '../lib/format.ts';
import { Poster } from '../components/Poster.tsx';
import { Button, ButtonLink } from '../components/ui/Button.tsx';
import { StateMessage } from '../components/ui/StateMessage.tsx';

export function MyTicketsPage() {
  const { user, requestLogin } = useAuth();

  if (!user) {
    return (
      <Shell>
        <StateMessage block title="Sign in to see your tickets" testId="tickets-signed-out"
          action={<Button onClick={() => void requestLogin()} icon={<LogIn className="size-4" aria-hidden />} data-testid="tickets-sign-in">Sign in</Button>}>
          Your bookings are only shown to you. Cinéva keeps your sign-in in memory only, so reloading the page or
          signing out always asks you to sign in again.
        </StateMessage>
      </Shell>
    );
  }
  if (user.role !== 'CUSTOMER') {
    return (
      <Shell>
        <StateMessage block title="Customer account required" testId="tickets-not-customer">
          Theater admin accounts do not buy tickets. Sign out and sign in as a customer to see bookings.
        </StateMessage>
      </Shell>
    );
  }
  // Keyed by user so one customer's tickets are never shown while another's are loading.
  return <TicketHistory key={user.id} />;
}

function Shell({ children, subtitle }: { children: ReactNode; subtitle?: string }) {
  return (
    <div className="mx-auto max-w-5xl px-4 py-10 sm:px-6 sm:py-14">
      <p className="eyebrow flex items-center gap-2">
        <Ticket className="size-3.5 text-violet-300" aria-hidden />
        Your bookings
      </p>
      <h1 className="mt-3 font-display text-4xl font-semibold tracking-tight text-mist-50 sm:text-5xl">My tickets</h1>
      {subtitle && <p className="mt-2 text-mist-400" data-testid="tickets-summary">{subtitle}</p>}
      <div className="mt-8">{children}</div>
    </div>
  );
}

function TicketHistory() {
  const { api } = useAuth();
  const first = useAsync(() => api.bookings({ page: 0, size: HISTORY_PAGE_SIZE }), [api]);
  const [more, setMore] = useState<BookingHistoryPage[]>([]);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreError, setMoreError] = useState<unknown>(null);
  const now = useNow(60_000);

  const pages = first.data ? [first.data, ...more] : [];
  const tickets = mergePages(pages);
  const total = first.data?.totalItems ?? 0;

  const loadMore = async () => {
    const last = pages.at(-1);
    if (!last) return;
    setLoadingMore(true);
    setMoreError(null);
    try {
      const next = await api.bookings({ page: last.page + 1, size: HISTORY_PAGE_SIZE });
      setMore((current) => [...current, next]);
    } catch (error) {
      setMoreError(error);
    } finally {
      setLoadingMore(false);
    }
  };

  if (first.status === 'error') {
    return (
      <Shell>
        <StateMessage block tone="error" title="Your tickets could not be loaded" testId="tickets-error"
          action={<Button onClick={first.reload} data-testid="tickets-retry">Try again</Button>}>
          {describeError(first.error)}
        </StateMessage>
      </Shell>
    );
  }
  if (!first.data) {
    return (
      <Shell>
        <div className="grid gap-4" aria-busy data-testid="tickets-loading">
          <span className="sr-only">Loading your tickets</span>
          {[0, 1, 2].map((key) => <div key={key} className="skeleton h-40" />)}
        </div>
      </Shell>
    );
  }
  if (tickets.length === 0) {
    return (
      <Shell>
        <StateMessage block title="No tickets yet" testId="tickets-empty" action={<ButtonLink to="/">Browse movies</ButtonLink>}>
          When you book seats, your tickets appear here with their booking reference.
        </StateMessage>
      </Shell>
    );
  }

  return (
    <Shell subtitle={`${total} ${total === 1 ? 'booking' : 'bookings'}, most recent first.`}>
      <ol className="grid gap-4" aria-label="Your tickets" data-testid="ticket-list">
        {tickets.map((booking, index) => (
          <TicketCard key={booking.bookingId} booking={booking} upcoming={isUpcoming(booking, now)} index={index} />
        ))}
      </ol>
      <div className="mt-8 flex flex-col items-center gap-3" aria-live="polite">
        {moreError !== null && (
          <StateMessage tone="error" title="More tickets could not be loaded" testId="tickets-more-error">
            {describeError(moreError)}
          </StateMessage>
        )}
        {hasMorePages(pages) ? (
          <Button variant="secondary" onClick={() => void loadMore()} loading={loadingMore} data-testid="tickets-load-more">
            Show older tickets
          </Button>
        ) : (
          tickets.length > HISTORY_PAGE_SIZE && <p className="text-sm text-mist-500">That is every ticket on your account.</p>
        )}
      </div>
    </Shell>
  );
}

function TicketCard({ booking, upcoming, index }: { booking: BookingSummary; upcoming: boolean; index: number }) {
  const when = `${ticketDate(booking.localDate)} · ${localTime(booking.localStartTime)}`;
  return (
    <motion.li
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.25, delay: Math.min(index, 8) * 0.03 }}
      data-testid="ticket-card"
      data-booking-id={booking.bookingId}
      data-upcoming={upcoming}
      className={`group relative flex flex-col overflow-hidden rounded-3xl bg-ink-850 shadow-card ring-1 ring-white/8 transition-shadow focus-within:ring-violet-400/60 hover:ring-violet-400/40 sm:flex-row ${upcoming ? '' : 'opacity-80'}`}
    >
      <div className="relative hidden w-32 shrink-0 sm:block">
        <Poster title={booking.movieTitle} showTitle={false} fill />
      </div>

      <div className="min-w-0 flex-1 p-5 sm:p-6">
        <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-[0.7rem] font-semibold tracking-wide uppercase ${upcoming ? 'bg-violet-500/15 text-violet-300' : 'bg-white/6 text-mist-400'}`}
          data-testid="ticket-card-timing">
          {upcoming ? 'Upcoming' : 'Past'}
        </span>
        <h2 className="mt-2 font-display text-2xl leading-tight font-semibold text-mist-50" data-testid="ticket-card-movie">
          {booking.movieTitle}
        </h2>
        <dl className="mt-3 space-y-1.5 text-sm text-mist-400">
          <div className="flex items-center gap-2">
            <dt className="sr-only">Showtime</dt>
            <CalendarDays className="size-4 shrink-0 text-violet-300" aria-hidden />
            <dd data-testid="ticket-card-when" className="text-mist-200">{when}</dd>
          </div>
          <div className="flex items-center gap-2">
            <dt className="sr-only">Theater</dt>
            <MapPin className="size-4 shrink-0 text-violet-300" aria-hidden />
            <dd data-testid="ticket-card-theater" className="truncate">{booking.theaterName}, {booking.city}</dd>
          </div>
          <div className="flex items-center gap-2">
            <dt className="sr-only">Screen and seats</dt>
            <Clapperboard className="size-4 shrink-0 text-violet-300" aria-hidden />
            <dd>
              {booking.screenName} · <span data-testid="ticket-card-seats" className="text-mist-200">{seatList(booking.seats)}</span>
            </dd>
          </div>
        </dl>
      </div>

      {/* The stub, perforated like the ticket on the confirmation page. */}
      <div className="relative flex flex-row items-end justify-between gap-4 border-t-2 border-dashed border-white/10 p-5 sm:w-56 sm:flex-col sm:items-stretch sm:border-t-0 sm:border-l-2 sm:p-6">
        <span className="absolute -top-3.5 -left-3.5 size-6 rounded-full bg-ink-950 sm:-top-3.5 sm:-left-3.5" aria-hidden />
        <span className="absolute -top-3.5 -right-3.5 size-6 rounded-full bg-ink-950 sm:top-auto sm:right-auto sm:-bottom-3.5 sm:-left-3.5" aria-hidden />
        <div>
          <p className="eyebrow">Reference</p>
          <p data-testid="ticket-card-reference" className="mt-1 font-mono text-lg font-semibold tracking-wider text-violet-300">{booking.reference}</p>
          <p className="mt-1 text-sm text-mist-400">
            <span className="sr-only">Total paid </span>
            <span data-testid="ticket-card-total" className="font-semibold text-mist-50">{price(booking.totalAmount)}</span>
            {' · '}Confirmed
          </p>
        </div>
        <Link
          to={`/bookings/${booking.bookingId}`}
          data-testid="ticket-card-link"
          aria-label={`View ticket ${booking.reference} for ${booking.movieTitle}, ${when}`}
          className="inline-flex items-center gap-1.5 self-end rounded text-sm font-medium text-violet-300 hover:text-violet-400 sm:self-start"
        >
          View ticket <ArrowRight className="size-4" aria-hidden />
        </Link>
      </div>
    </motion.li>
  );
}
