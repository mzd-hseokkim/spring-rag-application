import { useEffect, useState, useRef, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useGeneration } from '@/hooks/useGeneration';
import { searchDocuments } from '@/api/client';
import { startOutlineExtraction, saveOutline, startRequirementMapping, saveRequirementMapping, startSectionGeneration, getStreamUrl, getDownloadUrl, getPreviewUrl, fetchJob, type OutlineNode, type GenerationJob, type GenerationProgressEvent } from '@/api/generation';
import { OutlineEditor } from '@/components/generation/OutlineEditor';
import { RequirementMapView } from '@/components/generation/RequirementMapView';
import { SectionEditor } from '@/components/generation/SectionEditor';
import { GenerationProgress } from '@/components/generation/GenerationProgress';
import { GenerationResult } from '@/components/generation/GenerationResult';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger } from '@/components/ui/select';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';
import {
  ArrowLeft, ArrowRight, FileText, Loader2, AlertCircle, Clock, X, Trash2, Plus,
  Search, Download, BookOpen, Target, CheckCircle2, Circle
} from 'lucide-react';

type DocItem = { id: string; filename: string; chunkCount: number };

// ─── DocPicker (질의서 페이지에서 재사용 가능한 패턴) ───
function DocPicker({ label, description, icon, selectedItems, onToggle, onRemove }: {
  label: string; description: string; icon: React.ReactNode;
  selectedItems: DocItem[]; onToggle: (doc: DocItem) => void; onRemove: (id: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [results, setResults] = useState<DocItem[]>([]);
  const [searching, setSearching] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout>>();
  const selectedIds = selectedItems.map(d => d.id);

  const doSearch = useCallback(async (keyword: string) => {
    if (!keyword.trim()) { setResults([]); setSearching(false); return; }
    setSearching(true);
    try {
      const docs = await searchDocuments(keyword.trim());
      setResults(docs.map(d => ({ id: d.id, filename: d.filename, chunkCount: d.chunkCount })));
    } catch { setResults([]); } finally { setSearching(false); }
  }, []);

  const handleSearchChange = (value: string) => {
    setSearch(value);
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => doSearch(value), 400);
  };

  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between">
        <label className="text-sm font-medium flex items-center gap-1.5">{icon}{label}</label>
        <Button variant="outline" size="sm" onClick={() => { setOpen(!open); setSearch(''); setResults([]); }} className="h-7 text-xs">문서 선택</Button>
      </div>
      {selectedItems.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {selectedItems.map(doc => (
            <Badge key={doc.id} variant="secondary" className="gap-1 pr-1">
              <FileText className="h-3 w-3" /><span className="max-w-[150px] truncate">{doc.filename}</span>
              <button onClick={() => onRemove(doc.id)} className="ml-0.5 rounded-full p-0.5 hover:bg-foreground/10 cursor-pointer"><X className="h-3 w-3" /></button>
            </Badge>
          ))}
        </div>
      )}
      {open && (
        <div className="border rounded-lg bg-muted/30">
          <div className="relative px-3 pt-3 pb-2">
            <Search className="absolute left-5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground mt-0.5" />
            <Input value={search} onChange={e => handleSearchChange(e.target.value)} placeholder="문서명을 입력하여 검색..." className="h-8 text-sm pl-8" autoFocus />
          </div>
          <div className="px-3 pb-3 space-y-1 max-h-48 overflow-y-auto">
            {!search.trim() ? <p className="text-xs text-muted-foreground py-2 text-center">문서명을 입력하세요.</p>
              : searching ? <p className="text-xs text-muted-foreground py-2 text-center">검색 중...</p>
              : results.length === 0 ? <p className="text-xs text-muted-foreground py-2 text-center">검색 결과가 없습니다.</p>
              : results.map(doc => (
                <label key={doc.id} className="flex items-center gap-2 py-1.5 px-2 rounded-md hover:bg-accent/50 cursor-pointer text-sm">
                  <Checkbox checked={selectedIds.includes(doc.id)} onCheckedChange={() => onToggle(doc)} />
                  <FileText className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                  <span className="truncate">{doc.filename}</span>
                  <span className="ml-auto text-xs text-muted-foreground shrink-0">{doc.chunkCount}청크</span>
                </label>
              ))}
          </div>
        </div>
      )}
      <p className="text-xs text-muted-foreground">{description}</p>
    </div>
  );
}

