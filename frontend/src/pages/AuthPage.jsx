import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { login, register } from '../api/client.js';

export default function AuthPage({ mode }) {
  const isRegister = mode === 'register';
  const navigate = useNavigate();
  const [form, setForm] = useState({ name: '', email: '', password: '' });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const update = (field) => (e) => setForm({ ...form, [field]: e.target.value });

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      if (isRegister) await register(form.name, form.email, form.password);
      else await login(form.email, form.password);
      navigate('/');
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth">
      <form className="card auth-card" onSubmit={submit}>
        <h1>DocuMind</h1>
        <p className="muted">{isRegister ? 'Create your account' : 'Chat with your documents'}</p>
        {isRegister && (
          <label>
            Name
            <input value={form.name} onChange={update('name')} required maxLength={100} autoComplete="name" />
          </label>
        )}
        <label>
          Email
          <input type="email" value={form.email} onChange={update('email')} required autoComplete="email" />
        </label>
        <label>
          Password
          <input
            type="password"
            value={form.password}
            onChange={update('password')}
            required
            minLength={isRegister ? 8 : undefined}
            autoComplete={isRegister ? 'new-password' : 'current-password'}
          />
        </label>
        {error && <div className="error" role="alert">{error}</div>}
        <button className="primary" disabled={busy}>
          {busy ? 'Please wait…' : isRegister ? 'Create account' : 'Log in'}
        </button>
        <p className="muted small">
          {isRegister ? 'Already have an account? ' : 'New here? '}
          <Link to={isRegister ? '/login' : '/register'}>{isRegister ? 'Log in' : 'Create an account'}</Link>
        </p>
      </form>
    </div>
  );
}
