import { ApiError, type ApiResponse } from '../../api/client.ts';
import type { BookingConfirmation, PaymentResponse, PaymentScenario } from '../../api/types.ts';

/**
 * What the checkout page does next after a payment request or status poll.
 *
 * Key rule: an idempotency key belongs to exactly one payment attempt. It is retired (and a new
 * one generated for the next click) only when the server has definitively settled that attempt
 * without charging: a decline, or a 422 proving the key belongs to some other request. Every
 * ambiguous result (network failure, gateway error, 5xx, transient lock timeout) keeps the key,
 * so the only way forward is resending the identical request, which the server deduplicates.
 */
export type PaymentOutcome =
  | { kind: 'succeeded'; booking: BookingConfirmation }
  /** Outcome not known yet (UNKNOWN or still PENDING): poll the payment, never charge again. */
  | { kind: 'pending'; paymentId: string }
  | { kind: 'declined'; message: string }
  /** The hold could no longer be booked, so the captured charge was voided. Terminal. */
  | { kind: 'voided'; message: string }
  | { kind: 'expired'; message: string }
  /** No answer from the server: resend the same request (same key, same scenario) only. */
  | { kind: 'ambiguous'; message: string }
  /** Payment cannot proceed for this hold (attempt limit, hold no longer active, too close to expiry, ...). */
  | { kind: 'blocked'; code: string; message: string }
  | { kind: 'unauthenticated'; message: string };

export interface PaymentAttempt {
  idempotencyKey: string;
  /** Fixed for the life of the key: the server rejects a reused key with a different scenario. */
  scenario: PaymentScenario;
}

export function retiresKey(outcome: PaymentOutcome): boolean {
  return outcome.kind === 'declined';
}

/** Whether the customer may still act on this hold (pay again or retry). */
export function isTerminal(outcome: PaymentOutcome): boolean {
  return outcome.kind === 'succeeded' || outcome.kind === 'voided' || outcome.kind === 'expired'
    || outcome.kind === 'blocked';
}

export function fromPayment(payment: PaymentResponse): PaymentOutcome {
  switch (payment.status) {
    case 'SUCCEEDED':
      return payment.booking
        ? { kind: 'succeeded', booking: payment.booking }
        : { kind: 'pending', paymentId: payment.paymentId };
    case 'PENDING':
    case 'UNKNOWN':
      return { kind: 'pending', paymentId: payment.paymentId };
    case 'DECLINED':
      return { kind: 'declined', message: 'Your payment was declined. No booking was made.' };
    case 'VOIDED':
      return {
        kind: 'voided',
        message: 'Your hold ended before the payment completed, so the charge was voided and no booking was made.',
      };
  }
}

export function fromPayResponse(response: ApiResponse<PaymentResponse>): PaymentOutcome {
  return fromPayment(response.data);
}

const BLOCKED_MESSAGES: Record<string, string> = {
  TOO_MANY_ATTEMPTS: 'This hold has reached its limit of payment attempts. Release it and choose your seats again.',
  HOLD_TOO_CLOSE_TO_EXPIRY: 'Too little time is left on this hold to start a payment safely. Choose your seats again.',
  HOLD_NOT_ACTIVE: 'This hold is no longer active.',
  FORBIDDEN: 'Theater admin accounts cannot buy tickets. Sign in with a customer account.',
  HTTP_404: 'This hold was not found for your account.',
};

export function fromPayError(error: unknown): PaymentOutcome {
  if (!(error instanceof ApiError)) {
    return { kind: 'ambiguous', message: 'Something went wrong before we heard back about your payment.' };
  }
  const paymentId = typeof error.details?.paymentId === 'string' ? error.details.paymentId : null;
  switch (error.code) {
    case 'PAYMENT_DECLINED':
      return { kind: 'declined', message: 'Your payment was declined. No booking was made.' };
    case 'PAYMENT_IN_PROGRESS':
      return paymentId
        ? { kind: 'pending', paymentId }
        : { kind: 'ambiguous', message: 'A payment for this hold is already in progress.' };
    case 'PAYMENT_VOIDED':
      return fromPayment({ paymentId: paymentId ?? '', holdId: '', status: 'VOIDED', amount: 0, booking: null });
    case 'HOLD_EXPIRED':
      return { kind: 'expired', message: 'Your hold expired and the seats were released. No payment was taken.' };
    case 'UNAUTHENTICATED':
      return { kind: 'unauthenticated', message: 'Please sign in again to continue your payment.' };
    case 'TOO_MANY_ATTEMPTS':
    case 'HOLD_TOO_CLOSE_TO_EXPIRY':
    case 'HOLD_NOT_ACTIVE':
    case 'FORBIDDEN':
    case 'HTTP_404':
      return { kind: 'blocked', code: error.code, message: BLOCKED_MESSAGES[error.code]! };
    case 'IDEMPOTENCY_KEY_REUSED':
      return { kind: 'declined', message: 'That payment request could not be matched. Please try again.' };
    case 'SEATS_BUSY':
      return { kind: 'ambiguous', message: 'The seats are busy for a moment. Try again; you will not be charged twice.' };
    default:
      // Network failures, 5xx and anything unexpected: the charge may or may not have happened.
      return {
        kind: 'ambiguous',
        message: error.isNetworkError
          ? 'We lost the connection before hearing back about your payment.'
          : 'We could not confirm what happened to your payment.',
      };
  }
}

/** Status polls for a pending payment: quick at first, then stop and offer a manual check. */
export const POLL_DELAYS_MS: readonly number[] = [1000, 1500, 2000, 3000, 4000, 5000, 5000, 5000];
