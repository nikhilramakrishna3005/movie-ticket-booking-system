import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError } from '../../api/client.ts';
import type { BookingConfirmation, PaymentResponse } from '../../api/types.ts';
import { fromPayError, fromPayment, isTerminal, retiresKey } from './paymentFlow.ts';

const booking = { bookingId: 'b1', reference: 'BK-1' } as BookingConfirmation;
const payment = (overrides: Partial<PaymentResponse>): PaymentResponse => ({
  paymentId: 'p1',
  holdId: 'h1',
  status: 'SUCCEEDED',
  amount: 25,
  booking: null,
  ...overrides,
});

describe('payment outcomes', () => {
  it('only a booked SUCCEEDED payment counts as success', () => {
    assert.deepEqual(fromPayment(payment({ booking })), { kind: 'succeeded', booking });
    assert.deepEqual(fromPayment(payment({ booking: null })), { kind: 'pending', paymentId: 'p1' });
  });

  it('treats UNKNOWN as pending, never as declined', () => {
    const outcome = fromPayment(payment({ status: 'UNKNOWN' }));
    assert.deepEqual(outcome, { kind: 'pending', paymentId: 'p1' });
    assert.equal(retiresKey(outcome), false);
    assert.equal(isTerminal(outcome), false);
  });

  it('a decline retires the key so the retry is a new attempt', () => {
    const outcome = fromPayError(
      new ApiError(402, 'PAYMENT_DECLINED', 'declined', { paymentId: 'p1', status: 'DECLINED' }),
    );
    assert.equal(outcome.kind, 'declined');
    assert.equal(retiresKey(outcome), true);
    assert.equal(retiresKey(fromPayment(payment({ status: 'DECLINED' }))), true);
  });

  it('keeps the key after any ambiguous failure', () => {
    for (const error of [
      new ApiError(0, 'NETWORK_ERROR', 'offline'),
      new ApiError(500, 'INTERNAL_ERROR', 'boom'),
      new ApiError(504, 'HTTP_504', 'gateway'),
      new ApiError(409, 'SEATS_BUSY', 'busy'),
      new Error('unexpected'),
    ]) {
      const outcome = fromPayError(error);
      assert.equal(outcome.kind, 'ambiguous', String(error));
      assert.equal(retiresKey(outcome), false);
    }
  });

  it('polls an attempt already in progress instead of charging again', () => {
    assert.deepEqual(fromPayError(new ApiError(409, 'PAYMENT_IN_PROGRESS', 'busy', { paymentId: 'p9' })), {
      kind: 'pending',
      paymentId: 'p9',
    });
  });

  it('maps hold and payment terminal states', () => {
    assert.equal(fromPayError(new ApiError(409, 'HOLD_EXPIRED', 'x')).kind, 'expired');
    assert.equal(fromPayError(new ApiError(409, 'PAYMENT_VOIDED', 'x', { paymentId: 'p1' })).kind, 'voided');
    assert.equal(fromPayment(payment({ status: 'VOIDED' })).kind, 'voided');
    for (const code of ['TOO_MANY_ATTEMPTS', 'HOLD_TOO_CLOSE_TO_EXPIRY', 'HOLD_NOT_ACTIVE', 'FORBIDDEN', 'HTTP_404']) {
      const outcome = fromPayError(new ApiError(409, code, 'x'));
      assert.equal(outcome.kind, 'blocked', code);
      assert.equal(isTerminal(outcome), true);
    }
  });

  it('asks for sign-in on 401 without dropping the attempt', () => {
    const outcome = fromPayError(new ApiError(401, 'UNAUTHENTICATED', 'x'));
    assert.equal(outcome.kind, 'unauthenticated');
    assert.equal(retiresKey(outcome), false);
  });
});
