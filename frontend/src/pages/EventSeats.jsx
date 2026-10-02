import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { api, formatPrice } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import SeatMap from '../components/SeatMap';

const POLL_MS = 3000;

export default function EventSeats() {
  const { eventId } = useParams();
  const navigate = useNavigate();
  const { user } = useAuth();

  const [map, setMap] = useState(null);
  const [selected, setSelected] = useState([]);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  // Avoids a React state update after the component unmounts, which
  // would otherwise happen when a poll lands mid-navigation.
  // Guards against setting state after unmount.
  //
  // It must be set true on every mount, not just at ref creation:
  // StrictMode mounts, unmounts and remounts each component in
  // development, so a ref only cleared in the cleanup stays false
  // forever after that first simulated unmount — and every later
  // response gets silently dropped.
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);

  const refresh = useCallback(async () => {
    try {
      const data = await api.getSeatMap(eventId);
      if (alive.current) setMap(data);
    } catch (e) {
      if (alive.current) setError(e.message);
    }
  }, [eventId]);

  // Polling, not websockets. Honest about the trade-off: a 3s poll is a
  // few lines and good enough to watch seats grey out in a second
  // browser window, whereas live seat state at real scale would want a
  // websocket or SSE push so clients are not all polling a hot event.
  useEffect(() => {
    refresh();
    const id = setInterval(refresh, POLL_MS);
    return () => clearInterval(id);
  }, [refresh]);

  function toggle(seatId) {
    setError(null);
    setSelected((prev) =>
      prev.includes(seatId) ? prev.filter((id) => id !== seatId) : [...prev, seatId]);
  }

  async function startCheckout() {
    if (!user) {
      navigate('/login', { state: { from: `/events/${eventId}` } });
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const hold = await api.hold(Number(eventId), selected);
      navigate('/checkout', { state: { hold, eventName: map.eventName } });
    } catch (e) {
      // 409 means someone else claimed a seat between the render and
      // the click. Refresh so the UI reflects reality instead of
      // leaving a stale selection on screen.
      setError(e.message);
      setSelected([]);
      refresh();
    } finally {
      setBusy(false);
    }
  }

  if (error && !map) return <p className="error">{error}</p>;
  if (!map) return <p className="muted">Loading seat map…</p>;

  const selectedSeats = map.seats.filter((s) => selected.includes(s.id));
  const total = selectedSeats.reduce((sum, s) => sum + s.priceCents, 0);

  return (
    <div>
      <button className="link back" onClick={() => navigate('/')}>← All events</button>

      <div className="row-between">
        <h2>{map.eventName}</h2>
        <span className="muted">{map.availableSeats} of {map.totalSeats} available</span>
      </div>

      {error && <p className="error">{error}</p>}

      <SeatMap seats={map.seats} selected={selected} onToggle={toggle} disabled={busy} />

      <div className="card checkout-bar">
        {selected.length === 0 ? (
          <p className="muted">Pick up to 8 seats.</p>
        ) : (
          <>
            <div>
              <strong>{selected.length} seat{selected.length > 1 ? 's' : ''}</strong>
              <span className="muted"> · {formatPrice(total)}</span>
            </div>
            <button className="primary" onClick={startCheckout} disabled={busy}>
              {busy ? 'Holding…' : 'Hold these seats'}
            </button>
          </>
        )}
      </div>
    </div>
  );
}