// ─── 위자드 스텝 인디케이터 ───
const STEPS = [
  { num: 1, label: '문서 설정' },
  { num: 2, label: '목차 구성' },
  { num: 3, label: '요구사항 배치' },
  { num: 4, label: '내용 생성' },
  { num: 5, label: '미리보기' },
];

function StepIndicator({ current }: { current: number }) {
  return (
    <div className="flex items-center gap-1 mb-6">
      {STEPS.map((step, i) => (
        <div key={step.num} className="flex items-center gap-1">
          <div className={`flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium transition-colors ${
            step.num === current ? 'bg-primary text-primary-foreground' :
            step.num < current ? 'bg-primary/20 text-primary' : 'bg-muted text-muted-foreground'
          }`}>
            {step.num < current ? <CheckCircle2 className="h-3.5 w-3.5" /> : <Circle className="h-3.5 w-3.5" />}
            {step.label}
          </div>
          {i < STEPS.length - 1 && <ArrowRight className="h-3.5 w-3.5 text-muted-foreground" />}
        </div>
      ))}
    </div>
  );
}

// ─── 메인 페이지 ───
export function GeneratePage() {
  const navigate = useNavigate();
  const gen = useGeneration();

  // Step 1 state
  const [selectedTemplateId, setSelectedTemplateId] = useState('');
  const [userInput, setUserInput] = useState('');
  const [customerDocs, setCustomerDocs] = useState<DocItem[]>([]);
  const [refDocs, setRefDocs] = useState<DocItem[]>([]);

  // Wizard state
  const [wizardStep, setWizardStep] = useState(1);
  const [outline, setOutline] = useState<OutlineNode[]>([]);
  const [analyzing, setAnalyzing] = useState(false);
  const [analyzeError, setAnalyzeError] = useState<string | null>(null);
  const [jobId, setJobId] = useState<string | null>(null);

  // Step 3 state
  const [requirements, setRequirements] = useState<Array<{ id: string; category: string; item: string; description: string; importance: string }>>([]);
  const [reqMapping, setReqMapping] = useState<Record<string, string[]>>({});
  const [mapping, setMapping] = useState(false);
  const [mappingError, setMappingError] = useState<string | null>(null);

  // Step 4 state
  type SectionData = { key: string; title: string; content: string; highlights: string[]; tables: Array<{ caption: string; headers: string[]; rows: string[][] }>; references: string[]; layoutHint?: string };
  const [sections, setSections] = useState<SectionData[]>([]);
  const [generating, setGenerating] = useState(false);
  const [generatingIndex, setGeneratingIndex] = useState(-1);
  const [totalSectionsCount, setTotalSectionsCount] = useState(0);
  const [generateError, setGenerateError] = useState<string | null>(null);
  const [includeWebSearch, setIncludeWebSearch] = useState(false);

  useEffect(() => {
    gen.loadTemplates();
    gen.loadJobs();
  }, []);

  const selectedTemplate = gen.templates.find(t => t.id === selectedTemplateId);
  const toggleDoc = (list: DocItem[], setList: (v: DocItem[]) => void, doc: DocItem) => {
    setList(list.some(d => d.id === doc.id) ? list.filter(d => d.id !== doc.id) : [...list, doc]);
  };

  // Step 1 → Step 2: 작업 생성 + 목차 추출
  const handleAnalyze = async () => {
    if (!selectedTemplateId || customerDocs.length === 0) return;
    setAnalyzing(true);
    setAnalyzeError(null);
    try {
      // 작업 생성
      const { startGeneration: apiCreate } = await import('@/api/generation');
      const job = await apiCreate({
        templateId: selectedTemplateId,
        userInput: userInput.trim(),
        customerDocumentIds: customerDocs.map(d => d.id),
        referenceDocumentIds: refDocs.length > 0 ? refDocs.map(d => d.id) : undefined,
      });
      setJobId(job.id);

      // 목차 추출 시작
      await startOutlineExtraction(job.id, customerDocs.map(d => d.id));

      // SSE로 완료 대기
      const token = localStorage.getItem('accessToken');
      const url = getStreamUrl(job.id) + (token ? `?token=${token}` : '');
      const es = new EventSource(url);

      es.addEventListener('complete', () => {
        es.close();
        // 작업 조회해서 outline 가져오기
        import('@/api/generation').then(({ fetchJob }) =>
          fetchJob(job.id).then(updated => {
            if (updated.outline) {
              try { setOutline(JSON.parse(updated.outline)); } catch { /* ignore */ }
            }
            setWizardStep(2);
            setAnalyzing(false);
          })
        );
      });

      es.addEventListener('error', (e) => {
        try {
          const data: GenerationProgressEvent = JSON.parse((e as MessageEvent).data);
          setAnalyzeError(data.message || '목차 추출 중 오류가 발생했습니다.');
        } catch {
          setAnalyzeError('목차 추출 중 오류가 발생했습니다.');
        }
        es.close();
        setAnalyzing(false);
      });

      es.onerror = () => {
        if (es.readyState === EventSource.CLOSED) return;
        setAnalyzeError('서버와의 연결이 끊어졌습니다.');
        es.close();
        setAnalyzing(false);
      };
    } catch (e) {
      setAnalyzeError(e instanceof Error ? e.message : '분석 실패');
      setAnalyzing(false);
    }
  };

  // Step 2 → Step 3: 목차 저장 + 요구사항 매핑 시작
  const handleSaveOutline = async () => {
    if (!jobId) return;
    await saveOutline(jobId, outline);
    handleStartMapping();
  };

  // Step 3: 요구사항 매핑
  const handleStartMapping = async () => {
    if (!jobId) return;
    setMapping(true);
    setMappingError(null);
    setWizardStep(3);
    try {
      await startRequirementMapping(jobId, customerDocs.map(d => d.id));

      const token = localStorage.getItem('accessToken');
      const url = getStreamUrl(jobId) + (token ? `?token=${token}` : '');
      const es = new EventSource(url);

      es.addEventListener('complete', () => {
        es.close();
        fetchJob(jobId).then(updated => {
          if (updated.requirementMapping) {
            try {
              const parsed = JSON.parse(updated.requirementMapping);
              setRequirements(parsed.requirements || []);
              setReqMapping(parsed.mapping || {});
            } catch { /* ignore */ }
          }
          setMapping(false);
        });
      });

      es.addEventListener('error', (e) => {
        try {
          const data: GenerationProgressEvent = JSON.parse((e as MessageEvent).data);
          setMappingError(data.message || '요구사항 매핑 중 오류가 발생했습니다.');
        } catch {
          setMappingError('요구사항 매핑 중 오류가 발생했습니다.');
        }
        es.close();
        setMapping(false);
      });

      es.onerror = () => {
        if (es.readyState === EventSource.CLOSED) return;
        setMappingError('서버와의 연결이 끊어졌습니다.');
        es.close();
        setMapping(false);
      };
    } catch (e) {
      setMappingError(e instanceof Error ? e.message : '매핑 실패');
      setMapping(false);
    }
  };

  // Step 3 → Step 4: 매핑 저장 + 섹션 생성 시작
  const handleSaveMapping = async () => {
    if (!jobId) return;
    await saveRequirementMapping(jobId, { requirements, mapping: reqMapping });
    handleStartSectionGeneration();
  };

  // Step 4: 섹션 생성
  const handleStartSectionGeneration = async () => {
    if (!jobId) return;
    setGenerating(true);
    setGenerateError(null);
    setSections([]);
    setGeneratingIndex(0);
    setWizardStep(4);
    try {
      const refIds = refDocs.length > 0 ? refDocs.map(d => d.id) : undefined;
      await startSectionGeneration(jobId, refIds, includeWebSearch);

      const token = localStorage.getItem('accessToken');
      const url = getStreamUrl(jobId) + (token ? `?token=${token}` : '');
      const es = new EventSource(url);

      es.addEventListener('progress', (e) => {
        const data: GenerationProgressEvent = JSON.parse(e.data);
        if (data.currentSection != null && data.totalSections != null) {
          setGeneratingIndex(data.currentSection - 1);
          setTotalSectionsCount(data.totalSections);
        }
      });

      es.addEventListener('complete', () => {
        es.close();
        fetchJob(jobId).then(updated => {
          if (updated.generatedSections) {
            try { setSections(JSON.parse(updated.generatedSections)); } catch { /* ignore */ }
          }
          setTotalSectionsCount(updated.totalSections);
          setGenerating(false);
          setGeneratingIndex(-1);
        });
      });

      es.addEventListener('error', (e) => {
        try {
          const data: GenerationProgressEvent = JSON.parse((e as MessageEvent).data);
          setGenerateError(data.message || '섹션 생성 중 오류가 발생했습니다.');
        } catch {
          setGenerateError('섹션 생성 중 오류가 발생했습니다.');
        }
        es.close();
        setGenerating(false);
        setGeneratingIndex(-1);
      });

      es.onerror = () => {
        if (es.readyState === EventSource.CLOSED) return;
        setGenerateError('서버와의 연결이 끊어졌습니다.');
        es.close();
        setGenerating(false);
        setGeneratingIndex(-1);
      };
    } catch (e) {
      setGenerateError(e instanceof Error ? e.message : '생성 실패');
      setGenerating(false);
      setGeneratingIndex(-1);
    }
  };

  const handleSectionChange = (index: number, updated: SectionData) => {
    setSections(prev => prev.map((s, i) => i === index ? updated : s));
  };

  const handleReset = () => {
    gen.reset();
    setUserInput('');
    setCustomerDocs([]);
    setRefDocs([]);
    setOutline([]);
    setJobId(null);
    setWizardStep(1);
    setAnalyzing(false);
    setAnalyzeError(null);
    setRequirements([]);
    setReqMapping({});
    setMapping(false);
    setMappingError(null);
    setSections([]);
    setGenerating(false);
    setGeneratingIndex(-1);
    setTotalSectionsCount(0);
    setGenerateError(null);
    setIncludeWebSearch(false);
  };

  return (
    <div className="flex h-screen bg-background">
      {/* 사이드바 */}
      <aside className="w-72 border-r bg-sidebar text-sidebar-foreground flex flex-col shrink-0">
        <div className="flex items-center gap-2 px-3 pt-3 pb-1">
          <Button variant="ghost" size="icon" className="h-8 w-8" onClick={() => navigate('/')} title="채팅으로 돌아가기">
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <h2 className="text-sm font-medium flex-1">문서 생성</h2>
          <Button variant="outline" size="sm" className="h-7 text-xs" onClick={handleReset}>
            <Plus className="h-3.5 w-3.5 mr-1" />새로 만들기
          </Button>
        </div>
        <div className="flex-1 overflow-y-auto p-3 space-y-1">
          <p className="text-xs text-sidebar-foreground/50 mb-2">생성 이력</p>
          {gen.jobs.length === 0 && <p className="text-xs text-sidebar-foreground/40 px-2">아직 생성된 문서가 없습니다.</p>}
          {gen.jobs.map(job => <JobHistoryItem key={job.id} job={job} onDelete={gen.removeJob} />)}
        </div>
      </aside>

      {/* 메인 위자드 영역 */}
      <main className="flex-1 overflow-y-auto">
        <div className="max-w-5xl mx-auto py-8 px-6">
          <StepIndicator current={wizardStep} />

          {/* ── Step 1: 문서 설정 ── */}
          {wizardStep === 1 && (
            <Card>
              <CardHeader>
                <CardTitle>문서 설정</CardTitle>
                <CardDescription>고객 문서(RFP)를 선택하면 AI가 목차를 추출하고, 이를 기반으로 제안서를 생성합니다.</CardDescription>
              </CardHeader>
              <CardContent className="space-y-5">
                {/* 템플릿 */}
                <div className="space-y-1.5">
                  <label className="text-sm font-medium">템플릿 *</label>
                  <Select value={selectedTemplateId} onValueChange={v => setSelectedTemplateId(v ?? '')}>
                    <SelectTrigger className="w-full">
                      <span className={`truncate ${!selectedTemplate ? 'text-muted-foreground' : ''}`}>
                        {selectedTemplate ? selectedTemplate.name : '템플릿을 선택하세요'}
                      </span>
                    </SelectTrigger>
                    <SelectContent>
                      {gen.templates.map(t => (
                        <SelectItem key={t.id} value={t.id}>{t.name}</SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>

                {/* 고객 문서 */}
                <DocPicker
                  label="고객 문서 (RFP/요구사항) *"
                  description="제안요청서, 요구사항 명세서 등 고객이 전달한 문서를 선택합니다."
                  icon={<Target className="h-4 w-4" />}
                  selectedItems={customerDocs}
                  onToggle={doc => toggleDoc(customerDocs, setCustomerDocs, doc)}
                  onRemove={id => setCustomerDocs(prev => prev.filter(x => x.id !== id))}
                />

                {/* 참조 문서 */}
                <DocPicker
                  label="참조 문서 (선택)"
                  description="유사 제안서, 기술 자료 등 내용 작성 시 참고할 문서입니다."
                  icon={<BookOpen className="h-4 w-4" />}
                  selectedItems={refDocs}
                  onToggle={doc => toggleDoc(refDocs, setRefDocs, doc)}
                  onRemove={id => setRefDocs(prev => prev.filter(x => x.id !== id))}
                />

                {/* 추가 지시사항 */}
                <div className="space-y-1.5">
                  <label className="text-sm font-medium">추가 지시사항 (선택)</label>
                  <Textarea value={userInput} onChange={e => setUserInput(e.target.value)}
                    placeholder="특정 주제에 집중하거나 문서 스타일을 지정하고 싶을 때 입력하세요..." rows={4} className="resize-y" />
                </div>

                {/* 분석 시작 버튼 */}
                <div className="flex justify-end">
                  <Button onClick={handleAnalyze} disabled={!selectedTemplateId || customerDocs.length === 0 || analyzing}>
                    {analyzing ? <Loader2 className="h-4 w-4 mr-1.5 animate-spin" /> : <ArrowRight className="h-4 w-4 mr-1.5" />}
                    {analyzing ? '목차 추출 중...' : '분석 시작'}
                  </Button>
                </div>

                {analyzeError && (
                  <div className="flex items-center gap-2 text-destructive text-sm bg-destructive/10 rounded-md p-3">
                    <AlertCircle className="h-4 w-4 shrink-0" />{analyzeError}
                  </div>
                )}
              </CardContent>
            </Card>
          )}

          {/* ── Step 2: 목차 구성 ── */}
          {wizardStep === 2 && (
            <Card>
              <CardHeader>
                <CardTitle>목차 구성</CardTitle>
                <CardDescription>AI가 추출한 목차를 확인하고, 필요에 따라 수정하세요. 항목을 추가/삭제/순서변경할 수 있습니다.</CardDescription>
              </CardHeader>
              <CardContent className="space-y-4">
                {outline.length === 0 ? (
                  <p className="text-sm text-muted-foreground py-8 text-center">추출된 목차가 없습니다.</p>
                ) : (
                  <div className="border rounded-lg p-4 bg-muted/20">
                    <OutlineEditor outline={outline} onChange={setOutline} />
                  </div>
                )}

                <div className="flex justify-between">
                  <Button variant="outline" onClick={() => setWizardStep(1)}>
                    <ArrowLeft className="h-4 w-4 mr-1.5" />이전
                  </Button>
                  <Button onClick={handleSaveOutline} disabled={outline.length === 0}>
                    <ArrowRight className="h-4 w-4 mr-1.5" />다음 (요구사항 배치)
                  </Button>
                </div>
              </CardContent>
            </Card>
          )}

          {/* ── Step 3: 요구사항 배치 ── */}
          {wizardStep === 3 && (
            <Card>
              <CardHeader>
                <CardTitle>요구사항 배치</CardTitle>
                <CardDescription>AI가 추출한 요구사항을 목차에 매핑했습니다. 좌측 목차를 클릭하면 배치된 요구사항을 확인할 수 있습니다.</CardDescription>
              </CardHeader>
              <CardContent className="space-y-4">
                {mapping ? (
                  <div className="flex items-center gap-3 py-12 justify-center">
                    <Loader2 className="h-5 w-5 animate-spin text-primary" />
                    <span className="text-sm text-muted-foreground">요구사항을 추출하고 목차에 매핑하고 있습니다...</span>
                  </div>
                ) : requirements.length === 0 ? (
                  <p className="text-sm text-muted-foreground py-8 text-center">추출된 요구사항이 없습니다.</p>
                ) : (
                  <RequirementMapView
                    outline={outline}
                    requirements={requirements}
                    mapping={reqMapping}
                    onChange={setReqMapping}
                  />
                )}

                {mappingError && (
                  <div className="flex items-center gap-2 text-destructive text-sm bg-destructive/10 rounded-md p-3">
                    <AlertCircle className="h-4 w-4 shrink-0" />{mappingError}
                  </div>
                )}

                <div className="flex justify-between">
                  <Button variant="outline" onClick={() => setWizardStep(2)}>
                    <ArrowLeft className="h-4 w-4 mr-1.5" />이전 (목차)
                  </Button>
                  <Button onClick={handleSaveMapping} disabled={mapping || requirements.length === 0}>
                    <ArrowRight className="h-4 w-4 mr-1.5" />다음 (내용 생성)
                  </Button>
                </div>
              </CardContent>
            </Card>
          )}

          {/* ── Step 4: 내용 생성 ── */}
          {wizardStep === 4 && (
            <Card>
              <CardHeader>
                <CardTitle>내용 생성</CardTitle>
                <CardDescription>
                  {generating ? '섹션별로 내용을 생성하고 있습니다...' :
                    sections.length > 0 ? '생성된 내용을 확인하고 수정할 수 있습니다. 좌측 목록에서 섹션을 선택하세요.' :
                    '섹션 생성을 시작합니다.'}
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-4">
                <SectionEditor
                  sections={sections}
                  generatingIndex={generating ? generatingIndex : -1}
                  totalSections={totalSectionsCount || sections.length}
                  onSectionChange={handleSectionChange}
                />

                {generateError && (
                  <div className="flex items-center gap-2 text-destructive text-sm bg-destructive/10 rounded-md p-3">
                    <AlertCircle className="h-4 w-4 shrink-0" />{generateError}
                  </div>
                )}

                <div className="flex justify-between">
                  <Button variant="outline" onClick={() => setWizardStep(3)} disabled={generating}>
                    <ArrowLeft className="h-4 w-4 mr-1.5" />이전 (요구사항)
                  </Button>
                  <Button onClick={() => setWizardStep(5)} disabled={generating || sections.length === 0}>
                    <ArrowRight className="h-4 w-4 mr-1.5" />미리보기
                  </Button>
                </div>
              </CardContent>
            </Card>
          )}

          {/* ── Step 5: 미리보기 (Phase D에서 구현) ── */}
          {wizardStep === 5 && (
            <Card>
              <CardHeader>
                <CardTitle>미리보기 & 출력</CardTitle>
                <CardDescription>HTML 미리보기와 PDF 다운로드는 다음 업데이트에서 제공됩니다.</CardDescription>
              </CardHeader>
              <CardContent>
                <p className="text-sm text-muted-foreground mb-4">
                  {sections.length}개 섹션 내용이 생성되었습니다. 렌더링 및 PDF 출력 기능은 곧 구현됩니다.
                </p>
                <Button variant="outline" onClick={() => setWizardStep(4)}>
                  <ArrowLeft className="h-4 w-4 mr-1.5" />내용 수정으로 돌아가기
                </Button>
              </CardContent>
            </Card>
          )}
        </div>
      </main>
    </div>
  );
}

function JobHistoryItem({ job, onDelete }: { job: GenerationJob; onDelete: (id: string) => void }) {
  const isProcessing = ['PLANNING', 'GENERATING', 'REVIEWING', 'RENDERING', 'ANALYZING', 'MAPPING'].includes(job.status);
  const statusIcon = job.status === 'COMPLETE' ? <FileText className="h-3.5 w-3.5 text-green-500" />
    : job.status === 'FAILED' ? <AlertCircle className="h-3.5 w-3.5 text-destructive" />
    : isProcessing ? <Loader2 className="h-3.5 w-3.5 animate-spin text-primary" />
    : <Circle className="h-3.5 w-3.5 text-muted-foreground" />;

  const handleClick = () => {
    if (job.status === 'COMPLETE') {
      const token = localStorage.getItem('accessToken') || '';
      window.open(`${getPreviewUrl(job.id)}?token=${token}`, '_blank');
    }
  };

  const handleDownload = async (e: React.MouseEvent) => {
    e.stopPropagation();
    const token = localStorage.getItem('accessToken') || '';
    const res = await fetch(getDownloadUrl(job.id), { headers: { 'Authorization': `Bearer ${token}` } });
    if (!res.ok) return;
    const blob = await res.blob();
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `document-${new Date(job.createdAt).toISOString().slice(0, 10)}.html`;
    a.click();
    window.URL.revokeObjectURL(url);
  };

  return (
    <div className="group flex items-center gap-2 w-full px-2 py-2 rounded-md hover:bg-sidebar-accent/50 transition-colors">
      <button onClick={handleClick} className="flex items-center gap-2 flex-1 min-w-0 text-left cursor-pointer">
        {statusIcon}
        <div className="flex-1 min-w-0">
          <p className="text-sm truncate">{job.templateName}</p>
          <div className="flex items-center gap-1 text-xs text-sidebar-foreground/40">
            <Clock className="h-3 w-3" />{new Date(job.createdAt).toLocaleDateString('ko-KR')}
          </div>
        </div>
      </button>
      {job.status === 'COMPLETE' && (
        <button onClick={handleDownload} className="opacity-0 group-hover:opacity-100 p-1 rounded hover:bg-primary/10 text-muted-foreground hover:text-primary transition-all cursor-pointer shrink-0" title="다운로드">
          <Download className="h-3.5 w-3.5" />
        </button>
      )}
      <button onClick={e => { e.stopPropagation(); onDelete(job.id); }}
        className="opacity-0 group-hover:opacity-100 p-1 rounded hover:bg-destructive/10 text-muted-foreground hover:text-destructive transition-all cursor-pointer shrink-0" title="삭제">
        <Trash2 className="h-3.5 w-3.5" />
      </button>
    </div>
  );
}
