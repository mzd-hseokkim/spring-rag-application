import { useState, useEffect, useCallback } from 'react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Trash2 } from 'lucide-react';
import {
  fetchAdminGenerations,
  deleteAdminGeneration,
  fetchAdminQuestionnaires,
  deleteAdminQuestionnaire,
} from '@/api/admin';

interface JobItem {
  id: string;
  title: string | null;
  status: string;
  ownerEmail: string | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

interface PageData {
  content: JobItem[];
  totalPages: number;
  number: number;
}

type TabType = 'generation' | 'questionnaire';

const GENERATION_STATUSES = ['', 'DRAFT', 'PLANNING', 'ANALYZING', 'MAPPING', 'READY', 'GENERATING', 'REVIEWING', 'RENDERING', 'COMPLETE', 'FAILED'];
const QUESTIONNAIRE_STATUSES = ['', 'ANALYZING', 'GENERATING', 'RENDERING', 'COMPLETE', 'FAILED'];

const STATUS_COLORS: Record<string, string> = {
  COMPLETE: 'text-green-600',
  FAILED: 'text-red-500',
  GENERATING: 'text-blue-500',
  RENDERING: 'text-blue-500',
  ANALYZING: 'text-yellow-600',
  DRAFT: 'text-muted-foreground',
};

export function AdminGenerationsPage() {
  const [tab, setTab] = useState<TabType>('generation');
  const [data, setData] = useState<PageData | null>(null);
  const [page, setPage] = useState(0);
  const [statusFilter, setStatusFilter] = useState('');

  const load = useCallback(async () => {
    try {
      const res = tab === 'generation'
        ? await fetchAdminGenerations(page, 20, statusFilter || undefined)
        : await fetchAdminQuestionnaires(page, 20, statusFilter || undefined);
      setData(res);
    } catch {
      toast.error('목록을 불러오는데 실패했습니다.');
    }
  }, [tab, page, statusFilter]);

  useEffect(() => { load(); }, [load]);

  const handleTabChange = (newTab: TabType) => {
    setTab(newTab);
    setPage(0);
    setStatusFilter('');
  };

  const handleDelete = async (id: string) => {
    if (!confirm('정말 삭제하시겠습니까?')) return;
    try {
      if (tab === 'generation') {
        await deleteAdminGeneration(id);
      } else {
        await deleteAdminQuestionnaire(id);
      }
      toast.success('삭제되었습니다.');
      load();
    } catch {
      toast.error('삭제에 실패했습니다.');
    }
  };

  const statuses = tab === 'generation' ? GENERATION_STATUSES : QUESTIONNAIRE_STATUSES;

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">생성 관리</h1>

      <div className="flex items-center gap-4">
        <div className="flex gap-1">
          {(['generation', 'questionnaire'] as const).map(t => (
            <button key={t} onClick={() => handleTabChange(t)}
              className={`px-4 py-1.5 text-sm rounded-md border ${tab === t ? 'bg-primary text-primary-foreground' : 'hover:bg-muted'}`}>
              {t === 'generation' ? '문서 생성' : '질문 생성'}
            </button>
          ))}
        </div>
        <select value={statusFilter} onChange={e => { setStatusFilter(e.target.value); setPage(0); }}
          className="px-3 py-1.5 text-sm rounded-md border bg-background">
          <option value="">전체 상태</option>
          {statuses.filter(Boolean).map(s => (
            <option key={s} value={s}>{s}</option>
          ))}
        </select>
      </div>

      <table className="w-full text-sm">
        <thead className="bg-muted/50">
          <tr>
            <th className="text-left px-4 py-2 font-medium">제목</th>
            <th className="text-left px-4 py-2 font-medium">소유자</th>
            <th className="text-left px-4 py-2 font-medium">상태</th>
            <th className="text-left px-4 py-2 font-medium">생성일</th>
            <th className="text-right px-4 py-2 font-medium">작업</th>
          </tr>
        </thead>
        <tbody>
          {data?.content.map(job => (
            <tr key={job.id} className="border-t hover:bg-muted/30">
              <td className="px-4 py-2">
                <span>{job.title || '(제목 없음)'}</span>
                {job.errorMessage && (
                  <p className="text-xs text-red-400 mt-0.5 truncate max-w-md" title={job.errorMessage}>
                    {job.errorMessage}
                  </p>
                )}
              </td>
              <td className="px-4 py-2 text-muted-foreground">{job.ownerEmail ?? '-'}</td>
              <td className="px-4 py-2">
                <span className={`text-xs font-medium ${STATUS_COLORS[job.status] ?? ''}`}>
                  {job.status}
                </span>
              </td>
              <td className="px-4 py-2 text-muted-foreground text-xs">
                {new Date(job.createdAt).toLocaleString('ko-KR')}
              </td>
              <td className="px-4 py-2 text-right">
                <Button variant="ghost" size="icon" onClick={() => handleDelete(job.id)}
                  className="h-7 w-7 text-muted-foreground hover:text-red-500">
                  <Trash2 className="h-3.5 w-3.5" />
                </Button>
              </td>
            </tr>
          ))}
          {(!data || data.content.length === 0) && (
            <tr><td colSpan={5} className="text-center py-12 text-muted-foreground">데이터 없음</td></tr>
          )}
        </tbody>
      </table>

      {data && data.totalPages > 1 && (
        <div className="flex justify-center gap-2 pt-2">
          <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(p => p - 1)}>이전</Button>
          <span className="text-sm leading-8 text-muted-foreground">{page + 1} / {data.totalPages}</span>
          <Button variant="outline" size="sm" disabled={page >= data.totalPages - 1} onClick={() => setPage(p => p + 1)}>다음</Button>
        </div>
      )}
    </div>
  );
}
