import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client.js';

export default function AdminPage() {
  const queryClient = useQueryClient();
  const users = useQuery({ queryKey: ['admin-users'], queryFn: () => api('/admin/users') });
  const toggle = useMutation({
    mutationFn: (u) => api(`/admin/users/${u.id}`, { method: 'PATCH', body: { enabled: !u.enabled } }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin-users'] }),
  });

  return (
    <div className="page">
      <h1>Users</h1>
      <p className="muted">Usage per account. Document contents are never shown here.</p>
      {users.isLoading && <p className="muted">Loading…</p>}
      {users.isError && <div className="error">{users.error.message}</div>}
      {users.data && (
        <div className="table-wrap">
          <table>
            <thead>
              <tr><th>Name</th><th>Email</th><th>Role</th><th className="num">Documents</th><th className="num">Questions</th><th>Status</th><th /></tr>
            </thead>
            <tbody>
              {users.data.map((u) => (
                <tr key={u.id}>
                  <td>{u.name}</td>
                  <td>{u.email}</td>
                  <td>{u.role}</td>
                  <td className="num">{u.documents}</td>
                  <td className="num">{u.questions}</td>
                  <td>{u.enabled ? 'Active' : 'Disabled'}</td>
                  <td>
                    {u.role !== 'ADMIN' && (
                      <button className="ghost" onClick={() => toggle.mutate(u)}>{u.enabled ? 'Disable' : 'Enable'}</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
