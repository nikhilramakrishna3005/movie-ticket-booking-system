// Mirrors the Spring Boot response records. Instants are ISO-8601 UTC strings; LocalDate is
// "yyyy-MM-dd" and LocalTime "HH:mm:ss" in the theater's time zone. BigDecimal arrives as a number.

export type Role = 'CUSTOMER' | 'THEATER_ADMIN';

export interface CurrentUser {
  id: number;
  username: string;
  displayName: string;
  role: Role;
}

export interface City {
  id: number;
  name: string;
  stateCode: string;
  timeZone: string;
}

export interface Language {
  code: string;
  name: string;
}

export interface Movie {
  id: number;
  title: string;
  durationMinutes: number;
  certification: string;
  releaseDate: string;
  originalLanguage: Language;
  genres: string[];
}

export interface ShowtimeSearchResult {
  showtimeId: number;
  movieId: number;
  movieTitle: string;
  certification: string;
  durationMinutes: number;
  language: Language;
  theaterId: number;
  theaterName: string;
  cityId: number;
  cityName: string;
  stateCode: string;
  screenId: number;
  screenName: string;
  startsAt: string;
  endsAt: string;
  localDate: string;
  localStartTime: string;
  timeZone: string;
}

export interface ShowtimePage {
  items: ShowtimeSearchResult[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface ShowtimeSearchParams {
  cityId?: number;
  date?: string;
  q?: string;
  language?: string;
  genre?: string;
  movieId?: number;
  page?: number;
  size?: number;
}

export type SeatCategory = 'STANDARD' | 'PREMIUM';

export interface PriceTier {
  category: SeatCategory;
  price: number;
  seatCount: number;
}

export interface ShowtimeDetail {
  showtimeId: number;
  status: 'SCHEDULED' | 'CANCELLED';
  startsAt: string;
  endsAt: string;
  localDate: string;
  localStartTime: string;
  localEndTime: string;
  timeZone: string;
  movie: Movie;
  language: Language;
  theater: { id: number; name: string; address: string; city: { id: number; name: string; stateCode: string } };
  screen: { id: number; name: string };
  prices: PriceTier[];
}

/** Effective status: a hold past its expiry is already reported as AVAILABLE by the server. */
export type SeatStatus = 'AVAILABLE' | 'HELD' | 'BOOKED';

export interface SeatMapEntry {
  seatId: number;
  rowLabel: string;
  seatNumber: number;
  category: SeatCategory;
  price: number;
  status: SeatStatus;
}

export interface SeatMap {
  showtimeId: number;
  showtimeStatus: 'SCHEDULED' | 'CANCELLED';
  startsAt: string;
  seats: SeatMapEntry[];
}

export interface Hold {
  holdId: string;
  showtimeId: number;
  seatIds: number[];
  status: 'ACTIVE' | 'CONVERTED' | 'RELEASED' | 'EXPIRED';
  expiresAt: string;
}

export type PaymentScenario = 'SUCCESS' | 'DECLINE' | 'TIMEOUT';
export type PaymentStatus = 'PENDING' | 'SUCCEEDED' | 'DECLINED' | 'UNKNOWN' | 'VOIDED';

export interface BookedSeat {
  seatId: number;
  rowLabel: string;
  seatNumber: number;
  category: SeatCategory;
  price: number;
}

export interface BookingConfirmation {
  bookingId: string;
  reference: string;
  status: 'CONFIRMED';
  paymentId: string;
  showtimeId: number;
  movieTitle: string;
  theaterName: string;
  screenName: string;
  city: string;
  startsAt: string;
  seats: BookedSeat[];
  totalAmount: number;
  confirmedAt: string;
}

/** One entry of GET /api/bookings; localDate and localStartTime are in timeZone. */
export interface BookingSummary {
  bookingId: string;
  reference: string;
  status: 'CONFIRMED';
  showtimeId: number;
  movieTitle: string;
  theaterName: string;
  screenName: string;
  city: string;
  startsAt: string;
  localDate: string;
  localStartTime: string;
  timeZone: string;
  seats: BookedSeat[];
  totalAmount: number;
  confirmedAt: string;
}

/** The signed-in customer's bookings, newest first. */
export interface BookingHistoryPage {
  items: BookingSummary[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface PaymentResponse {
  paymentId: string;
  holdId: string;
  status: PaymentStatus;
  amount: number;
  /** Present exactly when status is SUCCEEDED. */
  booking: BookingConfirmation | null;
}

/** The JSON body of every backend error response. */
export interface ApiErrorBody {
  code: string;
  message: string;
  details: Record<string, unknown> | null;
}
