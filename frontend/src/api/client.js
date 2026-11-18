import { readSSE } from './sse.js';

const BASE = '/api/v1';
const STORAGE_KEY = 'documind.auth';

export class ApiError extends Error {
  constructor(message, status, code) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

let auth = load();
const listeners = new Set();

function load() {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY)) || null;
  } catch {
    return null;
  }
}

export function getAuth() {
  return auth;
}

export function setAuth(next) {
  auth = next;
  try {
    if (next) localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    else localStorage.removeItem(STORAGE_KEY);
  } catch {
    /* storage unavailable: session only */
  }
  listeners.forEach((fn) => fn(auth));
}

export function onAuthChange(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

let refreshing = null;

/** Exchanges the refresh token once, even if several requests hit 401 together. */
async function refreshTokens() {
  if (!auth?.refreshToken) return false;
  refreshing ??= fetch(`${BASE}/auth/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: auth.refreshToken }),
  })
    .then(async (res) => {
      if (!res.ok) {
        setAuth(null);
        return false;
      }
      setAuth(await res.json());
      return true;
    })
    .finally(() => {
      refreshing = null;
    });
  return refreshing;
}

async function toError(res) {
  const body = await res.json().catch(() => null);
  return new ApiError(body?.message || `Request failed (${res.status})`, res.status, body?.error);
}

/** fetch() with the access token, one silent refresh on 401, and JSON errors as ApiError. */
export async function apiFetch(path, init = {}) {
  const send = () => {
    const headers = new Headers(init.headers);
    if (auth?.accessToken) headers.set('Authorization', `Bearer ${auth.accessToken}`);
    return fetch(`${BASE}${path}`, { ...init, headers });
  };
  let res;
  try {
    res = await send();
    if (res.status === 401 && auth?.refreshToken && (await refreshTokens())) res = await send();
  } catch (err) {
    if (err.name === 'AbortError') throw err;
    throw new ApiError('Cannot reach the server. Check your connection.', 0, 'NETWORK');
  }
  if (!res.ok) throw await toError(res);
  return res;
}

export async function api(path, { method = 'GET', body } = {}) {
  const init = { method };
  if (body instanceof FormData) init.body = body;
  else if (body !== undefined) {
    init.body = JSON.stringify(body);
    init.headers = { 'Content-Type': 'application/json' };
  }
  const res = await apiFetch(path, init);
  return res.status === 204 ? null : res.json();
}

/** Opens an SSE endpoint (GET or POST) and forwards each event. */
export async function stream(path, { method = 'GET', body, onEvent, signal }) {
  const res = await apiFetch(path, {
    method,
    signal,
    headers: { Accept: 'text/event-stream', ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  await readSSE(res, onEvent);
}

export async function login(email, password) {
  setAuth(await api('/auth/login', { method: 'POST', body: { email, password } }));
}

export async function register(name, email, password) {
  await api('/auth/register', { method: 'POST', body: { name, email, password } });
  await login(email, password);
}

export function logout() {
  setAuth(null);
}
