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

interface GenTrace {
  id: string;
  jobId: string;
  jobType: string;
  stepName: string;
  status: string;
  startedAt: string;
  completedAt: string | null;
  durationMs: number | null;
  errorMessage: string | null;
}

interface PageData<T> {
  content: T[];
  totalPages: number;
  number: number;
}

const STEP_LABELS: Record<string, string> = {
  OUTLINE: '아웃라인 생성',
  EXTRACT_OUTLINE: '목차 추출',
  MAP_REQUIREMENTS: '요구사항 매핑',
  SECTION_GENERATE: '섹션 생성',
  RENDER: '렌더링',
  ANALYZE: '문서 분석',
  GENERATE: '질문 생성',
};

const STATUS_STYLES: Record<string, string> = {
  COMPLETED: 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400',
  FAILED: 'bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-400',
  RUNNING: 'bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400',
};

export function PipelineTab() {
  const [data, setData] = useState<PageData<Trace> | null>(null);
  const [page, setPage] = useState(0);
  const [genData, setGenData] = useState<PageData<GenTrace> | null>(null);
  const [genPage, setGenPage] = useState(0);

  const load = useCallback(async () => {
    try {
      setData(await dashboardApi.traces(page, 20));
    } catch { /* */ }
  }, [page]);

  const loadGen = useCallback(async () => {
    try {
      setGenData(await dashboardApi.generationTraces(genPage, 20));
    } catch { /* */ }
  }, [genPage]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => { loadGen(); }, [loadGen]);

  const formatTime = (ms: number) => ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`;

  return (
    <div className="space-y-6">
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
        {data && data.totalPages > 1 && (
          <div className="flex justify-center gap-2 pt-3">
            <Button variant="outline" size="sm" disabled={page === 0}
              onClick={() => setPage(p => p - 1)}>이전</Button>
            <span className="text-sm text-muted-foreground py-1.5">{page + 1} / {data.totalPages}</span>
            <Button variant="outline" size="sm" disabled={page >= data.totalPages - 1}
              onClick={() => setPage(p => p + 1)}>다음</Button>
          </div>
        )}
      </Card>

      <Card className="p-4">
        <h3 className="text-sm font-medium mb-3">생성 작업 트레이스</h3>
        <table className="w-full text-sm">
          <thead className="bg-muted/50">
            <tr>
              <th className="text-left px-4 py-2 font-medium">시간</th>
              <th className="text-left px-4 py-2 font-medium">유형</th>
              <th className="text-left px-4 py-2 font-medium">단계</th>
              <th className="text-left px-4 py-2 font-medium">상태</th>
              <th className="text-right px-4 py-2 font-medium">소요 시간</th>
              <th className="text-left px-4 py-2 font-medium">작업 ID</th>
            </tr>
          </thead>
          <tbody>
            {genData?.content.map(t => (
              <tr key={t.id} className="border-t hover:bg-muted/30">
                <td className="px-4 py-2 text-xs text-muted-foreground whitespace-nowrap">
                  {new Date(t.startedAt).toLocaleString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                </td>
                <td className="px-4 py-2">
                  <Badge variant="outline" className="text-[10px]">
                    {t.jobType === 'GENERATION' ? '문서' : '질문'}
                  </Badge>
                </td>
                <td className="px-4 py-2">{STEP_LABELS[t.stepName] ?? t.stepName}</td>
                <td className="px-4 py-2">
                  <span className={`text-[10px] font-medium px-1.5 py-0.5 rounded ${STATUS_STYLES[t.status] ?? ''}`}>
                    {t.status}
                  </span>
                  {t.errorMessage && (
                    <p className="text-xs text-red-400 mt-0.5 truncate max-w-xs" title={t.errorMessage}>
                      {t.errorMessage}
                    </p>
                  )}
                </td>
                <td className={`px-4 py-2 text-right font-mono text-xs ${t.durationMs && t.durationMs > 30000 ? 'text-yellow-600' : ''} ${t.status === 'FAILED' ? 'text-destructive' : ''}`}>
                  {t.durationMs != null ? formatTime(t.durationMs) : <span className="text-muted-foreground">-</span>}
                </td>
                <td className="px-4 py-2 text-xs text-muted-foreground font-mono">
                  {t.jobId.substring(0, 8)}
                </td>
              </tr>
            ))}
            {(!genData || genData.content.length === 0) && (
              <tr><td colSpan={6} className="text-center py-8 text-muted-foreground">데이터 없음</td></tr>
            )}
          </tbody>
        </table>
        {genData && genData.totalPages > 1 && (
          <div className="flex justify-center gap-2 pt-3">
            <Button variant="outline" size="sm" disabled={genPage === 0}
              onClick={() => setGenPage(p => p - 1)}>이전</Button>
            <span className="text-sm text-muted-foreground py-1.5">{genPage + 1} / {genData.totalPages}</span>
            <Button variant="outline" size="sm" disabled={genPage >= genData.totalPages - 1}
              onClick={() => setGenPage(p => p + 1)}>다음</Button>
          </div>
        )}
      </Card>
    </div>
  );
}
