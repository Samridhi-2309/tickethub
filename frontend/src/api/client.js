const BASE = '';   // Vite proxies /api to :8080 in dev

const TOKEN_KEY = 'tickethub.token';
const USER_KEY = 'tickethub.user';

/**
 * Token storage.
 *
 * localStorage is readable by any script on the origin, so an XSS bug
 * leaks the token. The alternative is an httpOnly cookie, which JS
 * cannot read — but that needs CSRF protection back, since the browser
 * would attach it automatically. For a project this size localStorage
 * is the reasonable trade; a production app handling payments would use
 * the cookie plus CSRF tokens.
 */
export const tokenStore = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (token) => localStorage.setItem(TOKEN_KEY, token),
  clear: () => {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
  },
  getUser: () => {
    const raw = localStorage.getItem(USER_KEY);
    return raw ? JSON.parse(raw) : null;
  },
  setUser: (user) => localStorage.setItem(USER_KEY, JSON.stringify(user))
};

/** Error carrying the server's code and HTTP status, not just a message. */
export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

async function request(path, { method = 'GET', body, headers = {}, auth = true } = {}) {
  const token = tokenStore.get();

  const res = await fetch(BASE + path, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(auth && token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers
    },
    body: body ? JSON.stringify(body) : undefined
  });

  if (res.status === 204) return null;

  const text = await res.text();
  const data = text ? JSON.parse(text) : null;

  if (!res.ok) {
    // An expired token should drop the session rather than leaving the
    // UI in a half-logged-in state.
    if (res.status === 401) tokenStore.clear();
    throw new ApiError(res.status, data?.code ?? 'ERROR', data?.message ?? res.statusText);
  }
  return data;
}

export const api = {
  // ── auth ────────────────────────────────────────────────────
  login: (email, password) =>
    request('/api/auth/login', { method: 'POST', body: { email, password }, auth: false }),

  register: (email, password, displayName) =>
    request('/api/auth/register', {
      method: 'POST',
      body: { email, password, displayName },
      auth: false
    }),

  me: () => request('/api/auth/me'),

  // ── events (public) ─────────────────────────────────────────
  listEvents: () => request('/api/events', { auth: false }),
  getSeatMap: (eventId) => request(`/api/events/${eventId}/seats`, { auth: false }),

  // ── booking (authenticated) ─────────────────────────────────
  hold: (eventId, seatIds) =>
    request('/api/bookings/hold', { method: 'POST', body: { eventId, seatIds } }),

  /**
   * The Idempotency-Key is generated once per checkout attempt and
   * reused on retry, so a dropped response cannot produce a second
   * booking. Generating a fresh key per retry would defeat the point.
   */
  confirm: (holdId, idempotencyKey) =>
    request('/api/bookings/confirm', {
      method: 'POST',
      body: { holdId },
      headers: { 'Idempotency-Key': idempotencyKey }
    }),

  listBookings: () => request('/api/bookings'),
  cancelBooking: (id) => request(`/api/bookings/${id}/cancel`, { method: 'POST' })
};

export const formatPrice = (cents) =>
  new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    maximumFractionDigits: 0
  }).format(cents / 100);
