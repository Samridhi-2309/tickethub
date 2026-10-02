import { useEffect, useState } from 'react';

/**
 * Counts down to `expiresAt` and calls onExpire once.
 *
 * The clock is driven from the server's expiry timestamp, not from a
 * local duration: if the tab is backgrounded or the machine sleeps, a
 * setInterval-based countdown drifts, while recomputing from a fixed
 * instant stays correct.
 */
export default function Countdown({ expiresAt, onExpire }) {
  const [remaining, setRemaining] = useState(() =>
    Math.max(0, Math.floor((new Date(expiresAt) - Date.now()) / 1000)));

  useEffect(() => {
    const tick = () => {
      const secs = Math.max(0, Math.floor((new Date(expiresAt) - Date.now()) / 1000));
      setRemaining(secs);
      if (secs === 0) onExpire?.();
    };
    tick();
    const id = setInterval(tick, 1000);
    return () => clearInterval(id);
  }, [expiresAt, onExpire]);

  const mins = String(Math.floor(remaining / 60)).padStart(2, '0');
  const secs = String(remaining % 60).padStart(2, '0');
  const urgent = remaining <= 30;

  return (
    <span className={urgent ? 'countdown urgent' : 'countdown'}>
      {mins}:{secs}
    </span>
  );
}
