import { useState, useEffect, useCallback } from 'react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Trash2, Search } from 'lucide-react';
import { fetchAdminUsers, updateUserRole, deleteUser } from '@/api/admin';

interface AdminUser {
  id: string;
  email: string;
  name: string;
  role: 'USER' | 'ADMIN';
  createdAt: string;
}

interface PageData {
  content: AdminUser[];
  totalPages: number;
  number: number;
}

export function AdminUsersPage() {
  const [data, setData] = useState<PageData | null>(null);
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);

  const load = useCallback(async () => {
    try {
      const res = await fetchAdminUsers(page, 20, query || undefined);
      setData(res);
    } catch (err) {
      console.error('Failed to load users:', err);
      toast.error('사용자 목록 조회 실패');
    }
  }, [page, query]);

  useEffect(() => { load(); }, [load]);

  const handleRoleChange = async (userId: string, role: string) => {
    try {
      await updateUserRole(userId, role);
      await load();
      toast.success('역할이 변경되었습니다.');
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '역할 변경 실패');
    }
  };

  const handleDelete = async (userId: string, email: string) => {
    if (!confirm(`${email} 사용자를 삭제하시겠습니까?`)) return;
    try {
      await deleteUser(userId);
      await load();
      toast.success('사용자가 삭제되었습니다.');
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '삭제 실패');
    }
  };

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setPage(0);
    load();
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">사용자 관리</h1>
        <form onSubmit={handleSearch} className="flex items-center gap-2">
          <Input
            placeholder="이메일 또는 이름 검색"
            value={query}
            onChange={e => setQuery(e.target.value)}
            className="w-60"
          />
          <Button type="submit" variant="outline" size="icon">
            <Search className="h-4 w-4" />
          </Button>
        </form>
      </div>

      <div className="border rounded-lg">
        <table className="w-full text-sm">
          <thead className="bg-muted/50">
            <tr>
              <th className="text-left px-4 py-3 font-medium">이메일</th>
              <th className="text-left px-4 py-3 font-medium">이름</th>
              <th className="text-left px-4 py-3 font-medium">역할</th>
              <th className="text-left px-4 py-3 font-medium">가입일</th>
              <th className="px-4 py-3 w-16"></th>
            </tr>
          </thead>
          <tbody>
            {data?.content.map(user => (
              <tr key={user.id} className="border-t hover:bg-muted/30">
                <td className="px-4 py-3">{user.email}</td>
                <td className="px-4 py-3">{user.name}</td>
                <td className="px-4 py-3">
                  <Select value={user.role} onValueChange={v => v && handleRoleChange(user.id, v)}>
                    <SelectTrigger className="w-28 h-8 text-xs">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="USER">USER</SelectItem>
                      <SelectItem value="ADMIN">ADMIN</SelectItem>
                    </SelectContent>
                  </Select>
                </td>
                <td className="px-4 py-3 text-muted-foreground">
                  {new Date(user.createdAt).toLocaleDateString('ko-KR')}
                </td>
                <td className="px-4 py-3">
                  <Button variant="ghost" size="icon" className="h-8 w-8 text-destructive"
                    onClick={() => handleDelete(user.id, user.email)}>
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {data && data.totalPages > 1 && (
        <div className="flex justify-center gap-2">
          <Button variant="outline" size="sm" disabled={page === 0}
            onClick={() => setPage(p => p - 1)}>이전</Button>
          <span className="text-sm text-muted-foreground py-1.5">
            {page + 1} / {data.totalPages}
          </span>
          <Button variant="outline" size="sm" disabled={page >= data.totalPages - 1}
            onClick={() => setPage(p => p + 1)}>다음</Button>
        </div>
      )}
    </div>
  );
}
