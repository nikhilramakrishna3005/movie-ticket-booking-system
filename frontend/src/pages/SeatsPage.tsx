import { useCallback, useEffect, useMemo, useState } from 'react';
import { ArrowLeft, ArrowRight, Armchair, RefreshCw } from 'lucide-react';
import { AnimatePresence, motion } from 'motion/react';
import { Link, useNavigate, useParams } from 'react-router';
import { ApiError } from '../api/client.ts';
import type { SeatMapEntry } from '../api/types.ts';
import { useAuth } from '../state/AuthContext.tsx';
import { useCheckoutSessions } from '../state/CheckoutContext.tsx';
import { useAsync } from '../hooks/useAsync.ts';
import { MAX_SEATS, markUnavailable, reconcileSelection, selectedSeats, total } from '../features/seats/selection.ts';
import { describeError } from '../lib/errors.ts';
import { price, seatList } from '../lib/format.ts';
import { SeatLegend, SeatMap } from '../components/SeatMap.tsx';
import { ShowtimeSummary } from '../components/ShowtimeSummary.tsx';
import { Button, ButtonLink } from '../components/ui/Button.tsx';
import { StateMessage } from '../components/ui/StateMessage.tsx';

const REFRESH_MS = 15_000;

type Notice =
  | { kind: 'error' | 'warning' | 'info'; title: string; text: string }
  | { kind: 'existing-hold'; holdId: string; resumable: boolean };

