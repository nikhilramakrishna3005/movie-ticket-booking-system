import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import { createApiClient, type ApiClient, type Credentials } from '../api/client.ts';
import type { CurrentUser } from '../api/types.ts';

interface AuthContextValue {
  api: ApiClient;
  user: CurrentUser | null;
  /** Validates the credentials against the API and, only if valid, keeps them in memory. */
  login: (credentials: Credentials) => Promise<CurrentUser>;
  logout: () => void;
  /** Opens the sign-in dialog; resolves with the user, or null if the dialog was dismissed. */
  requestLogin: () => Promise<CurrentUser | null>;
  loginOpen: boolean;
  closeLogin: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * Credentials live only in this provider's ref: never in Web Storage, cookies or IndexedDB, so
 * a reload signs the user out. That is the intended trade-off for local HTTP Basic demo auth.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const credentials = useRef<Credentials | null>(null);
  const pendingLogin = useRef<Array<(user: CurrentUser | null) => void>>([]);
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [loginOpen, setLoginOpen] = useState(false);

  const api = useMemo(() => createApiClient({ getCredentials: () => credentials.current }), []);

  const settleLogin = useCallback((result: CurrentUser | null) => {
    const waiting = pendingLogin.current;
    pendingLogin.current = [];
    waiting.forEach((resolve) => resolve(result));
  }, []);

  const login = useCallback(
    async (candidate: Credentials) => {
      const current = await api.login(candidate);
      credentials.current = candidate;
      setUser(current);
      setLoginOpen(false);
      settleLogin(current);
      return current;
    },
    [api, settleLogin],
  );

  const logout = useCallback(() => {
    credentials.current = null;
    setUser(null);
  }, []);

  const requestLogin = useCallback(() => {
    // A stale identity (401 after sign-in) must not be reused.
    credentials.current = null;
    setUser(null);
    setLoginOpen(true);
    return new Promise<CurrentUser | null>((resolve) => pendingLogin.current.push(resolve));
  }, []);

  const closeLogin = useCallback(() => {
    setLoginOpen(false);
    settleLogin(null);
  }, [settleLogin]);

  const value = useMemo(
    () => ({ api, user, login, logout, requestLogin, loginOpen, closeLogin }),
    [api, user, login, logout, requestLogin, loginOpen, closeLogin],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside AuthProvider');
  return value;
}
