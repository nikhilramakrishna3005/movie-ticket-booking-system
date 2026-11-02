import { dayLabel } from '../lib/dates.ts';

interface DateStripProps {
  dates: string[];
  selected: string;
  today: string;
  onSelect: (date: string) => void;
}

/** A radio group of days; arrow keys move between them (roving tabindex). */
export function DateStrip({ dates, selected, today, onSelect }: DateStripProps) {
  const move = (index: number) => {
    const next = dates[(index + dates.length) % dates.length];
    if (!next) return;
    onSelect(next);
    requestAnimationFrame(() => document.querySelector<HTMLButtonElement>(`[data-date="${next}"]`)?.focus());
  };

  return (
    <div role="radiogroup" aria-label="Show date" className="-mx-1 flex gap-2 overflow-x-auto px-1 py-1">
      {dates.map((date, index) => {
        const label = dayLabel(date);
        const active = date === selected;
        return (
          <button
            key={date}
            type="button"
            role="radio"
            aria-checked={active}
            tabIndex={active ? 0 : -1}
            data-date={date}
            data-testid={`date-option-${date}`}
            onClick={() => onSelect(date)}
            onKeyDown={(event) => {
              if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
                event.preventDefault();
                move(index + 1);
              } else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
                event.preventDefault();
                move(index - 1);
              }
            }}
            className={`flex w-16 shrink-0 flex-col items-center rounded-2xl border py-2.5 transition-colors ${
              active
                ? 'border-violet-400/60 bg-violet-500/15 text-mist-50 shadow-glow'
                : 'border-white/6 bg-ink-900/70 text-mist-400 hover:border-white/15 hover:text-mist-50'
            }`}
          >
            <span className="text-[0.65rem] font-semibold tracking-widest uppercase">
              {date === today ? 'Today' : label.weekday}
            </span>
            <span className="font-display text-xl font-semibold">{label.day}</span>
            <span className="text-[0.65rem] text-mist-500">{label.month}</span>
          </button>
        );
      })}
    </div>
  );
}
