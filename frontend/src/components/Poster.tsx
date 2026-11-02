import { useId } from 'react';
import { monogram, posterFor, type PosterSpec } from '../lib/posters.ts';

function Motif({ spec }: { spec: PosterSpec }) {
  const { accent, motif } = spec;
  switch (motif) {
    case 'orbit':
      return (
        <g fill="none" stroke={accent} strokeOpacity="0.35">
          <circle cx="150" cy="170" r="70" />
          <circle cx="150" cy="170" r="110" strokeOpacity="0.2" />
          <circle cx="150" cy="170" r="150" strokeOpacity="0.1" />
          <circle cx="220" cy="120" r="9" fill={accent} fillOpacity="0.8" stroke="none" />
        </g>
      );
    case 'horizon':
      return (
        <g>
          <circle cx="150" cy="250" r="90" fill={accent} fillOpacity="0.28" />
          {[0, 1, 2, 3, 4].map((line) => (
            <rect key={line} x="0" y={262 + line * 14} width="300" height={3 + line} fill="#000" fillOpacity="0.45" />
          ))}
        </g>
      );
    case 'beams':
      return (
        <g fill={accent}>
          {[-40, -15, 10, 35].map((angle, index) => (
            <rect
              key={angle}
              x="140"
              y="-40"
              width="20"
              height="520"
              fillOpacity={0.08 + index * 0.04}
              transform={`rotate(${angle} 150 420)`}
            />
          ))}
        </g>
      );
    case 'grid':
      return (
        <g stroke={accent} strokeOpacity="0.16">
          {Array.from({ length: 9 }, (_, index) => (
            <line key={`v${index}`} x1={index * 37.5} y1="0" x2={index * 37.5} y2="450" />
          ))}
          {Array.from({ length: 13 }, (_, index) => (
            <line key={`h${index}`} x1="0" y1={index * 37.5} x2="300" y2={index * 37.5} />
          ))}
          <rect x="112" y="132" width="76" height="76" fill={accent} fillOpacity="0.3" stroke="none" />
        </g>
      );
    case 'halo':
      return (
        <g>
          <circle cx="150" cy="180" r="120" fill={accent} fillOpacity="0.12" />
          <circle cx="150" cy="180" r="74" fill={accent} fillOpacity="0.18" />
          <circle cx="150" cy="180" r="36" fill={accent} fillOpacity="0.35" />
        </g>
      );
  }
}

interface PosterProps {
  title: string;
  className?: string;
  /** Show the title on the art (off when the title is already printed next to the poster). */
  showTitle?: boolean;
}

/** Generated, license-free poster art (see src/lib/posters.ts). Decorative: the title is in the text. */
export function Poster({ title, className = '', showTitle = true }: PosterProps) {
  const spec = posterFor(title);
  const id = useId();
  return (
    <div className={`relative overflow-hidden bg-ink-800 ${className}`} aria-hidden>
      <svg viewBox="0 0 300 450" preserveAspectRatio="xMidYMid slice" className="absolute inset-0 size-full">
        <defs>
          <linearGradient id={`${id}-bg`} gradientTransform={`rotate(${spec.angle % 90} 0.5 0.5)`}>
            <stop offset="0" stopColor={spec.from} />
            <stop offset="1" stopColor={spec.to} />
          </linearGradient>
          <linearGradient id={`${id}-fade`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0.45" stopColor="#000" stopOpacity="0" />
            <stop offset="1" stopColor="#000" stopOpacity="0.85" />
          </linearGradient>
        </defs>
        <rect width="300" height="450" fill={`url(#${id}-bg)`} />
        <Motif spec={spec} />
        <text
          x="150"
          y="196"
          textAnchor="middle"
          fontFamily="Fraunces Variable, Georgia, serif"
          fontSize="92"
          fontWeight="600"
          fill="#fff"
          fillOpacity="0.14"
        >
          {monogram(title)}
        </text>
        <rect width="300" height="450" fill={`url(#${id}-fade)`} />
      </svg>
      {showTitle && (
        <p className="absolute inset-x-0 bottom-0 p-4 font-display text-lg leading-tight font-semibold text-white">
          {title}
        </p>
      )}
    </div>
  );
}
