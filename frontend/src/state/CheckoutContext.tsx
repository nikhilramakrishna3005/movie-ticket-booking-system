import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';
import type { BookingConfirmation, Hold, SeatMapEntry, ShowtimeDetail } from '../api/types.ts';

/**
 * Everything the checkout page needs about a hold. Captured when the hold is created and kept in
 * memory only. After a reload the password is gone too (credentials are never persisted), so the
 * customer must sign in again; GET /api/holds/{id} then rebuilds this session without extending
 * the hold.
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
