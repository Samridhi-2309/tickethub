import { formatPrice } from '../api/client';

/**
 * Renders the seat grid.
 *
 * Seat state comes from the server on every poll rather than being
 * tracked locally. That is the whole point: open two browser windows
 * and the seat one window holds turns grey in the other.
 */
export default function SeatMap({ seats, selected, onToggle, disabled }) {
  const rows = seats.reduce((acc, seat) => {
    (acc[seat.rowLabel] ??= []).push(seat);
    return acc;
  }, {});

  const rowKeys = Object.keys(rows).sort((a, b) => Number(a) - Number(b));

  return (
    <div className="seatmap">
      <div className="stage">STAGE</div>

      {rowKeys.map((row) => (
        <div className="seat-row" key={row}>
          <span className="row-label">{row}</span>
          {rows[row]
            .sort((a, b) => a.seatNumber - b.seatNumber)
            .map((seat) => {
              const isSelected = selected.includes(seat.id);
              const taken = seat.status !== 'AVAILABLE';
              return (
                <button
                  key={seat.id}
                  type="button"
                  className={[
                    'seat',
                    seat.status.toLowerCase(),
                    isSelected ? 'selected' : ''
                  ].join(' ')}
                  disabled={taken || disabled}
                  onClick={() => onToggle(seat.id)}
                  title={`Row ${seat.rowLabel} seat ${seat.seatNumber} · ${formatPrice(seat.priceCents)} · ${seat.status}`}
                >
                  {seat.seatNumber}
                </button>
              );
            })}
        </div>
      ))}

      <div className="legend">
        <span><i className="swatch available" /> Available</span>
        <span><i className="swatch selected" /> Selected</span>
        <span><i className="swatch held" /> Held by someone</span>
        <span><i className="swatch booked" /> Booked</span>
      </div>
    </div>
  );
}
