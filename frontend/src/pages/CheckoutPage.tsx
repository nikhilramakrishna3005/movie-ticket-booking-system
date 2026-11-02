import { useEffect, useRef, useState } from 'react';
import { CircleCheck, CircleX, CreditCard, Hourglass, LoaderCircle, LockKeyhole, ShieldCheck } from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { useNavigate, useParams } from 'react-router';
import { ApiError } from '../api/client.ts';
import type { PaymentScenario } from '../api/types.ts';
import { useAuth } from '../state/AuthContext.tsx';
import { useCheckoutSessions, type CheckoutSession } from '../state/CheckoutContext.tsx';
import { useCheckout } from '../features/checkout/useCheckout.ts';
import { isTerminal } from '../features/checkout/paymentFlow.ts';
import { total } from '../features/seats/selection.ts';
import { remainingMs } from '../lib/dates.ts';
import { describeError } from '../lib/errors.ts';
import { price } from '../lib/format.ts';
import { useNow } from '../hooks/useNow.ts';
import { HoldCountdown } from '../components/HoldCountdown.tsx';
import { ShowtimeSummary } from '../components/ShowtimeSummary.tsx';
import { Button, ButtonLink } from '../components/ui/Button.tsx';
import { StateMessage } from '../components/ui/StateMessage.tsx';

/** Mirrors booking.payment-min-remaining: the server refuses to start a payment with less time left. */
const MIN_REMAINING_MS = 60_000;

const SCENARIOS: Array<{ value: PaymentScenario; title: string; text: string; icon: typeof CircleCheck }> = [
  { value: 'SUCCESS', title: 'Card approved', text: 'The simulated provider captures the payment.', icon: CircleCheck },
  { value: 'DECLINE', title: 'Card declined', text: 'The provider declines. No booking is made; you can retry.', icon: CircleX },
  {
    value: 'TIMEOUT',
    title: 'Provider timeout',
    text: 'The charge goes through but the reply is lost. Cinéva confirms the outcome with the provider.',
    icon: Hourglass,
  },
];

export function CheckoutPage() {
  const { holdId = '' } = useParams();
  const { user, requestLogin } = useAuth();
  const { sessions } = useCheckoutSessions();
  const session = sessions[holdId];

  if (!session || (user && user.username !== session.ownerUsername)) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-20">
        <StateMessage block title="This checkout is no longer open" testId="checkout-missing" action={<ButtonLink to="/">Browse showtimes</ButtonLink>}>
          Checkout details live only in this tab's memory, so they are gone after a reload or sign-out. Any seats you held are
          released automatically when the hold expires.
        </StateMessage>
      </div>
    );
  }
  if (!user) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-20">
        <StateMessage block title="Sign in to finish checkout" action={<Button onClick={() => void requestLogin()}>Sign in</Button>}>
          Your session ended. Sign in again as {session.ownerUsername} to continue.
        </StateMessage>
      </div>
    );
  }
  return <Checkout session={session} />;
}

