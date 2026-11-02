import type { ReactNode } from 'react';
import { ChevronDown, MapPin } from 'lucide-react';
import type { City } from '../api/types.ts';

interface SelectProps {
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: Array<{ value: string; label: string }>;
  icon?: ReactNode;
  testId?: string;
  className?: string;
}

export function Select({ label, value, onChange, options, icon, testId, className = '' }: SelectProps) {
  return (
    <label className={`relative flex items-center ${className}`}>
      <span className="sr-only">{label}</span>
      {icon && <span className="pointer-events-none absolute left-3 text-mist-500">{icon}</span>}
      <select
        value={value}
        onChange={(event) => onChange(event.target.value)}
        data-testid={testId}
        className={`h-11 w-full appearance-none rounded-xl border border-white/8 bg-ink-850 pr-9 text-sm text-mist-50 hover:border-white/15 focus:border-violet-400 focus:outline-none ${
          icon ? 'pl-9' : 'pl-3.5'
        }`}
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      <ChevronDown className="pointer-events-none absolute right-3 size-4 text-mist-500" aria-hidden />
    </label>
  );
}

export function CitySelect({ cities, value, onChange }: { cities: City[]; value: number | undefined; onChange: (id: number) => void }) {
  return (
    <Select
      label="City"
      value={value === undefined ? '' : String(value)}
      onChange={(next) => onChange(Number(next))}
      options={cities.map((city) => ({ value: String(city.id), label: `${city.name}, ${city.stateCode}` }))}
      icon={<MapPin className="size-4" aria-hidden />}
      testId="city-select"
      className="min-w-48"
    />
  );
}
