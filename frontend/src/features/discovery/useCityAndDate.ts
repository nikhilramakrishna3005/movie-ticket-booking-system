import { useCallback, useMemo } from 'react';
import { useLocation, useSearchParams } from 'react-router';
import type { City } from '../../api/types.ts';
import { dateRange, isIsoDate, todayIn } from '../../lib/dates.ts';

export const DAYS_SHOWN = 7;
const FALLBACK_ZONE = 'America/Chicago';

/**
 * City and date live in the URL (?city=1&date=2026-11-03) so a discover or movie page can be
 * shared and survives a reload. Dates are local to the selected city's time zone.
 */
export function useCityAndDate(cities: City[] | undefined) {
  const [params, setParams] = useSearchParams();
  const { pathname } = useLocation();

  const city = useMemo(() => {
    if (!cities || cities.length === 0) return undefined;
    const requested = Number(params.get('city'));
    return cities.find((candidate) => candidate.id === requested) ?? cities[0];
  }, [cities, params]);

  const today = todayIn(city?.timeZone ?? FALLBACK_ZONE);
  const dates = useMemo(() => dateRange(today, DAYS_SHOWN), [today]);
  const requestedDate = params.get('date');
  const date = isIsoDate(requestedDate) && requestedDate >= today ? requestedDate : today;
  const visibleDates = dates.includes(date) ? dates : [...dates, date];

  /**
   * React Router applies navigations in a React transition, so this page stays mounted for a
   * moment after the browser has already moved to another entry. setParams navigates relative to
   * the rendered page with replace, so a late call (e.g. the debounced search) would overwrite the
   * entry the user just opened with this page. Updates from a page the user has left are dropped.
   */
  const update = useCallback(
    (changes: Record<string, string | null>) => {
      if (window.location.pathname !== pathname) return;
      setParams(
        (current) => {
          const next = new URLSearchParams(current);
          for (const [key, value] of Object.entries(changes)) {
            if (value === null || value === '') next.delete(key);
            else next.set(key, value);
          }
          return next;
        },
        { replace: true },
      );
    },
    [setParams, pathname],
  );

  return {
    city,
    date,
    today,
    dates: visibleDates,
    params,
    update,
    setCity: (cityId: number) => update({ city: String(cityId) }),
    setDate: (value: string) => update({ date: value }),
  };
}