function Checkout({ session }: { session: CheckoutSession }) {
  const navigate = useNavigate();
  const { api, requestLogin } = useAuth();
  const { dropSession, saveBooking } = useCheckoutSessions();
  const checkout = useCheckout(session);
  const { outcome } = checkout;
  const now = useNow(1000);
  const left = remainingMs(session.hold.expiresAt, now, session.skewMs);
  const totalMs = useRef(Math.max(left, 1)).current;
  const [releasing, setReleasing] = useState(false);
  const [releaseError, setReleaseError] = useState<string | null>(null);

  const pending = outcome?.kind === 'pending';
  const expiredLocally = left === 0 && !pending && outcome?.kind !== 'succeeded';
  const terminal = (outcome !== null && isTerminal(outcome)) || expiredLocally;
  const tooLate = !terminal && left > 0 && left < MIN_REMAINING_MS && !pending;
  const amount = total(session.seats);
  const seatsUrl = `/showtimes/${session.showtime.showtimeId}/seats`;

  useEffect(() => {
    if (outcome?.kind !== 'succeeded') return;
    saveBooking(outcome.booking);
    dropSession(session.hold.holdId);
    navigate(`/bookings/${outcome.booking.bookingId}`, { replace: true });
  }, [outcome, saveBooking, dropSession, session.hold.holdId, navigate]);

  const release = async () => {
    setReleasing(true);
    setReleaseError(null);
    try {
      await api.releaseHold(session.hold.holdId);
      dropSession(session.hold.holdId);
      navigate(seatsUrl);
    } catch (error) {
      setReleasing(false);
      if (error instanceof ApiError && error.code === 'PAYMENT_IN_PROGRESS') {
        setReleaseError('A payment for this hold is still being processed, so it cannot be released yet.');
      } else if (error instanceof ApiError && error.code === 'HOLD_CONVERTED') {
        setReleaseError('These seats were already booked with this hold.');
      } else if (error instanceof ApiError && error.status === 401) {
        void requestLogin();
      } else {
        setReleaseError(describeError(error));
      }
    }
  };

  const payLabel = checkout.attempt && outcome?.kind === 'ambiguous'
    ? 'Retry the same payment'
    : outcome?.kind === 'declined' ? `Try again · ${price(amount)}` : `Pay ${price(amount)}`;

  return (
    <div data-testid="checkout-page" className="mx-auto max-w-6xl px-4 pt-8 pb-20 sm:px-6">
      <p className="eyebrow">Checkout</p>
      <h1 className="mt-2 font-display text-3xl font-semibold tracking-tight text-mist-50 sm:text-4xl">Confirm and pay</h1>

      <div className="mt-8 grid gap-6 lg:grid-cols-[1fr_24rem]">
        <section className="min-w-0 space-y-6">
          <div className="surface flex flex-wrap items-center justify-between gap-4 p-5">
            <HoldCountdown remainingMs={left} totalMs={totalMs} />
            <p className="max-w-xs text-sm text-mist-400">
              Your seats are reserved for you until the timer runs out. If it does, they are released and no payment is taken.
            </p>
          </div>

          <fieldset className="surface p-5" disabled={!!checkout.attempt || terminal || pending || checkout.submitting}>
            <legend className="sr-only">Simulated payment outcome</legend>
            <div className="mb-4 flex items-center justify-between gap-3">
              <p className="flex items-center gap-2 font-semibold text-mist-50">
                <CreditCard className="size-5 text-violet-300" aria-hidden /> Simulated payment
              </p>
              <span className="rounded-full bg-white/6 px-2.5 py-1 text-[0.7rem] text-mist-400">Demo provider · no real money</span>
            </div>
            <div className="grid gap-3 sm:grid-cols-3">
              {SCENARIOS.map(({ value, title, text, icon: Icon }) => {
                const active = checkout.scenario === value;
                return (
                  <label
                    key={value}
                    className={`relative flex cursor-pointer flex-col rounded-2xl border p-4 transition-colors has-[:disabled]:cursor-not-allowed has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-violet-400 ${
                      active ? 'border-violet-400/60 bg-violet-500/12' : 'border-white/8 bg-ink-850 hover:border-white/15'
                    } ${!active && checkout.attempt ? 'opacity-50' : ''}`}
                  >
                    <input
                      type="radio"
                      name="scenario"
                      value={value}
                      checked={active}
                      onChange={() => checkout.setScenario(value)}
                      className="sr-only"
                      data-testid={`scenario-${value}`}
                    />
                    <Icon className={`size-5 ${active ? 'text-violet-300' : 'text-mist-500'}`} aria-hidden />
                    <span className="mt-3 text-sm font-semibold text-mist-50">{title}</span>
                    <span className="mt-1 text-xs leading-relaxed text-mist-400">{text}</span>
                  </label>
                );
              })}
            </div>
            {checkout.attempt && outcome?.kind === 'ambiguous' && (
              <p className="mt-3 text-xs text-mist-500">
                The outcome is locked to this attempt so a retry can never charge you twice.
              </p>
            )}
          </fieldset>

          <div aria-live="polite" data-testid="payment-status" data-status={outcome?.kind ?? (expiredLocally ? 'expired' : 'idle')}>
            <AnimatePresence mode="wait">
              <motion.div key={outcome?.kind ?? (expiredLocally ? 'expired' : 'idle')} initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }} transition={{ duration: 0.2 }}>
                <OutcomePanel checkout={checkout} expiredLocally={expiredLocally} tooLate={tooLate} seatsUrl={seatsUrl} onSignIn={() => void requestLogin()} />
              </motion.div>
            </AnimatePresence>
          </div>
        </section>

        <aside className="min-w-0 space-y-4 lg:sticky lg:top-20 lg:self-start">
          <div className="surface p-5">
            <ShowtimeSummary showtime={session.showtime} compact />
            <ul className="mt-5 space-y-2 border-t border-white/6 pt-4 text-sm">
              {session.seats.map((seat) => (
                <li key={seat.seatId} className="flex justify-between">
                  <span className="text-mist-50">Seat {seat.rowLabel}{seat.seatNumber} <span className="text-mist-500">· {seat.category === 'PREMIUM' ? 'Premium' : 'Standard'}</span></span>
                  <span>{price(seat.price)}</span>
                </li>
              ))}
            </ul>
            <div className="mt-4 flex items-baseline justify-between border-t border-white/6 pt-4">
              <span className="text-sm text-mist-400">Total</span>
              <span className="font-display text-2xl font-semibold text-mist-50">{price(amount)}</span>
            </div>
          </div>

          {!terminal && (
            <Button size="lg" className="w-full" data-testid="pay-button" onClick={checkout.pay} loading={checkout.submitting}
              disabled={pending || tooLate} icon={<LockKeyhole className="size-4" aria-hidden />}>
              {payLabel}
            </Button>
          )}
          {!terminal && !pending && (
            <Button variant="ghost" className="w-full" onClick={release} loading={releasing} disabled={checkout.submitting} data-testid="release-hold-button">
              Release seats and go back
            </Button>
          )}
          {releaseError && <StateMessage tone="error" title="Seats were not released">{releaseError}</StateMessage>}
          <p className="flex items-start gap-2 px-1 text-xs leading-relaxed text-mist-500">
            <ShieldCheck className="size-4 shrink-0 text-violet-300" aria-hidden />
            Every payment attempt has a unique key, and retries reuse it, so the provider never charges an attempt twice.
          </p>
        </aside>
      </div>
    </div>
  );
}

