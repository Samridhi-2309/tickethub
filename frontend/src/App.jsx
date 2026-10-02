import { BrowserRouter, Routes, Route, Link, Navigate, useLocation } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import Login from './pages/Login';
import Events from './pages/Events';
import EventSeats from './pages/EventSeats';
import Checkout from './pages/Checkout';
import MyBookings from './pages/MyBookings';

/**
 * Client-side route guard. This is a convenience, not a security
 * control — every protected endpoint is enforced server-side by the
 * JWT filter. Removing this component would make the UI ugly, not
 * insecure.
 */
function RequireAuth({ children }) {
  const { user, checking } = useAuth();
  const location = useLocation();

  if (checking) return null;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return children;
}

function Header() {
  const { user, logout } = useAuth();
  return (
    <header>
      <Link to="/" className="brand">TicketHub</Link>
      <nav>
        {user ? (
          <>
            <Link to="/bookings">My bookings</Link>
            <span className="muted small">{user.email}</span>
            <button className="link" onClick={logout}>Sign out</button>
          </>
        ) : (
          <Link to="/login">Sign in</Link>
        )}
      </nav>
    </header>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Header />
        <main>
          <Routes>
            <Route path="/" element={<Events />} />
            <Route path="/login" element={<Login />} />
            <Route path="/events/:eventId" element={<EventSeats />} />
            <Route path="/checkout" element={<RequireAuth><Checkout /></RequireAuth>} />
            <Route path="/bookings" element={<RequireAuth><MyBookings /></RequireAuth>} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </main>
      </AuthProvider>
    </BrowserRouter>
  );
}
