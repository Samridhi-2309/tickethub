import { useRef, useState } from 'react';
import { useLocation, useNavigate, Navigate } from 'react-router-dom';
import { api, formatPrice } from '../api/client';
import Countdown from '../components/Countdown';

export default function Checkout() {
  const location = useLocation();
  const navigate = useNavigate();

  const hold = location.state?.hold;
  const eventName = location.state?.eventName;

  const [status, setStatus] = useState('holding');  // holding | done | expired
  const [booking, setBooking] = useState(null);
  const [error, setError] = useState(null);

  /**
   * One idempotency key per checkout attempt, generated once and kept
   * in a ref so a re-render does not change it.
   *
   * That is the entire point: if the response to the first confirm is
   * lost and the user clicks again, the server sees the same key and
   * replays the original booking instead of creating a second one. A
   * fresh key per click would defeat it.
   */
  const idemKey = useRef(crypto.randomUUID());

  if (!hold) return <Navigate to="/" replace />;

  async function confirm() {
    setError(null);
    try {
      const result = await api.confirm(hold.holdId, idemKey.current);
      setBooking(result);
      setStatus('done');
    } catch (e) {
      if (e.status === 410) setStatus('expired');
      else setError(e.message);
    }
  }

  if (status === 'done') {
    return (
      <div className="card narrow success">
        <h2>Booked</h2>
        <p>Booking #{booking.id} · {formatPrice(booking.totalCents)}</p>
        <p className="muted">Seats {booking.seatIds.join(', ')}</p>
        <div className="actions">
          <button className="primary" onClick={() => navigate('/bookings')}>My bookings</button>
          <button className="link" onClick={() => navigate('/')}>Back to events</button>
        </div>
      </div>
    );
  }

  if (status === 'expired') {
    return (
      <div className="card narrow">
        <h2>Hold expired</h2>
        <p className="muted">
          The seats were released back to the pool because the hold was not
          confirmed in time. Nothing was charged.
        </p>
        <button className="primary" onClick={() => navigate('/')}>Pick seats again</button>
      </div>
    );
  }

  return (
    <div className="card narrow">
      <h2>Confirm your seats</h2>
      <p className="muted">{eventName}</p>

      <div className="hold-summary">
        <div>
          <span className="muted">Seats</span>
          <strong>{hold.seatIds.join(', ')}</strong>
        </div>
        <div>
          <span className="muted">Total</span>
          <strong>{formatPrice(hold.totalCents)}</strong>
        </div>
        <div>
          <span className="muted">Held for</span>
          <Countdown expiresAt={hold.expiresAt} onExpire={() => setStatus('expired')} />
        </div>
      </div>

      {error && <p className="error">{error}</p>}

      <button className="primary wide" onClick={confirm}>
        Confirm booking
      </button>

      <p className="muted small">
        These seats are reserved for you until the timer runs out. Nobody else
        can take them in the meantime.
      </p>
    </div>
  );
}
