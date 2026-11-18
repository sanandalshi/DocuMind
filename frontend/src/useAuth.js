import { useEffect, useState } from 'react';
import { getAuth, onAuthChange } from './api/client.js';

export function useAuth() {
  const [auth, setAuthState] = useState(getAuth());
  useEffect(() => onAuthChange(setAuthState), []);
  return auth;
}
