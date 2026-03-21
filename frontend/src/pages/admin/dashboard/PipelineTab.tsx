import { useState, useEffect, useCallback } from 'react';
import { Card } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { dashboardApi } from '@/api/dashboard';

interface Trace {
  id: string;
  traceId: string;
  query: string;
  agentAction: string | null;
  totalLatency: number;
  createdAt: string;
}

interface PageData {
  content: Trace[];
  totalPages: number;
  number: number;
}

export function PipelineTab() {
  const [data, setData] = useState<PageData | null>(null);
  const [page, setPage] = useState(0);

  const load = useCallback(async () => {
    try {
      setData(await dashboardApi.traces(page, 20));
    } catch { /* */ }
  }, [page]);

  useEffect(() => { load(); }, [load]);

  const formatTime = (ms: number) => ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`;

  return (
    <div className="space-y-4">
      <Card className="p-4">
        <h3 className="text-sm font-medium mb-3">최근 파이프라인 요청</h3>
        <table className="w-full text-sm">
          <thead className="bg-muted/50">
            <tr>
              <th className="text-left px-4 py-2 font-medium">시간</th>
              <th className="text-left px-4 py-2 font-medium">질문</th>
              <th className="text-left px-4 py-2 font-medium">결정</th>
              <th className="text-right px-4 py-2 font-medium">소요 시간</th>
            </tr>
          </thead>
          <tbody>
            {data?.content.map(t => (
              <tr key={t.id} className="border-t hover:bg-muted/30">
                <td className="px-4 py-2 text-xs text-muted-foreground whitespace-nowrap">
                  {new Date(t.createdAt).toLocaleString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                </td>
                <td className="px-4 py-2 max-w-80 truncate" title={t.query}>{t.query}</td>
                <td className="px-4 py-2">
                  {t.agentAction && (
                    <Badge variant="outline" className="text-[10px]">{t.agentAction}</Badge>
                  )}
                </td>
                <td className={`px-4 py-2 text-right font-mono text-xs ${t.totalLatency > 5000 ? 'text-destructive' : ''}`}>
                  {formatTime(t.totalLatency)}
                </td>
              </tr>
            ))}
            {(!data || data.content.length === 0) && (
              <tr><td colSpan={4} className="text-center py-8 text-muted-foreground">데이터 없음</td></tr>
            )}
          </tbody>
        </table>
      </Card>

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
