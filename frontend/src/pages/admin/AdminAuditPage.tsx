import { useState, useEffect, useCallback } from 'react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem } from '@/components/ui/select';
import { fetchAuditLogs } from '@/api/admin';

interface AuditLogEntry {
  id: string;
  userId: string | null;
  userEmail: string | null;
  action: string;
  resource: string | null;
  resourceId: string | null;
  detail: Record<string, unknown> | null;
  ipAddress: string | null;
  createdAt: string;
}

interface PageData {
  content: AuditLogEntry[];
  totalPages: number;
  number: number;
}

const ACTION_STYLE: Record<string, string> = {
  LOGIN_SUCCESS: 'border-green-500 text-green-600',
  LOGIN_FAILED: 'border-red-500 text-red-600',
  REGISTER: 'border-blue-500 text-blue-600',
  LOGOUT: 'text-muted-foreground',
  RATE_LIMIT_EXCEEDED: 'border-red-500 text-red-600',
  DELETE_USER: 'border-red-500 text-red-600',
  DELETE_DOCUMENT: 'border-red-500 text-red-600',
  DELETE_CONVERSATION: 'border-red-500 text-red-600',
  CHANGE_ROLE: 'border-yellow-500 text-yellow-600',
  TOGGLE_PUBLIC: 'border-yellow-500 text-yellow-600',
  REINDEX: 'border-blue-500 text-blue-600',
  REINDEX_ALL: 'border-blue-500 text-blue-600',
  UPDATE_SETTINGS: 'border-yellow-500 text-yellow-600',
};

const ACTIONS = [
  'LOGIN_SUCCESS', 'LOGIN_FAILED', 'REGISTER', 'LOGOUT',
  'CHANGE_ROLE', 'DELETE_USER',
  'DELETE_DOCUMENT', 'TOGGLE_PUBLIC', 'REINDEX', 'REINDEX_ALL',
  'DELETE_CONVERSATION', 'UPDATE_SETTINGS', 'RATE_LIMIT_EXCEEDED',
];

export function AdminAuditPage() {
  const [data, setData] = useState<PageData | null>(null);
  const [page, setPage] = useState(0);
  const [actionFilter, setActionFilter] = useState<string>('');

  const load = useCallback(async () => {
    try {
      const res = await fetchAuditLogs(page, 30, actionFilter || undefined);
      setData(res);
    } catch (err) {
      console.error(err);
      toast.error('감사 로그 조회 실패');
    }
  }, [page, actionFilter]);

  useEffect(() => { load(); }, [load]);

  const formatDate = (dateStr: string) => {
    const d = new Date(dateStr);
    return d.toLocaleDateString('ko-KR') + ' ' + d.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">감사 로그</h1>
        <Select value={actionFilter} onValueChange={v => { setActionFilter(v === '__all__' || v === null ? '' : v); setPage(0); }}>
          <SelectTrigger className="h-8 w-48 text-xs">
            <SelectValue placeholder="전체 액션" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="__all__">전체 액션</SelectItem>
            {ACTIONS.map(a => (
              <SelectItem key={a} value={a}>{a}</SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <div className="border rounded-lg">
        <table className="w-full text-sm">
          <thead className="bg-muted/50">
            <tr>
              <th className="text-left px-4 py-3 font-medium">시간</th>
              <th className="text-left px-4 py-3 font-medium">액션</th>
              <th className="text-left px-4 py-3 font-medium">사용자</th>
              <th className="text-left px-4 py-3 font-medium">대상</th>
              <th className="text-left px-4 py-3 font-medium">상세</th>
              <th className="text-left px-4 py-3 font-medium">IP</th>
            </tr>
          </thead>
          <tbody>
            {data?.content.map(log => (
              <tr key={log.id} className="border-t hover:bg-muted/30">
                <td className="px-4 py-2 text-xs text-muted-foreground whitespace-nowrap">
                  {formatDate(log.createdAt)}
                </td>
                <td className="px-4 py-2">
                  <Badge variant="outline" className={`text-[10px] ${ACTION_STYLE[log.action] || ''}`}>
                    {log.action}
                  </Badge>
                </td>
                <td className="px-4 py-2 text-xs text-muted-foreground">
                  {log.userEmail || log.userId?.substring(0, 8) || '-'}
                </td>
                <td className="px-4 py-2 text-xs text-muted-foreground">
                  {log.resource && (
                    <span>{log.resource}{log.resourceId ? ` / ${log.resourceId.substring(0, 8)}` : ''}</span>
                  )}
                </td>
                <td className="px-4 py-2 text-xs text-muted-foreground max-w-48 truncate">
                  {log.detail ? JSON.stringify(log.detail) : ''}
                </td>
                <td className="px-4 py-2 text-xs text-muted-foreground">
                  {log.ipAddress || '-'}
                </td>
              </tr>
            ))}
            {data?.content.length === 0 && (
              <tr><td colSpan={6} className="text-center py-8 text-muted-foreground">로그가 없습니다.</td></tr>
            )}
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
