// Generated poster art. The app ships no studio key art (it is copyrighted and would need a
// license); every poster is drawn from the title alone, so it is stable across reloads and works
// fully offline.

export type PosterMotif = 'orbit' | 'horizon' | 'beams' | 'grid' | 'halo';

export interface PosterSpec {
  /** Background gradient stops, dark to light. */
  from: string;
  to: string;
  accent: string;
  motif: PosterMotif;
  angle: number;
}

const PALETTES: ReadonlyArray<Omit<PosterSpec, 'motif' | 'angle'>> = [
  { from: '#1a1033', to: '#4c2a85', accent: '#c4b5fd' },
  { from: '#0b1d2a', to: '#1f4e5f', accent: '#7dd3fc' },
  { from: '#2a0f16', to: '#7a2433', accent: '#fda4af' },
  { from: '#14140f', to: '#5a4a1f', accent: '#fcd34d' },
  { from: '#0e1a14', to: '#245143', accent: '#6ee7b7' },
  { from: '#1c1124', to: '#6b2f63', accent: '#f0abfc' },
  { from: '#101522', to: '#33416b', accent: '#a5b4fc' },
  { from: '#1f130c', to: '#7c3f1d', accent: '#fdba74' },
];

const MOTIFS: readonly PosterMotif[] = ['orbit', 'horizon', 'beams', 'grid', 'halo'];

/** FNV-1a: small, fast, and stable across platforms. */
export function hashString(value: string): number {
  let hash = 0x811c9dc5;
  for (let index = 0; index < value.length; index++) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193);
  }
  return hash >>> 0;
}

export function posterFor(title: string): PosterSpec {
  const hash = hashString(title);
  const palette = PALETTES[hash % PALETTES.length]!;
  return {
    ...palette,
    motif: MOTIFS[(hash >>> 8) % MOTIFS.length]!,
    angle: (hash >>> 16) % 360,
  };
}

/** Up to two initials for the poster monogram: "The Dark Knight" → "DK". */
export function monogram(title: string): string {
  const words = title
    .replace(/[^\p{L}\p{N}\s]/gu, ' ')
    .split(/\s+/)
    .filter((word) => word && !['the', 'a', 'an', 'of', 'and'].includes(word.toLowerCase()));
  const source = words.length > 0 ? words : [title];
  return source
    .slice(0, 2)
    .map((word) => word.charAt(0).toUpperCase())
    .join('');
}
