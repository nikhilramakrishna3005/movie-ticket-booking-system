import type {
  ApiErrorBody,
  BookingConfirmation,
  City,
  CurrentUser,
  Hold,
  Movie,
  PaymentResponse,
  PaymentScenario,
  SeatMap,
  ShowtimeDetail,
  ShowtimePage,
  ShowtimeSearchParams,
} from './types.ts';

export interface Credentials {
  username: string;
  password: string;
}

/** Status 0 means the request never got an HTTP response (network failure). */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details: Record<string, unknown> | null;

  constructor(status: number, code: string, message: string, details: Record<string, unknown> | null = null) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details;
  }

  get isNetworkError(): boolean {
    return this.status === 0;
  }
}

export interface ApiResponse<T> {
  status: number;
  data: T;
  /** The server's Date header, used to correct hold countdowns for client clock skew. */
  serverDate: Date | null;
}

export interface ApiClientOptions {
  fetch?: typeof fetch;
  getCredentials: () => Credentials | null;
  /** Delays between attempts for retryable requests; its length is the number of retries. */
  retryDelaysMs?: readonly number[];
  sleep?: (ms: number) => Promise<void>;
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'DELETE';
  body?: unknown;
  headers?: Record<string, string>;
  credentials?: Credentials | null;
  /**
   * Whether a network failure or gateway error may be retried by resending the identical
   * request. True only for requests the server treats idempotently: reads, deletes, and payments
   * carrying an Idempotency-Key (the server replays the recorded outcome for a repeated key).
   */
  retryable: boolean;
}

const RETRYABLE_STATUSES = new Set([502, 503, 504]);

export function encodeBasic({ username, password }: Credentials): string {
  const bytes = new TextEncoder().encode(`${username}:${password}`);
  let binary = '';
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return `Basic ${btoa(binary)}`;
}

function toQuery(params: ShowtimeSearchParams): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') {
      query.set(key, String(value));
    }
  }
  const text = query.toString();
  return text ? `?${text}` : '';
}

async function readError(response: Response): Promise<ApiError> {
  try {
    const body = (await response.json()) as Partial<ApiErrorBody>;
    if (typeof body.code === 'string') {
      return new ApiError(response.status, body.code, body.message ?? body.code, body.details ?? null);
    }
  } catch {
    // Not a JSON error body (e.g. an HTML page from the proxy); fall through to a generic error.
  }
  return new ApiError(response.status, `HTTP_${response.status}`, `Request failed with status ${response.status}`);
}

export function createApiClient(options: ApiClientOptions) {
  const fetchImpl = options.fetch ?? ((input, init) => fetch(input, init));
  const retryDelays = options.retryDelaysMs ?? [400, 1200];
  const sleep = options.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));

  async function request<T>(path: string, opts: RequestOptions): Promise<ApiResponse<T>> {
    const headers: Record<string, string> = {
      Accept: 'application/json',
      // Suppresses the WWW-Authenticate challenge so the browser never shows its native login dialog.
      'X-Requested-With': 'XMLHttpRequest',
      ...opts.headers,
    };
    const credentials = opts.credentials === undefined ? options.getCredentials() : opts.credentials;
    if (credentials) {
      headers.Authorization = encodeBasic(credentials);
    }
    let body: string | undefined;
    if (opts.body !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(opts.body);
    }
    const init: RequestInit = { method: opts.method ?? 'GET', headers, body, credentials: 'omit' };

    const maxAttempts = opts.retryable ? retryDelays.length + 1 : 1;
    let lastError: ApiError | null = null;
    for (let attempt = 0; attempt < maxAttempts; attempt++) {
      if (attempt > 0) {
        await sleep(retryDelays[attempt - 1] ?? 0);
      }
      let response: Response;
      try {
        response = await fetchImpl(path, init);
      } catch {
        lastError = new ApiError(0, 'NETWORK_ERROR', 'Could not reach the server');
        continue;
      }
      if (RETRYABLE_STATUSES.has(response.status)) {
        lastError = await readError(response);
        continue;
      }
      if (!response.ok) {
        throw await readError(response);
      }
      const dateHeader = response.headers.get('Date');
      const serverDate = dateHeader ? new Date(dateHeader) : null;
      const data = (response.status === 204 ? undefined : await response.json()) as T;
      return {
        status: response.status,
        data,
        serverDate: serverDate && !Number.isNaN(serverDate.getTime()) ? serverDate : null,
      };
    }
    throw lastError ?? new ApiError(0, 'NETWORK_ERROR', 'Could not reach the server');
  }

  const get = async <T>(path: string): Promise<T> => (await request<T>(path, { retryable: true })).data;

  return {
    /** Validates credentials without storing them; the caller decides whether to keep them. */
    login: async (credentials: Credentials): Promise<CurrentUser> =>
      (await request<CurrentUser>('/api/auth/me', { credentials, retryable: true })).data,
    cities: () => get<City[]>('/api/cities'),
    movies: () => get<Movie[]>('/api/movies'),
    searchShowtimes: (params: ShowtimeSearchParams) => get<ShowtimePage>(`/api/showtimes${toQuery(params)}`),
    showtime: (showtimeId: number) => get<ShowtimeDetail>(`/api/showtimes/${showtimeId}`),
    seatMap: (showtimeId: number) => get<SeatMap>(`/api/showtimes/${showtimeId}/seats`),
    /** Not retried: a lost response could leave a hold that a resend would collide with. */
    createHold: (showtimeId: number, seatIds: number[]) =>
      request<Hold>(`/api/showtimes/${showtimeId}/holds`, { method: 'POST', body: { seatIds }, retryable: false }),
    releaseHold: async (holdId: string): Promise<void> => {
      await request<void>(`/api/holds/${holdId}`, { method: 'DELETE', retryable: true });
    },
    pay: (holdId: string, scenario: PaymentScenario, idempotencyKey: string) =>
      request<PaymentResponse>(`/api/holds/${holdId}/payment`, {
        method: 'POST',
        body: { scenario },
        headers: { 'Idempotency-Key': idempotencyKey },
        retryable: true,
      }),
    payment: (paymentId: string) => get<PaymentResponse>(`/api/payments/${paymentId}`),
    booking: (bookingId: string) => get<BookingConfirmation>(`/api/bookings/${bookingId}`),
  };
}

export type ApiClient = ReturnType<typeof createApiClient>;
