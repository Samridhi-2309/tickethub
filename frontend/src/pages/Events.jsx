import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';

export default function Events() {
  const [events, setEvents] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    api.listEvents().then(setEvents).catch((e) => setError(e.message));
  }, []);

  if (error) return <p className="error">{error}</p>;
  if (!events) return <p className="muted">Loading events…</p>;

  return (
    <div>
      <h2>Events</h2>
      <div className="event-grid">
        {events.map((ev) => {
          const soldOut = ev.availableSeats === 0;
          return (
            <Link to={`/events/${ev.id}`} key={ev.id} className="card event">
              <h3>{ev.name}</h3>
              <p className="muted">{ev.venue}</p>
              <p className="muted small">
                {new Date(ev.startsAt).toLocaleString('en-IN', {
                  dateStyle: 'medium', timeStyle: 'short'
                })}
              </p>
              <p className={soldOut ? 'error' : 'availability'}>
                {soldOut ? 'Sold out' : `${ev.availableSeats} of ${ev.totalSeats} seats available`}
              </p>
            </Link>
          );
        })}
      </div>
    </div>
  );
}
