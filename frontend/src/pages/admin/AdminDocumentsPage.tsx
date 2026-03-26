import { useState, useEffect, useCallback } from 'react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { Select, SelectTrigger, SelectValue, SelectContent, SelectItem } from '@/components/ui/select';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';
import { Trash2, RotateCcw, Settings2, Save, Info } from 'lucide-react';
import {
  fetchAdminDocuments, updateDocumentPublic, deleteAdminDocument,
  reindexDocument, reindexAll,
  fetchChunkingSettings, updateChunkingSettings,
  fetchEmbeddingSettings, updateEmbeddingSettings,
  type ChunkingSettings, type EmbeddingSettings,
} from '@/api/admin';

interface AdminDocument {
  id: string;
  filename: string;
  status: string;
  chunkCount: number;
  isPublic: boolean;
  ownerEmail: string | null;
  createdAt: string;
}

interface PageData {
  content: AdminDocument[];
  totalPages: number;
  number: number;
}

const STATUS_STYLE: Record<string, string> = {
  COMPLETED: 'border-green-500 text-green-600',
  FAILED: 'border-red-500 text-red-600',
  PROCESSING: 'border-yellow-500 text-yellow-600',
  PENDING: 'text-muted-foreground',
};

export function AdminDocumentsPage() {
  const [data, setData] = useState<PageData | null>(null);
  const [page, setPage] = useState(0);
  const [showSettings, setShowSettings] = useState(false);
  const [chunkSettings, setChunkSettings] = useState<ChunkingSettings | null>(null);
  const [embedSettings, setEmbedSettings] = useState<EmbeddingSettings | null>(null);
  const [reindexingId, setReindexingId] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const res = await fetchAdminDocuments(page, 20);
      setData(res);
    } catch (err) {
      console.error('Failed to load documents:', err);
      toast.error('문서 목록 조회 실패');
    }
  }, [page]);

  useEffect(() => { load(); }, [load]);

  const handlePublicToggle = async (id: string, isPublic: boolean) => {
    try {
      await updateDocumentPublic(id, isPublic);
      await load();
    } catch (err) {
      console.error(err);
      toast.error('공용 설정 변경 실패');
    }
  };

  const handleDelete = async (id: string, filename: string) => {
    if (!confirm(`"${filename}" 문서를 삭제하시겠습니까?`)) return;
    try {
      await deleteAdminDocument(id);
      await load();
      toast.success('문서가 삭제되었습니다.');
    } catch (err) {
      console.error(err);
      toast.error('문서 삭제 실패');
    }
  };

  const pollUntilDone = useCallback((targetId?: string) => {
    const interval = setInterval(async () => {
      try {
        const res = await fetchAdminDocuments(page, 20);
        setData(res);
        const processing = res.content.some((d: AdminDocument) =>
          (targetId ? d.id === targetId : true) &&
          (d.status === 'PROCESSING' || d.status === 'PENDING')
        );
        if (!processing) {
          clearInterval(interval);
          setReindexingId(null);
        }
      } catch {
        clearInterval(interval);
        setReindexingId(null);
      }
    }, 2000);
    return interval;
  }, [page]);

  const handleReindex = async (id: string, filename: string) => {
    if (!confirm(`"${filename}" 문서를 재인덱싱하시겠습니까?`)) return;
    try {
      setReindexingId(id);
      await reindexDocument(id);
      toast.success('재인덱싱이 시작되었습니다.');
      pollUntilDone(id);
    } catch (err) {
      console.error(err);
      setReindexingId(null);
      toast.error('재인덱싱 실패');
    }
  };

  const handleReindexAll = async () => {
    if (!confirm('모든 문서를 재인덱싱하시겠습니까? 시간이 오래 걸릴 수 있습니다.')) return;
    try {
      setReindexingId('all');
      await reindexAll();
      toast.success('전체 재인덱싱이 시작되었습니다.');
      pollUntilDone();
    } catch (err) {
      console.error(err);
      setReindexingId(null);
      toast.error('전체 재인덱싱 실패');
    }
  };

  const loadSettings = async () => {
    try {
      const [cs, es] = await Promise.all([fetchChunkingSettings(), fetchEmbeddingSettings()]);
      setChunkSettings(cs);
      setEmbedSettings(es);
    } catch (err) {
      console.error(err);
      toast.error('설정 로드 실패');
    }
  };

  const handleToggleSettings = () => {
    if (!showSettings) loadSettings();
    setShowSettings(v => !v);
  };

  const handleSaveSettings = async () => {
    try {
      if (chunkSettings) await updateChunkingSettings(chunkSettings);
      if (embedSettings) await updateEmbeddingSettings(embedSettings);
      toast.success('설정이 저장되었습니다.');
    } catch (err) {
      console.error(err);
      toast.error('설정 저장 실패');
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">문서 관리</h1>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={handleToggleSettings}>
            <Settings2 className="h-4 w-4 mr-1" />
            청크 설정
          </Button>
          <Button variant="outline" size="sm" onClick={handleReindexAll}>
            <RotateCcw className="h-4 w-4 mr-1" />
            전체 재인덱싱
          </Button>
        </div>
      </div>

      {/* 청크 & 임베딩 설정 패널 */}
      {showSettings && chunkSettings && embedSettings && (
        <div className="border rounded-lg p-4 space-y-4 bg-muted/30">
          <h2 className="text-sm font-semibold">청크 전략 설정</h2>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <div className="space-y-1">
              <SettingLabel label="청크 모드" tooltip="문서를 청크로 분할하는 전략. semantic은 임베딩 기반 의미 분할, fixed는 고정 길이 분할." />
              <Select value={chunkSettings.mode}
                onValueChange={v => v && setChunkSettings({ ...chunkSettings, mode: v })}>
                <SelectTrigger className="h-8 w-full text-sm">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="semantic">Semantic</SelectItem>
                  <SelectItem value="fixed">Fixed</SelectItem>
                </SelectContent>
              </Select>
            </div>
            {chunkSettings.mode === 'fixed' && (
              <>
                <SettingField label="청크 크기" tooltip="각 청크의 최대 문자 수."
                  type="number" value={chunkSettings.fixedChunkSize}
                  onChange={v => setChunkSettings({ ...chunkSettings, fixedChunkSize: Number(v) })} />
                <SettingField label="오버랩" tooltip="인접 청크 간 겹치는 문자 수. 문맥 유실을 방지한다."
                  type="number" value={chunkSettings.fixedOverlap}
                  onChange={v => setChunkSettings({ ...chunkSettings, fixedOverlap: Number(v) })} />
              </>
            )}
            {chunkSettings.mode === 'semantic' && (
              <>
                <SettingField label="버퍼 크기" tooltip="각 문장 주변에 포함할 문장 수. 값이 클수록 문맥 파악이 정확하지만 느려진다."
                  type="number" value={chunkSettings.semanticBufferSize}
                  onChange={v => setChunkSettings({ ...chunkSettings, semanticBufferSize: Number(v) })} />
                <SettingField label="브레이크포인트 %" tooltip="분할 기준 백분위. 높을수록 적게 분할되어 큰 청크가 생긴다."
                  type="number" value={chunkSettings.semanticBreakpointPercentile}
                  onChange={v => setChunkSettings({ ...chunkSettings, semanticBreakpointPercentile: Number(v) })} />
                <SettingField label="최소 크기" tooltip="청크 최소 문자 수. 이보다 작은 청크는 인접 청크와 병합된다."
                  type="number" value={chunkSettings.semanticMinChunkSize}
                  onChange={v => setChunkSettings({ ...chunkSettings, semanticMinChunkSize: Number(v) })} />
                <SettingField label="최대 크기" tooltip="청크 최대 문자 수. 초과 시 고정 크기로 재분할된다."
                  type="number" value={chunkSettings.semanticMaxChunkSize}
                  onChange={v => setChunkSettings({ ...chunkSettings, semanticMaxChunkSize: Number(v) })} />
              </>
            )}
            <SettingField label="자식 청크 크기" tooltip="검색용 자식 청크의 최대 문자 수. 실제 벡터 검색에 사용되는 단위."
              type="number" value={chunkSettings.childChunkSize}
              onChange={v => setChunkSettings({ ...chunkSettings, childChunkSize: Number(v) })} />
            <SettingField label="자식 오버랩" tooltip="자식 청크 간 겹치는 문자 수. 문맥이 끊기지 않도록 슬라이딩 윈도우 역할을 한다."
              type="number" value={chunkSettings.childOverlap}
              onChange={v => setChunkSettings({ ...chunkSettings, childOverlap: Number(v) })} />
          </div>

          <h2 className="text-sm font-semibold mt-4">임베딩 설정</h2>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <SettingField label="배치 크기" tooltip="한 번에 임베딩할 청크 수. 크면 API 호출 횟수가 줄지만 메모리 사용량이 늘어난다."
              type="number" value={embedSettings.batchSize}
              onChange={v => setEmbedSettings({ ...embedSettings, batchSize: Number(v) })} />
            <SettingField label="병렬 수" tooltip="동시에 임베딩을 처리할 스레드 수. 서버 리소스에 맞게 조정한다."
              type="number" value={embedSettings.concurrency}
              onChange={v => setEmbedSettings({ ...embedSettings, concurrency: Number(v) })} />
          </div>

          <div className="flex justify-end">
            <Button size="sm" onClick={handleSaveSettings}>
              <Save className="h-4 w-4 mr-1" />
              설정 저장
            </Button>
          </div>
        </div>
      )}

      <div className="border rounded-lg">
        <table className="w-full text-sm">
          <thead className="bg-muted/50">
            <tr>
              <th className="text-left px-4 py-3 font-medium">파일명</th>
              <th className="text-left px-4 py-3 font-medium">소유자</th>
              <th className="text-center px-4 py-3 font-medium">공용</th>
              <th className="text-left px-4 py-3 font-medium">상태</th>
              <th className="text-right px-4 py-3 font-medium">청크</th>
              <th className="text-left px-4 py-3 font-medium">등록일</th>
              <th className="px-4 py-3 w-24"></th>
            </tr>
          </thead>
          <tbody>
            {data?.content.map(doc => (
              <tr key={doc.id} className="border-t hover:bg-muted/30">
                <td className="px-4 py-3 max-w-48 truncate" title={doc.filename}>{doc.filename}</td>
                <td className="px-4 py-3 text-muted-foreground">{doc.ownerEmail || '-'}</td>
                <td className="px-4 py-3 text-center">
                  <Checkbox
                    checked={doc.isPublic}
                    onCheckedChange={(checked) => handlePublicToggle(doc.id, checked === true)}
                  />
                </td>
                <td className="px-4 py-3">
                  <Badge variant="outline" className={`text-[10px] ${STATUS_STYLE[doc.status] || ''}`}>
                    {doc.status}
                  </Badge>
                </td>
                <td className="px-4 py-3 text-right text-muted-foreground">{doc.chunkCount}</td>
                <td className="px-4 py-3 text-muted-foreground">
                  {new Date(doc.createdAt).toLocaleDateString('ko-KR')}
                </td>
                <td className="px-4 py-3">
                  <div className="flex gap-1">
                    <Button variant="ghost" size="icon" className="h-8 w-8"
                      disabled={doc.status !== 'COMPLETED' || reindexingId !== null}
                      title="재인덱싱"
                      onClick={() => handleReindex(doc.id, doc.filename)}>
                      <RotateCcw className={`h-4 w-4 ${reindexingId === doc.id || (reindexingId === 'all' && doc.status === 'PROCESSING') ? 'animate-spin' : ''}`} />
                    </Button>
                    <Button variant="ghost" size="icon" className="h-8 w-8 text-destructive"
                      onClick={() => handleDelete(doc.id, doc.filename)}>
                      <Trash2 className="h-4 w-4" />
                    </Button>
                  </div>
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

function SettingLabel({ label, tooltip }: { label: string; tooltip: string }) {
  return (
    <div className="flex items-center gap-1">
      <span className="text-xs text-muted-foreground">{label}</span>
      <Tooltip>
        <TooltipTrigger render={<button type="button" className="inline-flex" />}>
          <Info className="h-3 w-3 text-muted-foreground/60" />
        </TooltipTrigger>
        <TooltipContent>{tooltip}</TooltipContent>
      </Tooltip>
    </div>
  );
}

function SettingField({ label, tooltip, value, onChange, type = 'text' }: {
  label: string;
  tooltip: string;
  value: string | number;
  onChange: (v: string) => void;
  type?: string;
}) {
  return (
    <div className="space-y-1">
      <SettingLabel label={label} tooltip={tooltip} />
      <Input type={type} value={value} className="h-8 text-sm"
        onChange={e => onChange(e.target.value)} />
    </div>
  );
}
