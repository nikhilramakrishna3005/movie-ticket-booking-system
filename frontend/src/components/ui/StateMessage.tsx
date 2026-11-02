import type { ReactNode } from 'react';
import { CircleCheck, Info, TriangleAlert } from 'lucide-react';

type Tone = 'info' | 'error' | 'success' | 'warning';

const tones: Record<Tone, { icon: ReactNode; ring: string }> = {
  info: { icon: <Info className="size-5 text-violet-300" aria-hidden />, ring: 'border-white/8' },
  error: { icon: <TriangleAlert className="size-5 text-danger-400" aria-hidden />, ring: 'border-danger-400/25' },
  warning: { icon: <TriangleAlert className="size-5 text-warning-400" aria-hidden />, ring: 'border-warning-400/25' },
  success: { icon: <CircleCheck className="size-5 text-success-400" aria-hidden />, ring: 'border-success-400/25' },
};

interface StateMessageProps {
  tone?: Tone;
  title: string;
  children?: ReactNode;
  action?: ReactNode;
  testId?: string;
  /** Large, centered panel for whole-page empty and error states. */
  block?: boolean;
}

export function StateMessage({ tone = 'info', title, children, action, testId, block }: StateMessageProps) {
  const { icon, ring } = tones[tone];
  return (
    <div
      data-testid={testId}
      role={tone === 'error' || tone === 'warning' ? 'alert' : 'status'}
      className={`rounded-2xl border bg-ink-900/70 ${ring} ${
        block ? 'flex flex-col items-center px-6 py-14 text-center' : 'flex gap-3 p-4'
      }`}
    >
      <div className={block ? 'mb-4 grid size-12 place-items-center rounded-full bg-white/5' : 'mt-0.5 shrink-0'}>
        {icon}
      </div>
      <div className={block ? 'max-w-md' : 'min-w-0 flex-1'}>
        <p className={`font-semibold text-mist-50 ${block ? 'text-lg' : 'text-sm'}`}>{title}</p>
        {children && <div className={`text-sm text-mist-400 ${block ? 'mt-2' : 'mt-1'}`}>{children}</div>}
        {action && <div className={block ? 'mt-6 flex flex-wrap justify-center gap-3' : 'mt-3 flex flex-wrap gap-2'}>{action}</div>}
      </div>
    </div>
  );
}
