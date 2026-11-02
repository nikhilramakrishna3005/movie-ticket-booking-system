import { LogIn, LogOut, UserRound } from 'lucide-react';
import { Link, useNavigate } from 'react-router';
import { useAuth } from '../state/AuthContext.tsx';
import { useCheckoutSessions } from '../state/CheckoutContext.tsx';
import { Button } from './ui/Button.tsx';

export function Logo() {
  return (
    <span className="flex items-center gap-2.5">
      <svg viewBox="0 0 32 32" className="size-8" aria-hidden>
        <rect width="32" height="32" rx="9" fill="#1c1a27" />
        <path d="M21.5 10.2a8 8 0 1 0 0 11.6" fill="none" stroke="#a78bfa" strokeWidth="3" strokeLinecap="round" />
        <circle cx="22" cy="16" r="2" fill="#a78bfa" />
      </svg>
      <span className="font-display text-xl font-semibold tracking-tight text-mist-50">Cinéva</span>
    </span>
  );
}

export function Header() {
  const { user, requestLogin, logout } = useAuth();
  const { clear } = useCheckoutSessions();
  const navigate = useNavigate();

  const signOut = () => {
    logout();
    clear();
    navigate('/');
  };

  return (
    <header className="sticky top-0 z-30 border-b border-white/5 bg-ink-950/75 backdrop-blur-xl">
      <div className="mx-auto flex h-16 max-w-7xl items-center justify-between gap-4 px-4 sm:px-6">
        <Link to="/" className="rounded-lg" aria-label="Cinéva home">
          <Logo />
        </Link>
        <nav className="flex items-center gap-2" aria-label="Account">
          {user ? (
            <>
              <span
                className="hidden items-center gap-2 rounded-full bg-white/5 px-3 py-1.5 text-sm text-mist-200 sm:flex"
                data-testid="nav-user-name"
              >
                <UserRound className="size-4 text-violet-300" aria-hidden />
                {user.displayName}
                {user.role === 'THEATER_ADMIN' && <span className="text-xs text-mist-500">· admin</span>}
              </span>
              <Button variant="ghost" size="sm" onClick={signOut} icon={<LogOut className="size-4" aria-hidden />} data-testid="nav-logout-button">
                Sign out
              </Button>
            </>
          ) : (
            <Button
              variant="secondary"
              size="sm"
              onClick={() => void requestLogin()}
              icon={<LogIn className="size-4" aria-hidden />}
              data-testid="nav-login-button"
            >
              Sign in
            </Button>
          )}
        </nav>
      </div>
    </header>
  );
}
