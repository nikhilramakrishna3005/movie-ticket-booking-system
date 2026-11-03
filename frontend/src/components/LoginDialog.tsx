import { useEffect, useRef, useState, type FormEvent } from 'react';
import { ShieldCheck, X } from 'lucide-react';
import { ApiError } from '../api/client.ts';
import { describeError } from '../lib/errors.ts';
import { useAuth } from '../state/AuthContext.tsx';
import { Button } from './ui/Button.tsx';

/**
 * Native <dialog> for focus trapping, Escape handling and inert background. The demo accounts are
 * documented in the README, never in the bundle.
 */
export function LoginDialog() {
  const { loginOpen, closeLogin, login } = useAuth();
  const dialog = useRef<HTMLDialogElement>(null);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    const element = dialog.current;
    if (!element) return;
    if (loginOpen && !element.open) {
      setError(null);
      element.showModal();
    } else if (!loginOpen && element.open) {
      element.close();
    }
  }, [loginOpen]);

  useEffect(() => {
    // The password must not outlive the dialog in component state.
    if (!loginOpen) setPassword('');
  }, [loginOpen]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await login({ username: username.trim(), password });
    } catch (caught) {
      setError(
        caught instanceof ApiError && caught.status === 401
          ? 'That username and password do not match a demo account.'
          : describeError(caught),
      );
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <dialog
      ref={dialog}
      onClose={() => loginOpen && closeLogin()}
      onClick={(event) => event.target === dialog.current && closeLogin()}
      aria-labelledby="login-title"
      data-testid="login-dialog"
      className="m-auto w-[min(26rem,calc(100vw-2rem))] rounded-3xl border border-white/8 bg-ink-900 p-0 text-mist-200 shadow-2xl backdrop:bg-black/70 backdrop:backdrop-blur-sm"
    >
      <form onSubmit={submit} className="p-6 sm:p-7">
        <div className="mb-6 flex items-start justify-between gap-4">
          <div>
            <p className="eyebrow">Welcome back</p>
            <h2 id="login-title" className="mt-1 font-display text-2xl font-semibold text-mist-50">
              Sign in
            </h2>
          </div>
          <button
            type="button"
            onClick={closeLogin}
            className="rounded-lg p-1.5 text-mist-400 hover:bg-white/5 hover:text-mist-50"
            aria-label="Close sign-in"
          >
            <X className="size-5" aria-hidden />
          </button>
        </div>

        <label className="block text-sm font-medium text-mist-200" htmlFor="login-username">
          Username
        </label>
        <input
          id="login-username"
          data-testid="login-username"
          autoComplete="username"
          required
          autoFocus
          value={username}
          onChange={(event) => setUsername(event.target.value)}
          className="mt-1.5 mb-4 h-11 w-full rounded-xl border border-white/8 bg-ink-850 px-3.5 text-mist-50 placeholder:text-mist-500 focus:border-violet-400 focus:outline-none"
        />
        <label className="block text-sm font-medium text-mist-200" htmlFor="login-password">
          Password
        </label>
        <input
          id="login-password"
          data-testid="login-password"
          type="password"
          autoComplete="current-password"
          required
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          className="mt-1.5 h-11 w-full rounded-xl border border-white/8 bg-ink-850 px-3.5 text-mist-50 focus:border-violet-400 focus:outline-none"
        />

        {error && (
          <p role="alert" data-testid="login-error" className="mt-4 rounded-xl bg-danger-400/10 px-3.5 py-2.5 text-sm text-danger-400">
            {error}
          </p>
        )}

        <Button type="submit" size="lg" className="mt-6 w-full" loading={submitting} data-testid="login-submit">
          Sign in
        </Button>

        <p className="mt-5 flex gap-2 text-xs leading-relaxed text-mist-500">
          <ShieldCheck className="size-4 shrink-0 text-violet-300" aria-hidden />
          Use one of the local demo accounts listed in the project README. Your password stays in this tab's memory
          and is forgotten on reload.
        </p>
      </form>
    </dialog>
  );
}
