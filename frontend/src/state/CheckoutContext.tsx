import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import type { BookingConfirmation, Hold, SeatMapEntry, ShowtimeDetail } from '../api/types.ts';

/**
 * Everything the checkout page needs about a hold. The API has no "get hold" endpoint, so this is
 * captured when the hold is created and lives in memory only; after a reload the customer starts
 * again from the seat map (their credentials are gone at that point too).
 */
export interface CheckoutSession {
  hold: Hold;
  showtime: ShowtimeDetail;
  seats: SeatMapEntry[];
  /** Server time minus client time when the hold was created. */
  skewMs: number;
  ownerUsername: string;
}

interface CheckoutContextValue {
  sessions: Record<string, CheckoutSession>;
  bookings: Record<string, BookingConfirmation>;
  saveSession: (session: CheckoutSession) => void;
  dropSession: (holdId: string) => void;
  saveBooking: (booking: BookingConfirmation) => void;
  clear: () => void;
}

const CheckoutContext = createContext<CheckoutContextValue | null>(null);

export function CheckoutProvider({ children }: { children: ReactNode }) {
  const [sessions, setSessions] = useState<Record<string, CheckoutSession>>({});
  const [bookings, setBookings] = useState<Record<string, BookingConfirmation>>({});

  const saveSession = useCallback((session: CheckoutSession) => {
    setSessions((current) => ({ ...current, [session.hold.holdId]: session }));
  }, []);
  const dropSession = useCallback((holdId: string) => {
    setSessions((current) => {
      const { [holdId]: _dropped, ...rest } = current;
      return rest;
    });
  }, []);
  const saveBooking = useCallback((booking: BookingConfirmation) => {
    setBookings((current) => ({ ...current, [booking.bookingId]: booking }));
  }, []);
  const clear = useCallback(() => {
    setSessions({});
    setBookings({});
  }, []);

  const value = useMemo(
    () => ({ sessions, bookings, saveSession, dropSession, saveBooking, clear }),
    [sessions, bookings, saveSession, dropSession, saveBooking, clear],
  );
  return <CheckoutContext.Provider value={value}>{children}</CheckoutContext.Provider>;
}

export function useCheckoutSessions(): CheckoutContextValue {
  const value = useContext(CheckoutContext);
  if (!value) throw new Error('useCheckoutSessions must be used inside CheckoutProvider');
  return value;
}
