import { useEffect, useState, useRef, useCallback } from 'react';
import { useQuestionnaire } from '@/hooks/useQuestionnaire';
import { usePersonas } from '@/hooks/usePersonas';
import { searchDocuments } from '@/api/client';
import { PersonaSelector } from '@/components/questionnaire/PersonaSelector';
import { QuestionnaireProgress } from '@/components/questionnaire/QuestionnaireProgress';
import { QuestionnaireResult } from '@/components/questionnaire/QuestionnaireResult';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';
import { AppSidebar } from '@/components/layout/AppSidebar';
import { FileText, Loader2, AlertCircle, Clock, X, ClipboardList, Target, BookOpen, Trash2, Plus, Search, Download, Pencil } from 'lucide-react';
import type { QuestionnaireJob } from '@/api/questionnaire';
import { getQuestionnairePreviewUrl, getQuestionnaireDownloadUrl, updateQuestionnaireJobTitle } from '@/api/questionnaire';

type DocItem = { id: string; filename: string; chunkCount: number };

function DocPicker({ label, description, icon, selectedItems, onToggle, onRemove }: {
  label: string;
  description: string;
  icon: React.ReactNode;
  selectedItems: DocItem[];
  onToggle: (doc: DocItem) => void;
  onRemove: (id: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [results, setResults] = useState<DocItem[]>([]);
  const [searching, setSearching] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout>>(undefined);
  const selectedIds = selectedItems.map(d => d.id);

  const doSearch = useCallback(async (keyword: string) => {
    if (!keyword.trim()) {
      setResults([]);
      setSearching(false);
      return;
    }
    setSearching(true);
    try {
      const docs = await searchDocuments(keyword.trim());
      setResults(docs.map(d => ({ id: d.id, filename: d.filename, chunkCount: d.chunkCount })));
    } catch {
      setResults([]);
    } finally {
      setSearching(false);
    }
  }, []);

  const handleSearchChange = (value: string) => {
    setSearch(value);
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => doSearch(value), 400);
  };

  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between">
        <label className="text-sm font-medium flex items-center gap-1.5">
          {icon}
          {label}
        </label>
        <Button variant="outline" size="sm" onClick={() => { setOpen(!open); setSearch(''); setResults([]); }} className="h-7 text-xs">
          문서 선택
        </Button>
      </div>

      {selectedItems.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {selectedItems.map((doc) => (
            <Badge key={doc.id} variant="secondary" className="gap-1 pr-1">
              <FileText className="h-3 w-3" />
              <span className="max-w-[150px] truncate">{doc.filename}</span>
              <button onClick={() => onRemove(doc.id)} className="ml-0.5 rounded-full p-0.5 hover:bg-foreground/10 cursor-pointer">
                <X className="h-3 w-3" />
              </button>
            </Badge>
          ))}
        </div>
      )}

      {open && (
        <div className="border rounded-lg bg-muted/30">
          <div className="relative px-3 pt-3 pb-2">
            <Search className="absolute left-5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground mt-0.5" />
            <Input
              value={search}
              onChange={(e) => handleSearchChange(e.target.value)}
              placeholder="문서명을 입력하여 검색..."
              className="h-8 text-sm pl-8"
              autoFocus
            />
          </div>
          <div className="px-3 pb-3 space-y-1 max-h-48 overflow-y-auto">
            {!search.trim() ? (
              <p className="text-xs text-muted-foreground py-2 text-center">문서명을 입력하세요.</p>
            ) : searching ? (
              <p className="text-xs text-muted-foreground py-2 text-center">검색 중...</p>
            ) : results.length === 0 ? (
              <p className="text-xs text-muted-foreground py-2 text-center">검색 결과가 없습니다.</p>
            ) : (
              results.map((doc) => (
                <label key={doc.id} className="flex items-center gap-2 py-1.5 px-2 rounded-md hover:bg-accent/50 cursor-pointer text-sm">
                  <Checkbox checked={selectedIds.includes(doc.id)} onCheckedChange={() => onToggle(doc)} />
                  <FileText className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                  <span className="truncate">{doc.filename}</span>
                  <span className="ml-auto text-xs text-muted-foreground shrink-0">{doc.chunkCount}청크</span>
                </label>
              ))
            )}
          </div>
        </div>
      )}
      <p className="text-xs text-muted-foreground">{description}</p>
    </div>
  );
}

