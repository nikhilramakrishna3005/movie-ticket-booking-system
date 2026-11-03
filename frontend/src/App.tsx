import { useEffect } from 'react';
import { MotionConfig } from 'motion/react';
import { BrowserRouter, Outlet, Route, Routes, useLocation } from 'react-router';
import { AuthProvider } from './state/AuthContext.tsx';
import { CheckoutProvider } from './state/CheckoutContext.tsx';
import { Header } from './components/Header.tsx';
import { LoginDialog } from './components/LoginDialog.tsx';
import { ButtonLink } from './components/ui/Button.tsx';
import { StateMessage } from './components/ui/StateMessage.tsx';
import { DiscoverPage } from './pages/DiscoverPage.tsx';
import { MoviePage } from './pages/MoviePage.tsx';
import { SeatsPage } from './pages/SeatsPage.tsx';
import { CheckoutPage } from './pages/CheckoutPage.tsx';
import { ConfirmationPage } from './pages/ConfirmationPage.tsx';
import { MyTicketsPage } from './pages/MyTicketsPage.tsx';
import { AdminPage } from './pages/AdminPage.tsx';

function ScrollToTop() {
  const { pathname } = useLocation();
  // Block body on purpose: newer browsers return a Promise from scrollTo, which React would
  // otherwise treat as the effect's cleanup function.
  useEffect(() => {
    window.scrollTo(0, 0);
  }, [pathname]);
  return null;
}

function Layout() {
  return (
    <div className="flex min-h-dvh flex-col">
      <a href="#main" className="sr-only z-50 rounded-lg bg-violet-500 px-4 py-2 text-white focus:not-sr-only focus:fixed focus:top-3 focus:left-3">
        Skip to content
      </a>
      <ScrollToTop />
      <Header />
      <main id="main" className="flex-1">
        <Outlet />
      </main>
      <footer className="border-t border-white/5 py-8 text-center text-xs text-mist-500">
        Cinéva is a local demo. Payments are simulated and no real money moves.
      </footer>
      <LoginDialog />
    </div>
  );
}

function NotFoundPage() {
  return (
    <div className="mx-auto max-w-3xl px-4 py-20">
      <StateMessage block title="Page not found" action={<ButtonLink to="/">Back to movies</ButtonLink>}>
        The page you were looking for does not exist.
      </StateMessage>
    </div>
  );
}

export function App() {
  return (
    <MotionConfig reducedMotion="user">
      <BrowserRouter>
        <AuthProvider>
          <CheckoutProvider>
            <Routes>
              <Route element={<Layout />}>
                <Route index element={<DiscoverPage />} />
                <Route path="movies/:movieId" element={<MoviePage />} />
                <Route path="showtimes/:showtimeId/seats" element={<SeatsPage />} />
                <Route path="checkout/:holdId" element={<CheckoutPage />} />
                <Route path="bookings/:bookingId" element={<ConfirmationPage />} />
                <Route path="tickets" element={<MyTicketsPage />} />
                <Route path="admin" element={<AdminPage />} />
                <Route path="*" element={<NotFoundPage />} />
              </Route>
            </Routes>
          </CheckoutProvider>
        </AuthProvider>
      </BrowserRouter>
    </MotionConfig>
  );
}
