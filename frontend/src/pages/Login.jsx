import { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

export default function Login() {
  const { login, register } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [mode, setMode] = useState('login');
  const [email, setEmail] = useState('demo@tickethub.dev');
  const [password, setPassword] = useState('password123');
  const [displayName, setDisplayName] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  // Send the user back where they were headed before the redirect.
  const next = location.state?.from ?? '/';

  async function onSubmit(e) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      if (mode === 'login') await login(email, password);
      else await register(email, password, displayName);
      navigate(next, { replace: true });
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card narrow">
      <h2>{mode === 'login' ? 'Sign in' : 'Create an account'}</h2>

      <form onSubmit={onSubmit}>
        <label>
          Email
          <input type="email" value={email} required
                 onChange={(e) => setEmail(e.target.value)} />
        </label>

        {mode === 'register' && (
          <label>
            Name
            <input value={displayName} required
                   onChange={(e) => setDisplayName(e.target.value)} />
          </label>
        )}

        <label>
          Password
          <input type="password" value={password} required minLength={8}
                 onChange={(e) => setPassword(e.target.value)} />
        </label>

        {error && <p className="error">{error}</p>}

        <button className="primary" disabled={busy}>
          {busy ? 'Working…' : mode === 'login' ? 'Sign in' : 'Create account'}
        </button>
      </form>

      <p className="muted">
        {mode === 'login' ? 'No account? ' : 'Already registered? '}
        <button type="button" className="link"
                onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setError(null); }}>
          {mode === 'login' ? 'Create one' : 'Sign in'}
        </button>
      </p>

      {mode === 'login' && (
        <p className="muted small">
          Demo account is pre-filled. A second account,
          <code> second@tickethub.dev</code>, uses the same password — useful
          for racing two browser windows for the same seat.
        </p>
      )}
    </div>
  );
}
