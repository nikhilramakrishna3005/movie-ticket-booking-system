import { Timer } from 'lucide-react';
import { countdown } from '../lib/dates.ts';

const WARNING_MS = 2 * 60_000;

export function HoldCountdown({ remainingMs, totalMs }: { remainingMs: number; totalMs: number }) {
  const fraction = totalMs > 0 ? Math.min(1, remainingMs / totalMs) : 0;
  const urgent = remainingMs <= WARNING_MS;
  const color = remainingMs === 0 ? 'text-danger-400' : urgent ? 'text-warning-400' : 'text-violet-300';
  return (
    <div className="flex items-center gap-4" data-testid="hold-countdown" data-remaining-ms={Math.round(remainingMs)}>
      <div className="relative grid size-14 place-items-center">
        <svg viewBox="0 0 36 36" className="absolute inset-0 -rotate-90" aria-hidden>
          <circle cx="18" cy="18" r="16" fill="none" stroke="currentColor" strokeWidth="2.5" className="text-ink-700" />
          <circle
            cx="18"
            cy="18"
            r="16"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.5"
            strokeLinecap="round"
            strokeDasharray={`${fraction * 100.5} 100.5`}
            className={`${color} transition-[stroke-dasharray] duration-1000 ease-linear`}
          />
        </svg>
        <Timer className={`size-5 ${color}`} aria-hidden />
      </div>
      <div>
        <p className="eyebrow">Seats held for</p>
        {/* Announced only on request: a live region ticking every second would be noise. */}
        <p className={`font-display text-3xl font-semibold tabular-nums ${remainingMs === 0 ? 'text-danger-400' : 'text-mist-50'}`}>
          {countdown(remainingMs)}
        </p>
      </div>
    </div>
  );
}
