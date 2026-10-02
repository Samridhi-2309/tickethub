import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import { api, tokenStore } from '../api/client';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => tokenStore.getUser());
  const [checking, setChecking] = useState(true);

  // A token in localStorage may be expired or signed with a key the
  // server no longer uses, so it is verified on load rather than
  // trusted. Until that check finishes the app renders nothing, which
  // avoids a flash of the logged-out UI for a valid session.
  useEffect(() => {
    const token = tokenStore.get();
    if (!token) {
      setChecking(false);
      return;
    }
    api.me()
      .then(() => setChecking(false))
      .catch(() => {
        tokenStore.clear();
        setUser(null);
        setChecking(false);
      });
  }, []);

  const value = useMemo(() => ({
    user,
    checking,
    async login(email, password) {
      const auth = await api.login(email, password);
      tokenStore.set(auth.token);
      tokenStore.setUser(auth);
      setUser(auth);
      return auth;
    },
    async register(email, password, displayName) {
      const auth = await api.register(email, password, displayName);
      tokenStore.set(auth.token);
      tokenStore.setUser(auth);
      setUser(auth);
      return auth;
    },
    logout() {
      tokenStore.clear();
      setUser(null);
    }
  }), [user, checking]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
