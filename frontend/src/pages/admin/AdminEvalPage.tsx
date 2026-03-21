import React, { useState, useEffect, useCallback } from 'react';
import { toast } from 'sonner';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Card } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';
import { Trash2, Play, Plus, ChevronDown, ChevronUp, Download } from 'lucide-react';
import { fetchEvalRuns, fetchEvalQuestions, fetchQuestionDetail, generateEval, executeEvalRun, deleteEvalRun } from '@/api/eval';
import { fetchDocuments } from '@/api/client';
import type { Document } from '@/types';

interface EvalRun {
  id: string;
  name: string;
  status: string;
  totalQuestions: number;
  completedQuestions: number;
  avgFaithfulness: number | null;
  avgRelevance: number | null;
  avgCorrectness: number | null;
  createdAt: string;
}

interface EvalQuestion {
  id: string;
  question: string;
  questionType: string;
  faithfulness: number | null;
  relevance: number | null;
  correctness: number | null;
  judgeComment: string | null;
  status: string;
}

interface EvalQuestionDetail {
  id: string;
  question: string;
  expectedAnswer: string;
  questionType: string;
  actualResponse: string | null;
  retrievedContext: string | null;
  faithfulness: number | null;
  relevance: number | null;
  correctness: number | null;
  judgeComment: string | null;
  status: string;
}

const STATUS_STYLE: Record<string, string> = {
  PENDING: 'text-muted-foreground',
  GENERATING: 'text-yellow-600',
  READY: 'text-blue-600',
  RUNNING: 'text-yellow-600',
  COMPLETED: 'text-green-600',
  FAILED: 'text-red-600',
};

function scoreColor(value: number | null | undefined): string {
  if (value == null) return '';
  if (value >= 4.5) return 'text-blue-600';
  if (value >= 4) return 'text-blue-500';
  if (value >= 3) return 'text-yellow-600';
  if (value >= 2) return 'text-orange-500';
  return 'text-red-600';
}

function ScoreCard({ label, value }: { label: string; value: number | null }) {
  const color = scoreColor(value);
  return (
    <Card className="p-3 text-center">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className={`text-xl font-bold mt-1 ${color}`}>{value != null ? value.toFixed(1) : '-'} <span className="text-xs font-normal text-muted-foreground">/ 5</span></p>
    </Card>
  );
}

