import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../../api/client.ts';
import type { PaymentScenario } from '../../api/types.ts';
import { newIdempotencyKey } from '../../lib/idempotency.ts';
import { useAuth } from '../../state/AuthContext.tsx';
import type { CheckoutSession } from '../../state/CheckoutContext.tsx';
import { POLL_DELAYS_MS, fromPayError, fromPayResponse, fromPayment, retiresKey, type PaymentAttempt, type PaymentOutcome } from './paymentFlow.ts';

export interface CheckoutController {
  scenario: PaymentScenario;
  setScenario: (scenario: PaymentScenario) => void;
  /** The attempt whose key the next Pay click reuses; null means the next click starts a new attempt. */
  attempt: PaymentAttempt | null;
  outcome: PaymentOutcome | null;
  submitting: boolean;
  /** True while a pending payment is being polled automatically. */
  polling: boolean;
  pay: () => Promise<void>;
  checkAgain: () => void;
}

export function useCheckout(session: CheckoutSession | undefined): CheckoutController {
  const { api, requestLogin } = useAuth();
  const [scenario, setScenarioState] = useState<PaymentScenario>('SUCCESS');
  const [attempt, setAttempt] = useState<PaymentAttempt | null>(null);
  const [outcome, setOutcome] = useState<PaymentOutcome | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [pollIndex, setPollIndex] = useState(0);
  const inFlight = useRef(false);

  const apply = useCallback((next: PaymentOutcome) => {
    if (retiresKey(next)) setAttempt(null);
    setOutcome(next);
  }, []);

  const pay = useCallback(async () => {
    if (!session || inFlight.current) return;
    if (outcome?.kind === 'pending') return;
    // Reuse the key of an attempt that has not been definitively settled; never mint a new one for it.
    const current = attempt ?? { idempotencyKey: newIdempotencyKey(), scenario };
    setAttempt(current);
    inFlight.current = true;
    setSubmitting(true);
    try {
      const response = await api.pay(session.hold.holdId, current.scenario, current.idempotencyKey);
      apply(fromPayResponse(response));
    } catch (error) {
      apply(fromPayError(error));
    } finally {
      inFlight.current = false;
      setSubmitting(false);
      setPollIndex(0);
    }
  }, [api, apply, attempt, outcome, scenario, session]);

  const pendingId = outcome?.kind === 'pending' ? outcome.paymentId : null;
  const polling = pendingId !== null && pollIndex < POLL_DELAYS_MS.length;

  useEffect(() => {
    if (!pendingId || pollIndex >= POLL_DELAYS_MS.length) return;
    let cancelled = false;
    const timer = setTimeout(async () => {
      try {
        const payment = await api.payment(pendingId);
        if (!cancelled) apply(fromPayment(payment));
      } catch (error) {
        if (cancelled) return;
        if (error instanceof ApiError && error.status === 401) {
          if (!(await requestLogin())) return;
        }
      }
      if (!cancelled) setPollIndex((index) => index + 1);
    }, POLL_DELAYS_MS[pollIndex]);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [api, apply, pendingId, pollIndex, requestLogin]);

  const setScenario = useCallback(
    (next: PaymentScenario) => {
      // The server rejects a reused key with a different scenario, so an open attempt pins it.
      if (!attempt) setScenarioState(next);
    },
    [attempt],
  );

  return {
    scenario: attempt?.scenario ?? scenario,
    setScenario,
    attempt,
    outcome,
    submitting,
    polling,
    pay,
    checkAgain: () => setPollIndex(0),
  };
}
