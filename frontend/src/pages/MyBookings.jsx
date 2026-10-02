import { useEffect, useState } from 'react';
import { api, formatPrice } from '../api/client';

export default function MyBookings() {
  const [bookings, setBookings] = useState(null);
  const [error, setError] = useState(null);
  const [busyId, setBusyId] = useState(null);

  function load() {
    api.listBookings().then(setBookings).catch((e) => setError(e.message));
  }

  useEffect(load, []);

  async function cancel(id) {
    setBusyId(id);
    setError(null);
    try {
      await api.cancelBooking(id);
      load();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusyId(null);
    }
  }

  if (error && !bookings) return <p className="error">{error}</p>;
  if (!bookings) return <p className="muted">Loading bookings…</p>;
  if (bookings.length === 0) return <p className="muted">No bookings yet.</p>;

  return (
    <div>
      <h2>My bookings</h2>
      {error && <p className="error">{error}</p>}

      <table className="bookings">
        <thead>
          <tr>
            <th>#</th><th>Seats</th><th>Total</th><th>Status</th><th>Booked</th><th />
          </tr>
        </thead>
        <tbody>
          {bookings.map((b) => (
            <tr key={b.id} className={b.status === 'CANCELLED' ? 'dim' : ''}>
              <td>{b.id}</td>
              <td>{b.seatIds.join(', ')}</td>
              <td>{formatPrice(b.totalCents)}</td>
              <td><span className={`badge ${b.status.toLowerCase()}`}>{b.status}</span></td>
              <td className="muted small">
                {new Date(b.createdAt).toLocaleString('en-IN', {
                  dateStyle: 'medium', timeStyle: 'short'
                })}
              </td>
              <td>
                {b.status === 'CONFIRMED' && (
                  <button className="link danger" disabled={busyId === b.id}
                          onClick={() => cancel(b.id)}>
                    {busyId === b.id ? 'Cancelling…' : 'Cancel'}
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
