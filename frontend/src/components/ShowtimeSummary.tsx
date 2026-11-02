import { CalendarDays, Clapperboard, Languages, MapPin } from 'lucide-react';
import type { ShowtimeDetail } from '../api/types.ts';
import { localTime, longDate } from '../lib/dates.ts';
import { Poster } from './Poster.tsx';

export function ShowtimeSummary({ showtime, compact }: { showtime: ShowtimeDetail; compact?: boolean }) {
  const line = 'flex items-center gap-2';
  return (
    <div className="flex gap-4">
      <Poster title={showtime.movie.title} showTitle={false} className={`${compact ? 'w-14' : 'w-20'} aspect-[2/3] shrink-0 rounded-xl ring-1 ring-white/10`} />
      <div className="min-w-0 space-y-1.5 text-sm text-mist-400">
        <p className={`font-display font-semibold text-mist-50 ${compact ? 'text-lg' : 'text-xl'}`}>{showtime.movie.title}</p>
        <p className={line}>
          <CalendarDays className="size-4 shrink-0 text-violet-300" aria-hidden />
          {longDate(showtime.localDate)} · {localTime(showtime.localStartTime)}
        </p>
        <p className={line}>
          <MapPin className="size-4 shrink-0 text-violet-300" aria-hidden />
          <span className="truncate">{showtime.theater.name}, {showtime.theater.city.name}</span>
        </p>
        {!compact && (
          <p className={line}>
            <Clapperboard className="size-4 shrink-0 text-violet-300" aria-hidden />
            {showtime.screen.name}
            <Languages className="ml-2 size-4 shrink-0 text-violet-300" aria-hidden />
            {showtime.language.name}
          </p>
        )}
      </div>
    </div>
  );
}
