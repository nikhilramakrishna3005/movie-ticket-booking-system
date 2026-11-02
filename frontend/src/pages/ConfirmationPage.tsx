import { CircleCheck } from 'lucide-react';
import { motion } from 'motion/react';
import { useParams } from 'react-router';
import { ApiError } from '../api/client.ts';
import type { BookingConfirmation, ShowtimeDetail } from '../api/types.ts';
import { useAuth } from '../state/AuthContext.tsx';
import { useCheckoutSessions } from '../state/CheckoutContext.tsx';
import { useAsync } from '../hooks/useAsync.ts';
import { instantIn, localTime, longDate } from '../lib/dates.ts';
import { describeError } from '../lib/errors.ts';
import { price, seatList } from '../lib/format.ts';
import { Poster } from '../components/Poster.tsx';
import { Button, ButtonLink } from '../components/ui/Button.tsx';
import { StateMessage } from '../components/ui/StateMessage.tsx';

export function ConfirmationPage() {
  const { bookingId = '' } = useParams();
  const { api, user, requestLogin } = useAuth();
  const { bookings } = useCheckoutSessions();
  const known = bookings[bookingId];

  const booking = useAsync<BookingConfirmation | null>(
    () => (known ? Promise.resolve(known) : user ? api.booking(bookingId) : Promise.resolve(null)),
    [api, bookingId, known, user],
  );
  const showtimeId = booking.data?.showtimeId;
  // The booking carries instants only; the showtime supplies the theater's zone and address.
  const showtime = useAsync<ShowtimeDetail | null>(
    () => (showtimeId ? api.showtime(showtimeId) : Promise.resolve(null)),
    [api, showtimeId],
  );

  if (!user && !known) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-20">
        <StateMessage block title="Sign in to view this ticket" action={<Button onClick={() => void requestLogin()}>Sign in</Button>}>
          Tickets are only shown to the customer who booked them.
        </StateMessage>
      </div>
    );
  }
  if (booking.status === 'error') {
    const notFound = booking.error instanceof ApiError && booking.error.status === 404;
    return (
      <div className="mx-auto max-w-3xl px-4 py-20">
        <StateMessage block tone={notFound ? 'info' : 'error'} title={notFound ? 'Booking not found' : 'This ticket could not be loaded'}
          action={notFound ? <ButtonLink to="/">Browse movies</ButtonLink> : <Button onClick={booking.reload}>Try again</Button>}>
          {notFound ? 'No booking with this reference exists for your account.' : describeError(booking.error)}
        </StateMessage>
      </div>
    );
  }
  if (!booking.data) {
    return <div className="mx-auto max-w-3xl px-4 py-20"><div className="skeleton h-96" /></div>;
  }
  return <Ticket booking={booking.data} showtime={showtime.data ?? null} />;
}

function Ticket({ booking, showtime }: { booking: BookingConfirmation; showtime: ShowtimeDetail | null }) {
  const when = showtime
    ? { date: longDate(showtime.localDate), time: localTime(showtime.localStartTime) }
    : { date: instantIn(booking.startsAt, 'UTC'), time: '' };
  const field = (label: string, value: string, testId?: string) => (
    <div>
      <dt className="eyebrow">{label}</dt>
      <dd data-testid={testId} className="mt-1 text-sm font-medium text-mist-50">{value}</dd>
    </div>
  );

  return (
    <div className="mx-auto max-w-4xl px-4 py-10 sm:px-6 sm:py-14">
      <motion.div initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.3 }} className="text-center">
        <span className="inline-grid size-14 place-items-center rounded-full bg-success-400/12">
          <CircleCheck className="size-7 text-success-400" aria-hidden />
        </span>
        <h1 className="mt-4 font-display text-3xl font-semibold tracking-tight text-mist-50 sm:text-4xl">You're all set</h1>
        <p className="mt-2 text-mist-400">Your booking is confirmed. Show the reference below at the box office.</p>
      </motion.div>

      <motion.article
        data-testid="booking-confirmation"
        data-booking-id={booking.bookingId}
        initial={{ opacity: 0, scale: 0.97 }}
        animate={{ opacity: 1, scale: 1 }}
        transition={{ duration: 0.35, delay: 0.05 }}
        className="relative mt-10 flex flex-col overflow-hidden rounded-3xl bg-ink-850 shadow-2xl ring-1 ring-white/8 md:flex-row"
        aria-label={`Ticket for ${booking.movieTitle}`}
      >
        <div className="relative h-32 shrink-0 md:h-auto md:w-48">
          <Poster title={booking.movieTitle} showTitle={false} fill />
        </div>

        <div className="flex-1 p-6 sm:p-8">
          <p className="eyebrow text-violet-300">Admit {booking.seats.length}</p>
          <h2 data-testid="ticket-movie" className="mt-2 font-display text-3xl leading-tight font-semibold text-mist-50">{booking.movieTitle}</h2>
          <dl className="mt-6 grid grid-cols-2 gap-x-6 gap-y-5 sm:grid-cols-3">
            {field('Date', when.date, 'ticket-date')}
            {field('Time', when.time || '—', 'ticket-showtime')}
            {field('Screen', booking.screenName)}
            <div className="col-span-2">
              <dt className="eyebrow">Theater</dt>
              <dd data-testid="ticket-theater" className="mt-1 text-sm font-medium text-mist-50">
                {booking.theaterName}
                <span className="block text-xs font-normal text-mist-400">{showtime?.theater.address ?? booking.city}</span>
              </dd>
            </div>
            {field('Seats', seatList(booking.seats), 'ticket-seats')}
          </dl>
        </div>

        {/* The stub: a dashed perforation with notches cut into the ticket edge on both ends. */}
        <div className="relative flex flex-col justify-between gap-6 border-t-2 border-dashed border-white/10 p-6 sm:p-8 md:w-60 md:border-t-0 md:border-l-2">
          <span className="absolute -top-3.5 -left-3.5 size-6 rounded-full bg-ink-950 md:-top-3.5 md:-left-3.5" aria-hidden />
          <span className="absolute -top-3.5 -right-3.5 size-6 rounded-full bg-ink-950 md:top-auto md:right-auto md:-bottom-3.5 md:-left-3.5" aria-hidden />
          <div>
            <p className="eyebrow">Booking reference</p>
            <p data-testid="booking-reference" className="mt-2 font-mono text-2xl font-semibold tracking-wider break-all text-violet-300">{booking.reference}</p>
          </div>
          <div className="space-y-1 text-sm">
            <p className="flex justify-between"><span className="text-mist-400">Status</span><span className="font-medium text-success-400">Confirmed</span></p>
            <p className="flex justify-between"><span className="text-mist-400">Total paid</span><span data-testid="ticket-total" className="font-semibold text-mist-50">{price(booking.totalAmount)}</span></p>
          </div>
        </div>
      </motion.article>

      <div className="mt-8 flex justify-center">
        <ButtonLink to="/" variant="secondary">Book another movie</ButtonLink>
      </div>
    </div>
  );
}