function OutcomePanel({ checkout, expiredLocally, tooLate, seatsUrl, onSignIn }: {
  checkout: ReturnType<typeof useCheckout>;
  expiredLocally: boolean;
  tooLate: boolean;
  seatsUrl: string;
  onSignIn: () => void;
}) {
  const { outcome } = checkout;
  const again = <ButtonLink to={seatsUrl} size="sm">Choose seats again</ButtonLink>;
  if (!outcome) {
    if (expiredLocally) {
      return <StateMessage tone="warning" title="Your hold expired" testId="hold-expired" action={again}>The seats were released and no payment was taken.</StateMessage>;
    }
    if (tooLate) {
      return <StateMessage tone="warning" title="Not enough time left to pay" testId="hold-expired" action={again}>A payment needs at least a minute on the hold. Choose your seats again to start a fresh hold.</StateMessage>;
    }
    return null;
  }
  switch (outcome.kind) {
    case 'succeeded':
      return <StateMessage tone="success" title="Payment confirmed">Opening your ticket…</StateMessage>;
    case 'pending':
      return (
        <div data-testid="payment-pending" className="surface flex gap-4 p-5" role="status">
          {checkout.polling ? <LoaderCircle className="size-6 shrink-0 animate-spin text-violet-300" aria-hidden /> : <Hourglass className="size-6 shrink-0 text-warning-400" aria-hidden />}
          <div>
            <p className="font-semibold text-mist-50">{checkout.polling ? 'Confirming your payment…' : 'Still waiting for the provider'}</p>
            <p className="mt-1 text-sm text-mist-400">
              The payment provider has not confirmed the result yet. We are checking with it directly. Do not pay again; this
              attempt can never be charged twice.
            </p>
            {!checkout.polling && <Button size="sm" variant="secondary" className="mt-3" onClick={checkout.checkAgain} data-testid="check-payment-button">Check again</Button>}
          </div>
        </div>
      );
    case 'declined':
      return <StateMessage tone="error" title="Payment declined" testId="payment-declined">{outcome.message} Your seats are still held; choose an outcome and try again.</StateMessage>;
    case 'ambiguous':
      return <StateMessage tone="warning" title="We did not hear back" testId="payment-error">{outcome.message} Retrying sends the same payment again, so you cannot be charged twice.</StateMessage>;
    case 'unauthenticated':
      return <StateMessage tone="warning" title="Signed out" testId="payment-error" action={<Button size="sm" onClick={onSignIn}>Sign in</Button>}>{outcome.message}</StateMessage>;
    case 'expired':
      return <StateMessage tone="warning" title="Your hold expired" testId="hold-expired" action={again}>{outcome.message}</StateMessage>;
    case 'voided':
      return <StateMessage tone="warning" title="Payment voided" testId="payment-voided" action={again}>{outcome.message}</StateMessage>;
    case 'blocked':
      return <StateMessage tone="error" title="Payment cannot continue" testId="payment-error" action={again}>{outcome.message}</StateMessage>;
  }
}