export function SeatsPage() {
  const showtimeId = Number(useParams().showtimeId);
  const navigate = useNavigate();
  const { api, user, requestLogin } = useAuth();
  const { sessions, saveSession, dropSession } = useCheckoutSessions();
  const detail = useAsync(() => api.showtime(showtimeId), [api, showtimeId]);
  const seatMap = useAsync(() => api.seatMap(showtimeId), [api, showtimeId]);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [notice, setNotice] = useState<Notice | null>(null);
  const [holding, setHolding] = useState(false);
  const [conflicts, setConflicts] = useState<number[]>([]);

  const seats = useMemo(() => markUnavailable(seatMap.data?.seats ?? [], conflicts), [seatMap.data, conflicts]);
  const chosen = useMemo(() => selectedSeats(selected, seats), [selected, seats]);
  const closed = seatMap.data?.showtimeStatus === 'CANCELLED' || detail.data?.status === 'CANCELLED';

  // Availability is live: refresh periodically while the tab is visible.
  useEffect(() => {
    const timer = setInterval(() => {
      if (document.visibilityState === 'visible' && !holding) seatMap.reload();
    }, REFRESH_MS);
    return () => clearInterval(timer);
  }, [seatMap.reload, holding]);

  // A fresh seat map supersedes locally recorded conflicts; seats taken meanwhile are deselected.
  useEffect(() => {
    if (seatMap.status !== 'success') return;
    setConflicts([]);
    setSelected((current) => {
      const { kept, lost } = reconcileSelection(current, seatMap.data.seats);
      if (lost.length > 0) {
        setNotice({ kind: 'warning', title: 'Some seats were just taken', text: `${seatList(lost)} ${lost.length === 1 ? 'is' : 'are'} no longer available and ${lost.length === 1 ? 'was' : 'were'} removed from your selection.` });
      }
      return lost.length > 0 ? kept : current;
    });
  }, [seatMap.status, seatMap.data]);

  const toggle = useCallback((seat: SeatMapEntry) => {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(seat.seatId)) {
        next.delete(seat.seatId);
      } else if (next.size >= MAX_SEATS) {
        setNotice({ kind: 'info', title: `Up to ${MAX_SEATS} seats per booking`, text: 'Deselect a seat to choose another.' });
        return current;
      } else {
        next.add(seat.seatId);
      }
      setNotice((previous) => (previous?.kind === 'info' ? null : previous));
      return next;
    });
  }, []);

  const hold = async () => {
    if (!detail.data || chosen.length === 0) return;
    setNotice(null);
    let account = user ?? (await requestLogin());
    if (!account) return;
    if (account.role !== 'CUSTOMER') {
      setNotice({ kind: 'error', title: 'Customer account required', text: 'Theater admin accounts cannot buy tickets. Sign out and sign in as a customer.' });
      return;
    }
    setHolding(true);
    try {
      for (let attempt = 0; attempt < 2; attempt++) {
        try {
          const sentAt = Date.now();
          const response = await api.createHold(showtimeId, chosen.map((seat) => seat.seatId));
          const receivedAt = Date.now();
          const skewMs = response.serverDate ? response.serverDate.getTime() - (sentAt + receivedAt) / 2 : 0;
          saveSession({ hold: response.data, showtime: detail.data, seats: chosen, skewMs, ownerUsername: account.username });
          navigate(`/checkout/${response.data.holdId}`);
          return;
        } catch (error) {
          if (error instanceof ApiError && error.status === 401 && attempt === 0) {
            account = await requestLogin();
            if (!account) return;
            continue;
          }
          handleHoldError(error, account.username);
          return;
        }
      }
    } finally {
      setHolding(false);
    }
  };

  const handleHoldError = (error: unknown, username: string) => {
    if (!(error instanceof ApiError)) {
      setNotice({ kind: 'error', title: 'Seats could not be held', text: describeError(error) });
      return;
    }
    switch (error.code) {
      case 'SEATS_UNAVAILABLE': {
        const taken = Array.isArray(error.details?.seatIds) ? (error.details.seatIds as number[]) : [];
        setConflicts(taken);
        setSelected((current) => new Set([...current].filter((id) => !taken.includes(id))));
        setNotice({ kind: 'warning', title: 'Someone just took some of those seats', text: 'We removed them from your selection. Pick different seats and try again.' });
        seatMap.reload();
        return;
      }
      case 'ACTIVE_HOLD_EXISTS': {
        const holdId = typeof error.details?.holdId === 'string' ? error.details.holdId : null;
        const session = holdId ? sessions[holdId] : undefined;
        if (holdId) {
          setNotice({ kind: 'existing-hold', holdId, resumable: session?.ownerUsername === username });
        } else {
          setNotice({ kind: 'warning', title: 'You already have seats on hold', text: 'Your earlier hold for this showtime is still active. Try again in a moment.' });
        }
        return;
      }
      case 'SHOWTIME_CLOSED':
        setNotice({ kind: 'error', title: 'This showtime is closed', text: 'It has started or was cancelled, so seats can no longer be held.' });
        return;
      case 'SEATS_BUSY':
        setNotice({ kind: 'warning', title: 'Those seats are busy right now', text: 'Another booking is in progress for them. Try again in a moment.' });
        return;
      case 'FORBIDDEN':
        setNotice({ kind: 'error', title: 'Customer account required', text: 'Theater admin accounts cannot buy tickets.' });
        return;
      default:
        setNotice({ kind: 'error', title: 'Seats could not be held', text: describeError(error) });
    }
  };

  const releaseAndRetry = async (holdId: string) => {
    setHolding(true);
    try {
      await api.releaseHold(holdId);
      dropSession(holdId);
    } catch (error) {
      setHolding(false);
      const text = error instanceof ApiError && error.code === 'PAYMENT_IN_PROGRESS'
        ? 'A payment for your earlier hold is still being processed. Return to it to see the result.'
        : describeError(error);
      setNotice({ kind: 'error', title: 'Your earlier hold could not be released', text });
      return;
    }
    setHolding(false);
    await hold();
  };

  if (detail.status === 'error' || (seatMap.status === 'error' && !seatMap.data)) {
    const notFound = detail.error instanceof ApiError && detail.error.status === 404;
    return (
      <div className="mx-auto max-w-3xl px-4 py-20">
        <StateMessage block tone={notFound ? 'info' : 'error'} title={notFound ? 'Showtime not found' : 'Seats could not be loaded'} testId="seat-error"
          action={notFound ? <ButtonLink to="/">Browse movies</ButtonLink> : <Button onClick={() => { detail.reload(); seatMap.reload(); }}>Try again</Button>}>
          {notFound ? 'This showtime does not exist.' : describeError(detail.error ?? seatMap.error)}
        </StateMessage>
      </div>
    );
  }

  const amount = total(chosen);
  return (
    <div className="mx-auto max-w-7xl px-4 pt-6 pb-32 sm:px-6 lg:pb-16">
      <Link to={detail.data ? `/movies/${detail.data.movie.id}?city=${detail.data.theater.city.id}&date=${detail.data.localDate}` : '/'}
        className="inline-flex items-center gap-1.5 rounded text-sm text-mist-400 hover:text-mist-50">
        <ArrowLeft className="size-4" aria-hidden /> Showtimes
      </Link>
      <h1 className="mt-3 font-display text-3xl font-semibold tracking-tight text-mist-50 sm:text-4xl">Choose your seats</h1>

      <div className="mt-6 grid gap-6 lg:grid-cols-[1fr_22rem]">
        <section aria-label="Seat map" className="surface p-4 sm:p-8">
          {seatMap.data && detail.data ? (
            <>
              {closed && <div className="mb-6"><StateMessage tone="warning" title="This showtime was cancelled">Seats can no longer be held.</StateMessage></div>}
              <SeatMap seats={seats} selected={selected} onToggle={toggle} disabled={closed || holding} />
              <div className="mt-8 flex flex-col items-center gap-4">
                <SeatLegend prices={detail.data.prices} />
                <button type="button" onClick={seatMap.reload} className="inline-flex items-center gap-1.5 rounded text-xs text-mist-500 hover:text-mist-200">
                  <RefreshCw className={`size-3.5 ${seatMap.status === 'loading' ? 'animate-spin' : ''}`} aria-hidden />
                  Availability refreshes automatically
                </button>
              </div>
            </>
          ) : (
            <div className="space-y-3" aria-busy>
              <div className="skeleton mx-auto mb-8 h-2 w-80" />
              {[0, 1, 2, 3, 4, 5].map((row) => <div key={row} className="skeleton mx-auto h-8 w-full max-w-xl" />)}
            </div>
          )}
        </section>

        <aside className="space-y-4 lg:sticky lg:top-20 lg:self-start">
          <div className="surface p-5">
            {detail.data ? <ShowtimeSummary showtime={detail.data} /> : <div className="skeleton h-28" />}
          </div>
          <div className="surface p-5" data-testid="selected-seat-summary">
            <p className="eyebrow">Your seats</p>
            <AnimatePresence mode="popLayout" initial={false}>
              {chosen.length === 0 ? (
                <motion.p key="none" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="mt-3 flex items-center gap-2 text-sm text-mist-500">
                  <Armchair className="size-4" aria-hidden /> Tap a seat on the map to select it.
                </motion.p>
              ) : (
                <motion.ul key="list" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} className="mt-3 space-y-2 text-sm">
                  {chosen.map((seat) => (
                    <li key={seat.seatId} className="flex justify-between">
                      <span className="text-mist-50">{seat.rowLabel}{seat.seatNumber} <span className="text-mist-500">· {seat.category === 'PREMIUM' ? 'Premium' : 'Standard'}</span></span>
                      <span>{price(seat.price)}</span>
                    </li>
                  ))}
                </motion.ul>
              )}
            </AnimatePresence>
            <div className="mt-4 flex items-baseline justify-between border-t border-white/6 pt-4">
              <span className="text-sm text-mist-400">Total</span>
              <span data-testid="total-price" className="font-display text-2xl font-semibold text-mist-50">{price(amount)}</span>
            </div>
          </div>

          {notice && <NoticePanel notice={notice} busy={holding} onResume={(holdId) => navigate(`/checkout/${holdId}`)} onRelease={releaseAndRetry} />}

          <div className="fixed inset-x-0 bottom-0 z-20 border-t border-white/6 bg-ink-950/90 p-4 backdrop-blur-xl lg:static lg:border-0 lg:bg-transparent lg:p-0">
            <Button size="lg" className="w-full" onClick={hold} loading={holding} disabled={chosen.length === 0 || closed} data-testid="continue-button"
              icon={<ArrowRight className="size-4" aria-hidden />}>
              {chosen.length === 0 ? 'Select seats' : `Hold ${chosen.length} ${chosen.length === 1 ? 'seat' : 'seats'} · ${price(amount)}`}
            </Button>
            <p className="mt-2 hidden text-center text-xs text-mist-500 lg:block">
              {user ? 'Seats are held for 8 minutes while you pay.' : 'You will be asked to sign in. Seats are held for 8 minutes.'}
            </p>
          </div>
        </aside>
      </div>
    </div>
  );
}

function NoticePanel({ notice, busy, onResume, onRelease }: {
  notice: Notice;
  busy: boolean;
  onResume: (holdId: string) => void;
  onRelease: (holdId: string) => void;
}) {
  if (notice.kind === 'existing-hold') {
    return (
      <StateMessage tone="warning" title="You already have seats on hold" testId="seat-error"
        action={
          <>
            {notice.resumable && <Button size="sm" onClick={() => onResume(notice.holdId)}>Resume checkout</Button>}
            <Button size="sm" variant="secondary" loading={busy} onClick={() => onRelease(notice.holdId)} data-testid="release-existing-hold">
              Release it and hold these seats
            </Button>
          </>
        }>
        You have an active hold for this showtime. Resume it, or release it to hold your new selection.
      </StateMessage>
    );
  }
  return <StateMessage tone={notice.kind} title={notice.title} testId="seat-error">{notice.text}</StateMessage>;
}
