import type { ReactNode } from 'react';
import { Bell, LogIn } from 'lucide-react';
import { Link } from 'react-router';
import { useAuth } from '../state/AuthContext.tsx';
import { useAsync } from '../hooks/useAsync.ts';
import { describeError } from '../lib/errors.ts';
import { Button } from '../components/ui/Button.tsx';
import { StateMessage } from '../components/ui/StateMessage.tsx';

export function NotificationsPage() {
  const { user, requestLogin } = useAuth();

  if (!user) {
    return (
      <Shell>
        <StateMessage block title="Sign in to see notifications" testId="notifications-signed-out"
          action={<Button onClick={() => void requestLogin()} icon={<LogIn className="size-4" aria-hidden />} data-testid="notifications-sign-in">Sign in</Button>}>
          Booking confirmations appear here after the local outbox relay delivers them. Sign-in stays in memory only.
        </StateMessage>
      </Shell>
    );
  }
  if (user.role !== 'CUSTOMER') {
    return (
      <Shell>
        <StateMessage block title="Customer account required" testId="notifications-not-customer">
          Theater admin accounts do not receive customer booking notifications.
        </StateMessage>
      </Shell>
    );
  }
  return <Inbox key={user.id} />;
}

function Shell({ children }: { children: ReactNode }) {
  return (
    <div className="mx-auto max-w-3xl px-4 py-10 sm:px-6 sm:py-14">
      <p className="eyebrow flex items-center gap-2">
        <Bell className="size-3.5 text-violet-300" aria-hidden />
        Inbox
      </p>
      <h1 className="mt-3 font-display text-4xl font-semibold tracking-tight text-mist-50 sm:text-5xl">Notifications</h1>
      <p className="mt-2 text-mist-400">Local delivery of confirmed bookings. This is not email.</p>
      <div className="mt-8">{children}</div>
    </div>
  );
}

function Inbox() {
  const { api } = useAuth();
  const list = useAsync(() => api.notifications({ page: 0, size: 20 }), [api]);

  if (list.status === 'error') {
    return (
      <Shell>
        <StateMessage block tone="error" title="Notifications could not be loaded" testId="notifications-error"
          action={<Button onClick={list.reload} data-testid="notifications-retry">Try again</Button>}>
          {describeError(list.error)}
        </StateMessage>
      </Shell>
    );
  }
  if (!list.data) {
    return (
      <Shell>
        <div className="grid gap-3" aria-busy data-testid="notifications-loading">
          <span className="sr-only">Loading notifications</span>
          {[0, 1, 2].map((key) => <div key={key} className="skeleton h-20" />)}
        </div>
      </Shell>
    );
  }
  if (list.data.items.length === 0) {
    return (
      <Shell>
        <StateMessage block title="No notifications yet" testId="notifications-empty">
          When a booking is confirmed, the outbox relay writes one inbox row. Reloading this page after a few seconds
          shows it; Cinéva does not invent notifications in the browser.
        </StateMessage>
      </Shell>
    );
  }

  return (
    <Shell>
      <ol className="grid gap-3" data-testid="notification-list">
        {list.data.items.map((item) => (
          <li key={item.id} className="rounded-2xl border border-white/10 bg-white/5 px-4 py-3" data-testid="notification-item">
            <p className="text-sm font-medium text-mist-50">Booking confirmed</p>
            <p className="mt-1 text-sm text-mist-300">
              Reference {item.reference ?? '—'}
              {item.bookingId && (
                <>
                  {' · '}
                  <Link to={`/bookings/${item.bookingId}`} className="text-violet-300 underline-offset-2 hover:underline">
                    Open ticket
                  </Link>
                </>
              )}
            </p>
          </li>
        ))}
      </ol>
    </Shell>
  );
}
