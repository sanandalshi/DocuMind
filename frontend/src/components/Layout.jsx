import { Link, NavLink } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { logout } from '../api/client.js';
import { useAuth } from '../useAuth.js';

export default function Layout({ children }) {
  const auth = useAuth();
  const queryClient = useQueryClient();
  return (
    <div className="app">
      <header className="topbar">
        <Link to="/" className="brand">DocuMind</Link>
        <nav>
          <NavLink to="/" end>Documents</NavLink>
          {auth?.user?.role === 'ADMIN' && <NavLink to="/admin">Admin</NavLink>}
        </nav>
        <div className="user">
          <span className="muted">{auth?.user?.name}</span>
          <button className="ghost" onClick={() => { queryClient.clear(); logout(); }}>Log out</button>
        </div>
      </header>
      <main className="content">{children}</main>
    </div>
  );
}
