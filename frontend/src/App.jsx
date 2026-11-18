import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './useAuth.js';
import Layout from './components/Layout.jsx';
import AuthPage from './pages/AuthPage.jsx';
import DocumentsPage from './pages/DocumentsPage.jsx';
import ChatPage from './pages/ChatPage.jsx';
import AdminPage from './pages/AdminPage.jsx';

function Protected({ children, admin = false }) {
  const auth = useAuth();
  if (!auth) return <Navigate to="/login" replace />;
  if (admin && auth.user?.role !== 'ADMIN') return <Navigate to="/" replace />;
  return <Layout>{children}</Layout>;
}

export default function App() {
  const auth = useAuth();
  return (
    <Routes>
      <Route path="/login" element={auth ? <Navigate to="/" replace /> : <AuthPage mode="login" />} />
      <Route path="/register" element={auth ? <Navigate to="/" replace /> : <AuthPage mode="register" />} />
      <Route path="/" element={<Protected><DocumentsPage /></Protected>} />
      <Route path="/chats/:chatId" element={<Protected><ChatPage /></Protected>} />
      <Route path="/admin" element={<Protected admin><AdminPage /></Protected>} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