export function QuestionnairePage() {
  const qna = useQuestionnaire();
  const { personas, loadPersonas, createPersona, updatePersona, regeneratePrompt, deletePersona } = usePersonas();

  const [userInput, setUserInput] = useState('');
  const [customerDocs, setCustomerDocs] = useState<DocItem[]>([]);
  const [proposalDocs, setProposalDocs] = useState<DocItem[]>([]);
  const [refDocs, setRefDocs] = useState<DocItem[]>([]);
  const [selectedPersonaIds, setSelectedPersonaIds] = useState<string[]>([]);
  const [questionCount, setQuestionCount] = useState(7);
  const [includeWebSearch, setIncludeWebSearch] = useState(false);
  const [analysisMode, setAnalysisMode] = useState<'RAG' | 'LLM'>('RAG');

  useEffect(() => {
    loadPersonas();
    qna.loadJobs();
  }, []);

  useEffect(() => {
    if (personas.length > 0 && selectedPersonaIds.length === 0) {
      setSelectedPersonaIds(personas.filter(p => p.isDefault).map(p => p.id));
    }
  }, [personas]);

  const toggleDoc = (list: DocItem[], setList: (v: DocItem[]) => void, doc: DocItem) => {
    setList(list.some(d => d.id === doc.id) ? list.filter(d => d.id !== doc.id) : [...list, doc]);
  };

  const togglePersona = (id: string) => {
    setSelectedPersonaIds(prev => prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]);
  };

  const canStart = customerDocs.length > 0 && selectedPersonaIds.length > 0;

  const handleStart = () => {
    if (!canStart) return;
    qna.startGeneration({
      customerDocumentIds: customerDocs.map(d => d.id),
      proposalDocumentIds: proposalDocs.length > 0 ? proposalDocs.map(d => d.id) : undefined,
      referenceDocumentIds: refDocs.length > 0 ? refDocs.map(d => d.id) : undefined,
      personaIds: selectedPersonaIds,
      userInput: userInput.trim() || undefined,
      questionCount,
      includeWebSearch,
      analysisMode: proposalDocs.length > 0 ? analysisMode : undefined,
    });
  };

  const handleReset = () => {
    qna.reset();
    setUserInput('');
    setCustomerDocs([]);
    setProposalDocs([]);
    setRefDocs([]);
    setSelectedPersonaIds(personas.filter(p => p.isDefault).map(p => p.id));
    setQuestionCount(7);
    setIncludeWebSearch(false);
    setAnalysisMode('RAG');
  };

  const step = qna.currentJob?.status === 'COMPLETE'
    ? 'result'
    : qna.isGenerating
      ? 'progress'
      : 'input';

  return (
    <div className="flex h-screen bg-background">
      <AppSidebar>
        <div className="flex items-center gap-2 px-3 pt-3 pb-1">
          <h2 className="text-sm font-medium flex-1">생성 이력</h2>
          <Button variant="outline" size="sm" className="h-7 text-xs" onClick={handleReset}>
            <Plus className="h-3.5 w-3.5 mr-1" />
            새로 만들기
          </Button>
        </div>

        <div className="flex-1 overflow-y-auto p-3 space-y-1">
          {qna.jobs.length === 0 && (
            <p className="text-xs text-sidebar-foreground/40 px-2">아직 생성된 질의서가 없습니다.</p>
          )}
          {qna.jobs.map((job) => (
            <JobHistoryItem key={job.id} job={job} onDelete={qna.removeJob} onRename={async (id, title) => { await updateQuestionnaireJobTitle(id, title); qna.loadJobs(); }} />
          ))}
        </div>
      </AppSidebar>

      <main className="flex-1 overflow-y-auto">
        <div className="max-w-5xl mx-auto py-8 px-6">
          {step === 'input' && (
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <ClipboardList className="h-5 w-5" />
                  예상 질의서 생성
                </CardTitle>
                <CardDescription>고객 문서(RFP)와 제안 문서를 선택하고 페르소나를 지정하면 고객 요구사항 대비 예상 질문과 답변을 생성합니다.</CardDescription>
              </CardHeader>
              <CardContent className="space-y-5">
                {/* 고객 문서 (필수) */}
                <DocPicker
                  label="고객 문서 (RFP/요구사항) *"
                  description="제안요청서, 요구사항 명세서, 평가기준표 등 고객이 전달한 문서를 선택합니다."
                  icon={<FileText className="h-4 w-4" />}
                  selectedItems={customerDocs}
                  onToggle={(doc) => toggleDoc(customerDocs, setCustomerDocs, doc)}
                  onRemove={(id) => setCustomerDocs(prev => prev.filter(x => x.id !== id))}
                />

                {/* 제안 문서 (필수) */}
                <DocPicker
                  label="제안 문서 (우리 제안서)"
                  description="우리가 작성한 제안서, 기술제안서, 사업수행계획서 등을 선택합니다."
                  icon={<Target className="h-4 w-4" />}
                  selectedItems={proposalDocs}
                  onToggle={(doc) => toggleDoc(proposalDocs, setProposalDocs, doc)}
                  onRemove={(id) => setProposalDocs(prev => prev.filter(x => x.id !== id))}
                />

                {/* 참조 문서 (선택) */}
                <DocPicker
                  label="참조 문서 (선택)"
                  description="유사 제안서, 기존 질문집, 업계 자료 등 질문/답변 품질 향상을 위한 참고 자료입니다."
                  icon={<BookOpen className="h-4 w-4" />}
                  selectedItems={refDocs}
                  onToggle={(doc) => toggleDoc(refDocs, setRefDocs, doc)}
                  onRemove={(id) => setRefDocs(prev => prev.filter(x => x.id !== id))}
                />

                {/* 페르소나 선택 */}
                <PersonaSelector
                  personas={personas}
                  selectedIds={selectedPersonaIds}
                  onToggle={togglePersona}
                  onCreate={async (req) => { await createPersona(req); }}
                  onUpdate={async (id, req) => { await updatePersona(id, req); }}
                  onRegeneratePrompt={async (id) => { await regeneratePrompt(id); }}
                  onDelete={async (id) => {
                    await deletePersona(id);
                    setSelectedPersonaIds(prev => prev.filter(pid => pid !== id));
                  }}
                />

                {/* 질문 수 + 추가 지시사항 */}
                <div className="grid grid-cols-[120px_1fr] gap-4">
                  <div className="space-y-1.5">
                    <label className="text-sm font-medium">페르소나당 질문 수</label>
                    <Input
                      type="number"
                      min={3}
                      max={20}
                      value={questionCount}
                      onChange={(e) => setQuestionCount(e.target.value === '' ? '' as any : Number(e.target.value))}
                      onBlur={() => setQuestionCount(Math.max(3, Math.min(20, Number(questionCount) || 7)))}
                      className="h-9"
                    />
                  </div>
                  <div className="space-y-1.5">
                    <label className="text-sm font-medium">추가 지시사항 (선택)</label>
                    <Textarea
                      value={userInput}
                      onChange={(e) => setUserInput(e.target.value)}
                      placeholder="특정 주제에 집중하거나, 질문 난이도를 조절하고 싶을 때 입력하세요..."
                      rows={3}
                      className="resize-y"
                    />
                  </div>
                </div>

                {/* 웹 검색 옵션 */}
                <div className="flex items-start gap-2 p-3 rounded-lg border bg-muted/20">
                  <Checkbox
                    id="webSearch"
                    checked={includeWebSearch}
                    onCheckedChange={(checked) => setIncludeWebSearch(checked === true)}
                    className="mt-0.5"
                  />
                  <div>
                    <label htmlFor="webSearch" className="text-sm font-medium cursor-pointer">
                      웹 검색 포함
                    </label>
                    <p className="text-xs text-muted-foreground mt-0.5">
                      업계 동향, 유사 사례, 기술 트렌드 등 외부 정보를 검색하여 더 깊이 있는 질문을 생성합니다.
                    </p>
                  </div>
                </div>

                {/* 분석 모드 선택 (제안문서 선택 시에만 표시) */}
                {proposalDocs.length > 0 && (
                  <div className="p-3 rounded-lg border bg-muted/20 space-y-2">
                    <label className="text-sm font-medium">분석 모드</label>
                    <div className="flex gap-3">
                      <label className="flex items-center gap-2 cursor-pointer">
                        <input type="radio" name="analysisMode" value="RAG" checked={analysisMode === 'RAG'} onChange={() => setAnalysisMode('RAG')} className="accent-primary" />
                        <div>
                          <span className="text-sm font-medium">빠른 분석 (RAG)</span>
                          <p className="text-xs text-muted-foreground">요구사항별로 제안서를 검색하여 대응 여부 확인. 빠르지만 판정이 덜 정밀할 수 있음.</p>
                        </div>
                      </label>
                      <label className="flex items-center gap-2 cursor-pointer">
                        <input type="radio" name="analysisMode" value="LLM" checked={analysisMode === 'LLM'} onChange={() => setAnalysisMode('LLM')} className="accent-primary" />
                        <div>
                          <span className="text-sm font-medium">정밀 분석 (LLM)</span>
                          <p className="text-xs text-muted-foreground">AI가 요구사항별 충족도를 직접 판정. 더 정확하지만 시간과 비용이 더 소요됨.</p>
                        </div>
                      </label>
                    </div>
                  </div>
                )}

                {/* 생성 버튼 */}
                <div className="flex justify-end">
                  <Button onClick={handleStart} disabled={!canStart}>
                    <ClipboardList className="h-4 w-4 mr-1.5" />
                    질의서 생성 시작
                  </Button>
                </div>

                {qna.error && (
                  <div className="flex items-center gap-2 text-destructive text-sm bg-destructive/10 rounded-md p-3">
                    <AlertCircle className="h-4 w-4 shrink-0" />
                    {qna.error}
                  </div>
                )}
              </CardContent>
            </Card>
          )}

          {step === 'progress' && qna.currentJob && (
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <Loader2 className="h-5 w-5 animate-spin text-primary" />
                  예상 질의서 생성 중
                </CardTitle>
              </CardHeader>
              <CardContent>
                <QuestionnaireProgress
                  personas={qna.personas}
                  statusMessage={qna.statusMessage}
                  currentPersona={qna.currentJob.currentPersona}
                  totalPersonas={qna.currentJob.totalPersonas}
                />
              </CardContent>
            </Card>
          )}

          {step === 'result' && qna.currentJob && (
            <Card>
              <CardHeader>
                <CardTitle>예상 질의서</CardTitle>
              </CardHeader>
              <CardContent>
                <QuestionnaireResult
                  jobId={qna.currentJob.id}
                  onRegenerate={handleReset}
                />
              </CardContent>
            </Card>
          )}
        </div>
      </main>
    </div>
  );
}