export function AdminEvalPage() {
  const [runs, setRuns] = useState<EvalRun[]>([]);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [expandedQId, setExpandedQId] = useState<string | null>(null);
  const [questionDetail, setQuestionDetail] = useState<EvalQuestionDetail | null>(null);
  const [questions, setQuestions] = useState<EvalQuestion[]>([]);
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [docs, setDocs] = useState<Document[]>([]);
  const [selectedDocs, setSelectedDocs] = useState<Set<string>>(new Set());
  const [qPerChunk, setQPerChunk] = useState(3);

  const loadRuns = useCallback(async () => {
    try { setRuns(await fetchEvalRuns()); } catch { toast.error('평가 목록 조회 실패'); }
  }, []);

  useEffect(() => { loadRuns(); }, [loadRuns]);

  // 진행 중인 실행이 있으면 3초 폴링
  useEffect(() => {
    const running = runs.some(r => r.status === 'GENERATING' || r.status === 'RUNNING');
    if (!running) return;
    const timer = setInterval(loadRuns, 3000);
    return () => clearInterval(timer);
  }, [runs, loadRuns]);

  const handleExpand = async (id: string) => {
    if (expandedId === id) { setExpandedId(null); return; }
    try {
      setQuestions(await fetchEvalQuestions(id));
      setExpandedId(id);
    } catch { toast.error('질문 조회 실패'); }
  };

  const handleCreate = async () => {
    if (!name.trim() || selectedDocs.size === 0) return;
    try {
      await generateEval(name.trim(), [...selectedDocs], qPerChunk);
      setCreating(false);
      setName('');
      setSelectedDocs(new Set());
      await loadRuns();
      toast.success('평가 생성 시작');
    } catch { toast.error('평가 생성 실패'); }
  };

  const handleExecute = async (id: string) => {
    try {
      await executeEvalRun(id);
      await loadRuns();
      toast.success('평가 실행 시작');
    } catch { toast.error('평가 실행 실패'); }
  };

  const handleDelete = async (id: string) => {
    if (!confirm('이 평가를 삭제하시겠습니까?')) return;
    try {
      await deleteEvalRun(id);
      if (expandedId === id) setExpandedId(null);
      await loadRuns();
    } catch { toast.error('삭제 실패'); }
  };

  const handleDownload = async (run: EvalRun) => {
    try {
      const qs = await fetchEvalQuestions(run.id);
      // 각 질문의 상세를 fetch
      const details = await Promise.all(qs.map((q: EvalQuestion) => fetchQuestionDetail(q.id)));

      const csvRows: string[] = [];
      const esc = (v: string) => `"${v.replace(/"/g, '""')}"`;
      csvRows.push(['질문', '유형', '기대 답변', '실제 RAG 응답', '검색 컨텍스트', 'Faithfulness', 'Relevance', 'Correctness', '심사 코멘트'].map(esc).join(','));
      for (const d of details) {
        csvRows.push([
          d.question, d.questionType, d.expectedAnswer || '',
          d.actualResponse || '', d.retrievedContext || '',
          d.faithfulness?.toString() || '', d.relevance?.toString() || '', d.correctness?.toString() || '',
          d.judgeComment || '',
        ].map(esc).join(','));
      }

      const bom = '\uFEFF';
      const blob = new Blob([bom + csvRows.join('\n')], { type: 'text/csv;charset=utf-8;' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${run.name.replace(/[^a-zA-Z0-9가-힣]/g, '_')}_eval.csv`;
      a.click();
      URL.revokeObjectURL(url);
    } catch {
      toast.error('다운로드 실패');
    }
  };

  const openCreateDialog = async () => {
    try { setDocs(await fetchDocuments()); } catch { /* */ }
    setCreating(true);
  };

  const toggleDoc = (id: string) => {
    setSelectedDocs(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">RAG 성능 평가</h1>
        <Button size="sm" onClick={openCreateDialog}><Plus className="h-4 w-4 mr-1" />새 평가 생성</Button>
      </div>

      {creating && (
        <Card className="p-4 space-y-3">
          <Input placeholder="평가 이름 (예: v1.0 벤치마크)" value={name} onChange={e => setName(e.target.value)} />
          <div>
            <p className="text-sm font-medium mb-2">대상 문서 선택</p>
            <div className="space-y-1 max-h-40 overflow-y-auto">
              {docs.filter(d => d.status === 'COMPLETED').map(doc => (
                <label key={doc.id} className="flex items-center gap-2 text-sm cursor-pointer py-0.5">
                  <Checkbox checked={selectedDocs.has(doc.id)} onCheckedChange={() => toggleDoc(doc.id)} />
                  {doc.filename}
                </label>
              ))}
            </div>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-sm">청크당 질문 수:</span>
            <Input type="number" className="w-20" value={qPerChunk} onChange={e => setQPerChunk(Number(e.target.value))} min={1} max={10} />
          </div>
          <div className="flex gap-2">
            <Button size="sm" onClick={handleCreate} disabled={!name.trim() || selectedDocs.size === 0}>생성</Button>
            <Button size="sm" variant="outline" onClick={() => setCreating(false)}>취소</Button>
          </div>
        </Card>
      )}

      <div className="space-y-2">
        {runs.map(run => (
          <div key={run.id}>
            <Card className="p-3">
              <div className="flex items-center gap-3 cursor-pointer" onClick={() => handleExpand(run.id)}>
                {expandedId === run.id ? <ChevronUp className="h-4 w-4 shrink-0" /> : <ChevronDown className="h-4 w-4 shrink-0" />}
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-medium">{run.name}</p>
                  <div className="flex items-center gap-2 text-xs text-muted-foreground mt-0.5">
                    <Badge variant="outline" className={`text-[10px] ${STATUS_STYLE[run.status] || ''}`}>{run.status}</Badge>
                    <span>{run.completedQuestions}/{run.totalQuestions} 질문</span>
                    <span>{new Date(run.createdAt).toLocaleDateString('ko-KR')}</span>
                  </div>
                </div>
                {run.avgFaithfulness != null && (
                  <div className="flex gap-3 text-xs shrink-0">
                    <span className={scoreColor(run.avgFaithfulness)}>F: <strong>{run.avgFaithfulness.toFixed(1)}</strong></span>
                    <span className={scoreColor(run.avgRelevance)}>R: <strong>{run.avgRelevance?.toFixed(1)}</strong></span>
                    <span className={scoreColor(run.avgCorrectness)}>C: <strong>{run.avgCorrectness?.toFixed(1)}</strong></span>
                  </div>
                )}
                <div className="flex gap-1 shrink-0" onClick={e => e.stopPropagation()}>
                  {run.status === 'READY' && (
                    <Button variant="ghost" size="icon" className="h-7 w-7" onClick={() => handleExecute(run.id)} title="평가 실행">
                      <Play className="h-3.5 w-3.5" />
                    </Button>
                  )}
                  {run.status === 'COMPLETED' && (
                    <Button variant="ghost" size="icon" className="h-7 w-7" onClick={() => handleDownload(run)} title="엑셀 다운로드">
                      <Download className="h-3.5 w-3.5" />
                    </Button>
                  )}
                  <Button variant="ghost" size="icon" className="h-7 w-7 text-destructive" onClick={() => handleDelete(run.id)}>
                    <Trash2 className="h-3.5 w-3.5" />
                  </Button>
                </div>
              </div>
            </Card>

            {expandedId === run.id && (
              <div className="ml-6 mt-2 space-y-3">
                <div className="grid grid-cols-3 gap-3">
                  <ScoreCard label="Faithfulness" value={run.avgFaithfulness} />
                  <ScoreCard label="Relevance" value={run.avgRelevance} />
                  <ScoreCard label="Correctness" value={run.avgCorrectness} />
                </div>

                <Card className="p-3">
                  <table className="w-full text-sm">
                    <thead className="bg-muted/50">
                      <tr>
                        <th className="text-left px-3 py-2 font-medium">질문</th>
                        <th className="text-left px-3 py-2 font-medium w-20">유형</th>
                        <th className="text-center px-3 py-2 font-medium w-12">F</th>
                        <th className="text-center px-3 py-2 font-medium w-12">R</th>
                        <th className="text-center px-3 py-2 font-medium w-12">C</th>
                        <th className="text-left px-3 py-2 font-medium">코멘트</th>
                      </tr>
                    </thead>
                    <tbody>
                      {questions.map(q => (
                        <React.Fragment key={q.id}>
                          <tr className="border-t hover:bg-muted/30 cursor-pointer"
                              onClick={async () => {
                                if (expandedQId === q.id) {
                                  setExpandedQId(null);
                                  return;
                                }
                                try {
                                  const detail = await fetchQuestionDetail(q.id);
                                  setQuestionDetail(detail);
                                  setExpandedQId(q.id);
                                } catch { toast.error('상세 조회 실패'); }
                              }}>
                            <td className="px-3 py-2 max-w-60 truncate underline decoration-dotted underline-offset-2" title={q.question}>{q.question}</td>
                            <td className="px-3 py-2">
                              <Badge variant="outline" className="text-[10px]">{q.questionType}</Badge>
                            </td>
                            <td className={`px-3 py-2 text-center font-medium ${scoreColor(q.faithfulness)}`}>{q.faithfulness?.toFixed(1) ?? '-'}</td>
                            <td className={`px-3 py-2 text-center font-medium ${scoreColor(q.relevance)}`}>{q.relevance?.toFixed(1) ?? '-'}</td>
                            <td className={`px-3 py-2 text-center font-medium ${scoreColor(q.correctness)}`}>{q.correctness?.toFixed(1) ?? '-'}</td>
                            <td className="px-3 py-2 text-xs text-muted-foreground max-w-40 truncate" title={q.judgeComment || ''}>
                              {q.judgeComment || (q.status === 'PENDING' ? '대기 중' : '')}
                            </td>
                          </tr>
                          <tr>
                            <td colSpan={6} className="p-0">
                              <div className="grid transition-all duration-300 ease-in-out"
                                   style={{ gridTemplateRows: expandedQId === q.id ? '1fr' : '0fr' }}>
                                <div className="overflow-hidden">
                                  {questionDetail?.id === q.id && (
                                    <div className="px-3 py-3 bg-muted/20 space-y-3 text-sm">
                                      <div>
                                        <p className="font-medium text-xs text-muted-foreground mb-1">기대 답변</p>
                                        <div className="prose prose-sm max-w-none dark:prose-invert">
                                          <Markdown remarkPlugins={[remarkGfm]}>{questionDetail.expectedAnswer}</Markdown>
                                        </div>
                                      </div>
                                      {questionDetail.actualResponse && (
                                        <div>
                                          <p className="font-medium text-xs text-muted-foreground mb-1">실제 RAG 응답</p>
                                          <div className="prose prose-sm max-w-none dark:prose-invert bg-background p-2 rounded border">
                                            <Markdown remarkPlugins={[remarkGfm]}>{questionDetail.actualResponse}</Markdown>
                                          </div>
                                        </div>
                                      )}
                                      {questionDetail.retrievedContext && (
                                        <div>
                                          <p className="font-medium text-xs text-muted-foreground mb-1">검색된 컨텍스트</p>
                                          <p className="whitespace-pre-wrap bg-background p-2 rounded border text-xs max-h-40 overflow-y-auto">{questionDetail.retrievedContext}</p>
                                        </div>
                                      )}
                                      {questionDetail.judgeComment && (
                                        <div>
                                          <p className="font-medium text-xs text-muted-foreground mb-1">심사 코멘트</p>
                                          <p>{questionDetail.judgeComment}</p>
                                        </div>
                                      )}
                                    </div>
                                  )}
                                </div>
                              </div>
                            </td>
                          </tr>
                        </React.Fragment>
                      ))}
                    </tbody>
                  </table>
                </Card>
              </div>
            )}
          </div>
        ))}

        {runs.length === 0 && !creating && (
          <p className="text-sm text-muted-foreground text-center py-8">평가 이력이 없습니다. "새 평가 생성"을 클릭하세요.</p>
        )}
      </div>
    </div>
  );
}