function JobHistoryItem({ job, onDelete, onRename }: { job: QuestionnaireJob; onDelete: (id: string) => void; onRename: (id: string, title: string) => void }) {
  const statusIcon = {
    COMPLETE: <ClipboardList className="h-3.5 w-3.5 text-green-500" />,
    FAILED: <AlertCircle className="h-3.5 w-3.5 text-destructive" />,
    ANALYZING: <Loader2 className="h-3.5 w-3.5 animate-spin text-primary" />,
    GENERATING: <Loader2 className="h-3.5 w-3.5 animate-spin text-primary" />,
    RENDERING: <Loader2 className="h-3.5 w-3.5 animate-spin text-primary" />,
  };

  const [editing, setEditing] = useState(false);
  const [editValue, setEditValue] = useState(job.title || '예상 질의서');
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => { if (editing) inputRef.current?.select(); }, [editing]);

  const commitRename = () => {
    const trimmed = editValue.trim();
    if (trimmed && trimmed !== (job.title || '예상 질의서')) {
      onRename(job.id, trimmed);
    }
    setEditing(false);
  };

  const handleClick = () => {
    if (editing) return;
    if (job.status === 'COMPLETE') {
      const token = localStorage.getItem('accessToken') || '';
      window.open(`${getQuestionnairePreviewUrl(job.id)}?token=${token}`, '_blank');
    }
  };

  const handleDownload = async (e: React.MouseEvent) => {
    e.stopPropagation();
    const token = localStorage.getItem('accessToken') || '';
    const res = await fetch(getQuestionnaireDownloadUrl(job.id), { headers: { 'Authorization': `Bearer ${token}` } });
    if (!res.ok) return;
    const blob = await res.blob();
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `questionnaire-${new Date(job.createdAt).toISOString().slice(0, 10)}.xlsx`;
    a.click();
    window.URL.revokeObjectURL(url);
  };

  return (
    <div className="group flex items-center gap-2 w-full px-2 py-2 rounded-md hover:bg-sidebar-accent/50 transition-colors">
      <button onClick={handleClick} className="flex items-center gap-2 flex-1 min-w-0 text-left cursor-pointer">
        {statusIcon[job.status]}
        <div className="flex-1 min-w-0">
          {editing ? (
            <input
              ref={inputRef}
              value={editValue}
              onChange={e => setEditValue(e.target.value)}
              onBlur={commitRename}
              onKeyDown={e => { if (e.key === 'Enter') commitRename(); if (e.key === 'Escape') { setEditValue(job.title || '예상 질의서'); setEditing(false); } }}
              onClick={e => e.stopPropagation()}
              className="text-sm w-full bg-transparent border-b border-primary outline-none px-0 py-0"
            />
          ) : (
            <p className="text-sm truncate" title={job.title || '예상 질의서'}>{job.title || '예상 질의서'}</p>
          )}
          <div className="flex items-center gap-1 text-xs text-sidebar-foreground/40">
            <Clock className="h-3 w-3" />
            {new Date(job.createdAt).toLocaleDateString('ko-KR')}
          </div>
        </div>
      </button>
      <button onClick={e => { e.stopPropagation(); setEditValue(job.title || '예상 질의서'); setEditing(true); }}
        className="opacity-0 group-hover:opacity-100 p-1 rounded hover:bg-primary/10 text-muted-foreground hover:text-primary transition-all cursor-pointer shrink-0" title="이름 변경">
        <Pencil className="h-3.5 w-3.5" />
      </button>
      {job.status === 'COMPLETE' && (
        <button
          onClick={handleDownload}
          className="opacity-0 group-hover:opacity-100 p-1 rounded hover:bg-primary/10 text-muted-foreground hover:text-primary transition-all cursor-pointer shrink-0"
          title="엑셀 다운로드"
        >
          <Download className="h-3.5 w-3.5" />
        </button>
      )}
      <button
        onClick={(e) => { e.stopPropagation(); onDelete(job.id); }}
        className="opacity-0 group-hover:opacity-100 p-1 rounded hover:bg-destructive/10 text-muted-foreground hover:text-destructive transition-all cursor-pointer shrink-0"
        title="삭제"
      >
        <Trash2 className="h-3.5 w-3.5" />
      </button>
    </div>
  );
}
