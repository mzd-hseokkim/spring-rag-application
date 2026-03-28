import { useEffect, useState, useRef, useCallback } from 'react';
import { toast } from 'sonner';
import { useGeneration } from '@/hooks/useGeneration';
import { searchDocuments } from '@/api/client';
import { startOutlineExtraction, saveOutline, startRequirementMapping, saveRequirementMapping, generateUnmappedSections, startSectionGeneration, startRendering, regenerateSection, clearSections, getStreamUrl, getDownloadUrl, getPreviewUrl, fetchJob, updateJobTitle, type OutlineNode, type GenerationJob, type GenerationProgressEvent } from '@/api/generation';
import { OutlineEditor } from '@/components/generation/OutlineEditor';
import { RequirementMapView } from '@/components/generation/RequirementMapView';
import { SectionEditor } from '@/components/generation/SectionEditor';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger } from '@/components/ui/select';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';
import { AppSidebar } from '@/components/layout/AppSidebar';
import {
  ArrowLeft, ArrowRight, FileText, Loader2, AlertCircle, Clock, X, Trash2, Plus,
  Search, Download, BookOpen, Target, CheckCircle2, Circle, RefreshCw, Pencil, Check
} from 'lucide-react';

type DocItem = { id: string; filename: string; chunkCount: number };

type Requirement = { id: string; category: string; item: string; description: string; importance: string };

/** 요구사항 ID 정렬: 접두어 그룹별 → 숫자순 (CSR-001 < CSR-002 < DAR-001 < REQ-01) */
function compareReqIds(a: string, b: string): number {
  const parse = (id: string) => {
    const m = id.match(/^([A-Za-z가-힣]+)-?(\d+)$/);
    return m ? { prefix: m[1], num: parseInt(m[2], 10) } : { prefix: id, num: 0 };
  };
  const pa = parse(a), pb = parse(b);
  if (pa.prefix !== pb.prefix) return pa.prefix.localeCompare(pb.prefix);
  return pa.num - pb.num;
}

// ─── Markdown 다운로드 유틸 ───

function outlineToMarkdown(nodes: OutlineNode[], depth = 0): string {
  return nodes.map(node => {
    const prefix = '#'.repeat(Math.min(depth + 2, 6));
    let line = `${prefix} ${node.key}. ${node.title}`;
    if (node.description) line += `\n\n${node.description}`;
    const children = node.children.length > 0 ? '\n\n' + outlineToMarkdown(node.children, depth + 1) : '';
    return line + children;
  }).join('\n\n');
}

function buildOutlineMarkdown(outline: OutlineNode[]): string {
  return `# 목차 구성\n\n${outlineToMarkdown(outline)}`;
}

function buildRequirementMarkdown(
  outline: OutlineNode[],
  requirements: Requirement[],
  mapping: Record<string, string[]>,
): string {
  const reqMap = new Map(requirements.map(r => [r.id, r]));
  const allMappedIds = new Set(Object.values(mapping).flat());
  const unmapped = requirements.filter(r => !allMappedIds.has(r.id));

  // 요구사항 목록
  const lines: string[] = ['# 요구사항 및 목차 배치\n'];

  // 카테고리별 요구사항 요약
  const categories = new Map<string, Requirement[]>();
  for (const req of requirements) {
    const cat = req.category || '기타';
    const list = categories.get(cat) || [];
    list.push(req);
    categories.set(cat, list);
  }

  const sorted = [...requirements].sort((a, b) => compareReqIds(a.id, b.id));
  lines.push(`## 요구사항 목록 (${sorted.length}개)\n`);
  lines.push('| ID | 카테고리 | 항목 | 설명 | 중요도 |');
  lines.push('|---|---|---|---|---|');
  for (const req of sorted) {
    lines.push(`| ${req.id} | ${req.category} | ${req.item} | ${req.description} | ${req.importance} |`);
  }

  // 목차별 배치
  lines.push('\n## 목차별 요구사항 배치\n');
  const renderMapping = (nodes: OutlineNode[], depth: number) => {
    for (const node of nodes) {
      const prefix = '#'.repeat(Math.min(depth + 3, 6));
      const ids = mapping[node.key] || [];
      const suffix = ids.length > 0 ? ` (${ids.length}개)` : '';
      lines.push(`${prefix} ${node.key}. ${node.title}${suffix}\n`);
      if (ids.length > 0) {
        for (const id of ids) {
          const req = reqMap.get(id);
          if (req) lines.push(`- **[${req.id}]** [${req.importance}] ${req.item}: ${req.description}`);
        }
        lines.push('');
      }
      if (node.children.length > 0) renderMapping(node.children, depth + 1);
    }
  };
  renderMapping(outline, 0);

  // 미배치
  if (unmapped.length > 0) {
    lines.push(`## 미배치 요구사항 (${unmapped.length}개)\n`);
    for (const req of unmapped) {
      lines.push(`- **[${req.id}]** [${req.importance}] ${req.item}: ${req.description}`);
    }
  }

  return lines.join('\n');
}

function downloadMarkdown(content: string, filename: string) {
  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

// ─── DocPicker (질의서 페이지에서 재사용 가능한 패턴) ───
function DocPicker({ label, description, icon, selectedItems, onToggle, onRemove }: {
  label: string; description: string; icon: React.ReactNode;
  selectedItems: DocItem[]; onToggle: (doc: DocItem) => void; onRemove: (id: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [results, setResults] = useState<DocItem[]>([]);
  const [searching, setSearching] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout>>(undefined);
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

interface StepIndicatorProps {
  current: number;
  maxReached: number;
  onStepClick: (step: number) => void;
  title?: string | null;
  jobSettings?: { customerDocs: DocItem[]; refDocs: DocItem[]; userInput: string; includeWebSearch: boolean; templateName?: string } | null;
}

function StepIndicator({ current, maxReached, onStepClick, title, jobSettings }: StepIndicatorProps) {
  const [showSettings, setShowSettings] = useState(false);

  return (
    <div className="flex items-center gap-2 mb-6">
      {/* 스텝 표시 */}
      <div className="flex items-center gap-1">
        {STEPS.map((step, i) => {
          const isVisited = step.num <= maxReached;
          const isCurrent = step.num === current;
          const canClick = isVisited;
          return (
            <div key={step.num} className="flex items-center gap-1">
              <button
                disabled={!canClick}
                onClick={() => canClick && onStepClick(step.num)}
                className={`flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium transition-colors ${
                  isCurrent ? 'bg-primary text-primary-foreground' :
                  isVisited ? 'bg-primary/20 text-primary hover:bg-primary/30 cursor-pointer' :
                  'bg-muted text-muted-foreground'
                } ${canClick && !isCurrent ? 'cursor-pointer' : ''}`}
              >
                {isVisited && !isCurrent ? <CheckCircle2 className="h-3.5 w-3.5" /> : <Circle className="h-3.5 w-3.5" />}
                {step.label}
              </button>
              {i < STEPS.length - 1 && <ArrowRight className="h-3.5 w-3.5 text-muted-foreground" />}
            </div>
          );
        })}
      </div>

      {/* 작업 제목 */}
      {title && (
        <div className="flex-1 min-w-0 px-3">
          <p className="text-sm font-medium truncate text-muted-foreground" title={title}>{title}</p>
        </div>
      )}

      {/* 작업 설정 확인 버튼 */}
      {jobSettings && (
        <>
          <Button variant="ghost" size="sm" className="shrink-0 text-xs text-muted-foreground h-7" onClick={() => setShowSettings(true)}>
            <FileText className="h-3.5 w-3.5 mr-1" />설정 확인
          </Button>

          {/* 설정 모달 */}
          {showSettings && (
            <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={() => setShowSettings(false)}>
              <div className="bg-background rounded-lg shadow-xl max-w-lg w-full mx-4 max-h-[80vh] overflow-y-auto" onClick={e => e.stopPropagation()}>
                <div className="flex items-center justify-between p-4 border-b">
                  <h3 className="font-semibold">작업 설정 정보</h3>
                  <button onClick={() => setShowSettings(false)} className="p-1 rounded hover:bg-muted cursor-pointer"><X className="h-4 w-4" /></button>
                </div>
                <div className="p-4 space-y-4 text-sm">
                  {jobSettings.templateName && (
                    <div>
                      <p className="text-xs font-medium text-muted-foreground mb-1">템플릿</p>
                      <p>{jobSettings.templateName}</p>
                    </div>
                  )}
                  <div>
                    <p className="text-xs font-medium text-muted-foreground mb-1">고객 문서 (RFP)</p>
                    {jobSettings.customerDocs.length > 0 ? (
                      <ul className="space-y-0.5">
                        {jobSettings.customerDocs.map(d => (
                          <li key={d.id} className="flex items-center gap-1.5"><FileText className="h-3.5 w-3.5 text-muted-foreground shrink-0" />{d.filename}</li>
                        ))}
                      </ul>
                    ) : <p className="text-muted-foreground">없음</p>}
                  </div>
                  <div>
                    <p className="text-xs font-medium text-muted-foreground mb-1">참조 문서</p>
                    {jobSettings.refDocs.length > 0 ? (
                      <ul className="space-y-0.5">
                        {jobSettings.refDocs.map(d => (
                          <li key={d.id} className="flex items-center gap-1.5"><FileText className="h-3.5 w-3.5 text-muted-foreground shrink-0" />{d.filename}</li>
                        ))}
                      </ul>
                    ) : <p className="text-muted-foreground">없음</p>}
                  </div>
                  <div>
                    <p className="text-xs font-medium text-muted-foreground mb-1">추가 지시사항</p>
                    <p className={jobSettings.userInput ? '' : 'text-muted-foreground'}>{jobSettings.userInput || '없음'}</p>
                  </div>
                  <div>
                    <p className="text-xs font-medium text-muted-foreground mb-1">웹 검색</p>
                    <p>{jobSettings.includeWebSearch ? '포함' : '미포함'}</p>
                  </div>
                </div>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}

// ─── 메인 페이지 ───
export function GeneratePage() {
  const gen = useGeneration();

  // Step 1 state
  const [selectedTemplateId, setSelectedTemplateId] = useState('');
  const [userInput, setUserInput] = useState('');
  const [customerDocs, setCustomerDocs] = useState<DocItem[]>([]);
  const [refDocs, setRefDocs] = useState<DocItem[]>([]);

  // Wizard state
  const [wizardStep, _setWizardStep] = useState(1);
  const [maxReachedStep, setMaxReachedStep] = useState(1);
  const setWizardStep = (step: number) => {
    _setWizardStep(step);
    setMaxReachedStep(prev => Math.max(prev, step));
  };
  const [outline, setOutline] = useState<OutlineNode[]>([]);
  const [analyzing, setAnalyzing] = useState(false);
  const [analyzeError, setAnalyzeError] = useState<string | null>(null);
  const [jobId, setJobId] = useState<string | null>(null);

  // Step 3 state
  const [requirements, setRequirements] = useState<Requirement[]>([]);
  const [reqMapping, setReqMapping] = useState<Record<string, string[]>>({});
  const [mapping, setMapping] = useState(false);
  const [mappingError, setMappingError] = useState<string | null>(null);

  // Step 4 state
  type SectionData = { key: string; title: string; content: string; highlights: string[]; tables: Array<{ caption: string; headers: string[]; rows: string[][] }>; references: string[]; layoutHint?: string };
  const [sections, setSections] = useState<SectionData[]>([]);
  const [generating, setGenerating] = useState(false);
  const [generatingIndex, setGeneratingIndex] = useState(-1);
  const [generatingKey, setGeneratingKey] = useState<string | null>(null);
  const [totalSectionsCount, setTotalSectionsCount] = useState(0);
  const [generateError, setGenerateError] = useState<string | null>(null);
  const [includeWebSearch, setIncludeWebSearch] = useState(false);
  const [regeneratingKey, setRegeneratingKey] = useState<string | null>(null);
  const [checkedTopKeys, setCheckedTopKeys] = useState<Set<string>>(new Set());

  // 미배치 목차 생성 결과 모달
  const [unmappedResult, setUnmappedResult] = useState<{ key: string; title: string; reqIds: string[] }[] | null>(null);

  // Step 5 state
  const [rendering, setRendering] = useState(false);
  const [renderError, setRenderError] = useState<string | null>(null);
  const [previewHtml, setPreviewHtml] = useState<string | null>(null);

  useEffect(() => {
    gen.loadTemplates();
    gen.loadJobs();
  }, []);

  // 페이지 진입 시 진행 중인 Job이 있으면 상태 복원 + 폴링
  const resumedRef = useRef(false);
  const pollingRef = useRef<ReturnType<typeof setInterval>>(undefined);

  useEffect(() => {
    if (resumedRef.current || gen.jobs.length === 0 || jobId) return;
    const activeStatuses = ['ANALYZING', 'MAPPING', 'GENERATING', 'RENDERING'];
    const activeJob = gen.jobs.find(j => activeStatuses.includes(j.status));
    if (!activeJob) return;
    resumedRef.current = true;

    // 상태 복원
    setJobId(activeJob.id);
    if (activeJob.customerDocuments?.length > 0) {
      setCustomerDocs(activeJob.customerDocuments.map(d => ({ id: d.id, filename: d.filename, chunkCount: d.chunkCount })));
    }
    if (activeJob.referenceDocuments?.length > 0) {
      setRefDocs(activeJob.referenceDocuments.map(d => ({ id: d.id, filename: d.filename, chunkCount: d.chunkCount })));
    }
    if (activeJob.templateId) setSelectedTemplateId(activeJob.templateId);
    if (activeJob.userInput) setUserInput(activeJob.userInput);
    if (activeJob.outline) {
      try { setOutline(JSON.parse(activeJob.outline)); } catch { /* ignore */ }
    }
    if (activeJob.requirementMapping) {
      try {
        const parsed = JSON.parse(activeJob.requirementMapping);
        setRequirements(parsed.requirements || []);
        setReqMapping(parsed.mapping || {});
      } catch { /* ignore */ }
    }
    if (activeJob.generatedSections) {
      try { setSections(JSON.parse(activeJob.generatedSections)); } catch { /* ignore */ }
    }
    setTotalSectionsCount(activeJob.totalSections);
    setIncludeWebSearch(activeJob.includeWebSearch);
    if (activeJob.currentSection > 0) setGeneratingIndex(activeJob.currentSection - 1);

    // 진행 중 플래그 + 위자드 스텝 설정
    if (activeJob.status === 'ANALYZING') { setAnalyzing(true); setWizardStep(2); }
    else if (activeJob.status === 'MAPPING') { setMapping(true); setWizardStep(3); }
    else if (activeJob.status === 'GENERATING') { setGenerating(true); setWizardStep(4); }
    else if (activeJob.status === 'RENDERING') { setRendering(true); setWizardStep(5); }

    // SSE 재연결 — 실시간 progress 이벤트 수신 (generatingKey 포함)
    const token = localStorage.getItem('accessToken');
    const sseUrl = getStreamUrl(activeJob.id) + (token ? `?token=${token}` : '');
    const resumeEs = new EventSource(sseUrl);

    resumeEs.addEventListener('progress', (e) => {
      try {
        const data: GenerationProgressEvent = JSON.parse(e.data);
        if (data.currentSection != null) {
          setGeneratingIndex(data.currentSection - 1);
          setGeneratingKey(data.sectionKey ?? null);
          setTotalSectionsCount(data.totalSections ?? 0);
        }
        fetchJob(activeJob.id).then(updated => {
          if (updated.generatedSections) {
            try { setSections(JSON.parse(updated.generatedSections)); } catch { /* ignore */ }
          }
        }).catch(() => {});
      } catch { /* ignore */ }
    });

    resumeEs.addEventListener('requirements', (e) => {
      try { setRequirements(JSON.parse((e as MessageEvent).data)); } catch { /* ignore */ }
    });

    resumeEs.addEventListener('complete', () => {
      resumeEs.close();
      if (pollingRef.current) clearInterval(pollingRef.current);
      fetchJob(activeJob.id).then(updated => {
        if (updated.generatedSections) {
          try { setSections(JSON.parse(updated.generatedSections)); } catch { /* ignore */ }
        }
        if (updated.outline) {
          try { setOutline(JSON.parse(updated.outline)); } catch { /* ignore */ }
        }
        if (updated.requirementMapping) {
          try {
            const parsed = JSON.parse(updated.requirementMapping);
            setRequirements(parsed.requirements || []);
            setReqMapping(parsed.mapping || {});
          } catch { /* ignore */ }
        }
        setAnalyzing(false); setMapping(false); setGenerating(false); setRendering(false);
        setGeneratingIndex(-1); setGeneratingKey(null);
        if (updated.status === 'COMPLETE') {
          setWizardStep(5);
          const tk = localStorage.getItem('accessToken') || '';
          fetch(getPreviewUrl(activeJob.id) + (tk ? `?token=${tk}` : ''), { headers: { 'Authorization': `Bearer ${tk}` } })
            .then(r => r.ok ? r.text() : null).then(html => { if (html) setPreviewHtml(html); }).catch(() => {});
        } else if (updated.generatedSections) {
          setWizardStep(4);
        } else if (updated.requirementMapping) {
          setWizardStep(3);
        } else if (updated.outline) {
          setWizardStep(2);
        }
        gen.loadJobs();
      });
    });

    resumeEs.addEventListener('error', (e) => {
      try {
        const data: GenerationProgressEvent = JSON.parse((e as MessageEvent).data);
        setGenerateError(data.message || '생성 중 오류');
      } catch { /* ignore */ }
      resumeEs.close();
      setAnalyzing(false); setMapping(false); setGenerating(false); setRendering(false);
      setGeneratingIndex(-1); setGeneratingKey(null);
      gen.loadJobs();
    });

    resumeEs.onerror = () => {
      // SSE 연결 실패 시 폴링으로 폴백 (아래에서 계속)
    };

    // 폴링으로 진행 추적 (5초 간격) — SSE 폴백 + 중간 결과 동기화
    pollingRef.current = setInterval(async () => {
      try {
        const updated = await fetchJob(activeJob.id);
        // 중간 결과 반영
        if (updated.generatedSections) {
          try { setSections(JSON.parse(updated.generatedSections)); } catch { /* ignore */ }
        }
        if (updated.currentSection > 0) {
          setGeneratingIndex(updated.currentSection - 1);
          setTotalSectionsCount(updated.totalSections);
        }

        // 완료/실패 감지
        if (updated.status === 'DRAFT' || updated.status === 'READY') {
          clearInterval(pollingRef.current);
          resumeEs.close();
          if (updated.outline) {
            try { setOutline(JSON.parse(updated.outline)); } catch { /* ignore */ }
          }
          if (updated.requirementMapping) {
            try {
              const parsed = JSON.parse(updated.requirementMapping);
              setRequirements(parsed.requirements || []);
              setReqMapping(parsed.mapping || {});
            } catch { /* ignore */ }
          }
          setAnalyzing(false); setMapping(false); setGenerating(false); setRendering(false);
          setGeneratingIndex(-1); setGeneratingKey(null);
          if (updated.generatedSections) setWizardStep(4);
          else if (updated.requirementMapping) setWizardStep(3);
          else if (updated.outline) setWizardStep(2);
          gen.loadJobs();
        } else if (updated.status === 'COMPLETE') {
          clearInterval(pollingRef.current);
          resumeEs.close();
          setAnalyzing(false); setMapping(false); setGenerating(false); setRendering(false);
          setGeneratingIndex(-1); setGeneratingKey(null);
          setWizardStep(5);
          const tk = localStorage.getItem('accessToken') || '';
          try {
            const res = await fetch(getPreviewUrl(activeJob.id) + (tk ? `?token=${tk}` : ''), { headers: { 'Authorization': `Bearer ${tk}` } });
            if (res.ok) setPreviewHtml(await res.text());
          } catch { /* ignore */ }
          gen.loadJobs();
        } else if (updated.status === 'FAILED') {
          clearInterval(pollingRef.current);
          resumeEs.close();
          setAnalyzing(false); setMapping(false); setGenerating(false); setRendering(false);
          setGeneratingIndex(-1); setGeneratingKey(null);
          setGenerateError(updated.errorMessage || '생성 실패');
          gen.loadJobs();
        }
      } catch { /* polling error — ignore, will retry */ }
    }, 3000);

    return () => { if (pollingRef.current) clearInterval(pollingRef.current); };
  }, [gen.jobs]);

  // 컴포넌트 언마운트 시 폴링 정리
  useEffect(() => {
    return () => { if (pollingRef.current) clearInterval(pollingRef.current); };
  }, []);

  const selectedTemplate = gen.templates.find(t => t.id === selectedTemplateId);
  const toggleDoc = (list: DocItem[], setList: (v: DocItem[]) => void, doc: DocItem) => {
    setList(list.some(d => d.id === doc.id) ? list.filter(d => d.id !== doc.id) : [...list, doc]);
  };

  // Step 1 → Step 2: 작업 생성 + 목차 추출
  const handleAnalyze = async () => {
    if (!jobId && (!selectedTemplateId || customerDocs.length === 0)) return;
    setAnalyzing(true);
    setAnalyzeError(null);
    try {
      // 기존 job이 없을 때만 새로 생성
      let currentJobId = jobId;
      if (!currentJobId) {
        const { startGeneration: apiCreate } = await import('@/api/generation');
        const job = await apiCreate({
          templateId: selectedTemplateId,
          userInput: userInput.trim(),
          customerDocumentIds: customerDocs.map(d => d.id),
          referenceDocumentIds: refDocs.length > 0 ? refDocs.map(d => d.id) : undefined,
          includeWebSearch,
        });
        currentJobId = job.id;
        setJobId(currentJobId);
        gen.loadJobs();
      }

      // 목차 추출 시작 (customerDocs가 있으면 사용, 없으면 빈 배열 — 백엔드가 기존 쿼리로 처리)
      await startOutlineExtraction(currentJobId, customerDocs.length > 0 ? customerDocs.map(d => d.id) : []);

      // SSE로 완료 대기
      const token = localStorage.getItem('accessToken');
      const url = getStreamUrl(currentJobId) + (token ? `?token=${token}` : '');
      const es = new EventSource(url);

      // Step 2에서 요구사항이 실시간으로 추출됨
      es.addEventListener('requirements', (e) => {
        try {
          const partialReqs = JSON.parse((e as MessageEvent).data);
          setRequirements(partialReqs);
        } catch { /* ignore */ }
      });

      es.addEventListener('complete', () => {
        es.close();
        import('@/api/generation').then(({ fetchJob }) =>
          fetchJob(currentJobId!).then(updated => {
            if (updated.outline) {
              try { setOutline(JSON.parse(updated.outline)); } catch { /* ignore */ }
            }
            // Step 2에서 추출된 요구사항도 복원
            if (updated.requirementMapping) {
              try {
                const parsed = JSON.parse(updated.requirementMapping);
                setRequirements(parsed.requirements || []);
              } catch { /* ignore */ }
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
      const msg = e instanceof Error ? e.message : '분석 실패';
      setAnalyzeError(msg);
      toast.error('목차 추출 실패', { description: msg });
      setAnalyzing(false);
    }
  };

  // Step 2 → Step 3: 목차 저장 + 요구사항 매핑 시작
  const handleSaveOutline = async () => {
    if (!jobId) return;
    await saveOutline(jobId, outline);
    // 매핑이 이미 완료된 경우에만 이동만 (매핑 데이터가 있고 실제 배치가 있을 때)
    if (requirements.length > 0 && Object.values(reqMapping).some(ids => ids.length > 0)) {
      setWizardStep(3);
    } else {
      // 요구사항은 있지만 매핑이 안 됐거나, 요구사항이 없으면 매핑 시작
      handleStartMapping();
    }
  };

  // Step 3: 요구사항 매핑
  const handleStartMapping = async () => {
    if (!jobId) return;
    setMapping(true);
    setMappingError(null);
    setRequirements([]);
    setReqMapping({});
    setWizardStep(3);
    try {
      await startRequirementMapping(jobId, customerDocs.map(d => d.id));

      const token = localStorage.getItem('accessToken');
      const url = getStreamUrl(jobId) + (token ? `?token=${token}` : '');
      const es = new EventSource(url);

      // 배치별 부분 결과 수신 — 요구사항이 실시간으로 추가됨
      es.addEventListener('requirements', (e) => {
        try {
          const partialReqs = JSON.parse((e as MessageEvent).data);
          setRequirements(partialReqs);
        } catch { /* ignore */ }
      });

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
      const msg = e instanceof Error ? e.message : '매핑 실패';
      setMappingError(msg);
      toast.error('요구사항 매핑 실패', { description: msg });
      setMapping(false);
    }
  };

  // Step 3 → Step 4: 매핑 저장 + 미완료분 이어서 생성
  const handleSaveMapping = async () => {
    if (!jobId) return;
    await saveRequirementMapping(jobId, { requirements, mapping: reqMapping });
    // Step 4로 이동만 — 자동 생성하지 않음 (사용자가 선택 후 생성)
    setWizardStep(4);
    // 대분류 전체 체크 초기화
    setCheckedTopKeys(new Set(outline.map(n => n.key)));
  };

  // Step 4: 섹션 생성
  const handleStartSectionGeneration = async (filterKeys?: string[], forceRegenerate?: boolean) => {
    if (!jobId) return;
    setGenerating(true);
    setGenerateError(null);
    if (!filterKeys || filterKeys.length === 0) {
      // 전체 재생성 시에만 섹션 초기화
      setSections([]);
    }
    setGeneratingIndex(0);
    setWizardStep(4);
    try {
      const job = gen.jobs.find(j => j.id === jobId);
      const webSearch = job?.includeWebSearch ?? includeWebSearch;
      const refIds = refDocs.length > 0 ? refDocs.map(d => d.id) : undefined;
      await startSectionGeneration(jobId, refIds, webSearch, filterKeys, forceRegenerate);

      const token = localStorage.getItem('accessToken');
      const url = getStreamUrl(jobId) + (token ? `?token=${token}` : '');
      const es = new EventSource(url);

      es.addEventListener('progress', (e) => {
        const data: GenerationProgressEvent = JSON.parse(e.data);
        if (data.currentSection != null && data.totalSections != null) {
          setGeneratingIndex(data.currentSection - 1);
          setGeneratingKey(data.sectionKey ?? null);
          setTotalSectionsCount(data.totalSections);

          // 이전 섹션이 완료되었으므로 중간 결과를 조회
          fetchJob(jobId).then(updated => {
            if (updated.generatedSections) {
              try { setSections(JSON.parse(updated.generatedSections)); } catch { /* ignore */ }
            }
          }).catch(() => {});
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
          setGeneratingIndex(-1); setGeneratingKey(null);
          gen.loadJobs();
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
        setGeneratingIndex(-1); setGeneratingKey(null);
      });

      es.onerror = () => {
        if (es.readyState === EventSource.CLOSED) return;
        setGenerateError('서버와의 연결이 끊어졌습니다.');
        es.close();
        setGenerating(false);
        setGeneratingIndex(-1); setGeneratingKey(null);
      };
    } catch (e) {
      const msg = e instanceof Error ? e.message : '생성 실패';
      setGenerateError(msg);
      toast.error('섹션 생성 실패', { description: msg });
      setGenerating(false);
      setGeneratingIndex(-1); setGeneratingKey(null);
    }
  };

  const handleSectionChange = (index: number, updated: SectionData) => {
    setSections(prev => prev.map((s, i) => i === index ? updated : s));
  };

  const handleRegenerateSection = async (sectionKey: string, userInstruction?: string) => {
    if (!jobId) return;
    setRegeneratingKey(sectionKey);
    try {
      const refIds = refDocs.length > 0 ? refDocs.map(d => d.id) : undefined;
      await regenerateSection(jobId, sectionKey, refIds, includeWebSearch, userInstruction);

      const token = localStorage.getItem('accessToken');
      const url = getStreamUrl(jobId) + (token ? `?token=${token}` : '');
      const es = new EventSource(url);

      es.addEventListener('complete', () => {
        es.close();
        fetchJob(jobId).then(updated => {
          if (updated.generatedSections) {
            try { setSections(JSON.parse(updated.generatedSections)); } catch { /* ignore */ }
          }
          setRegeneratingKey(null);
        });
      });

      es.addEventListener('error', (e) => {
        es.close();
        try {
          const data: GenerationProgressEvent = JSON.parse((e as MessageEvent).data);
          setGenerateError(data.message || '섹션 재생성 중 오류');
        } catch {
          setGenerateError('섹션 재생성 중 오류');
        }
        setRegeneratingKey(null);
      });

      es.onerror = () => {
        if (es.readyState === EventSource.CLOSED) return;
        setRegeneratingKey(null);
      };
    } catch {
      setRegeneratingKey(null);
    }
  };

  // Step 5: 렌더링 시작
  const handleStartRendering = async () => {
    if (!jobId) return;
    setRendering(true);
    setRenderError(null);
    setPreviewHtml(null);
    setWizardStep(5);
    try {
      await startRendering(jobId);

      const token = localStorage.getItem('accessToken');
      const url = getStreamUrl(jobId) + (token ? `?token=${token}` : '');
      const es = new EventSource(url);

      es.addEventListener('complete', () => {
        es.close();
        // 미리보기 HTML 로드
        const previewUrl = getPreviewUrl(jobId) + (token ? `?token=${token}` : '');
        fetch(previewUrl, { headers: { 'Authorization': `Bearer ${token}` } })
          .then(r => r.ok ? r.text() : Promise.reject('Failed'))
          .then(html => setPreviewHtml(html))
          .catch(() => setRenderError('미리보기 로드에 실패했습니다.'));
        setRendering(false);
        gen.loadJobs();
      });

      es.addEventListener('error', (e) => {
        try {
          const data: GenerationProgressEvent = JSON.parse((e as MessageEvent).data);
          setRenderError(data.message || '렌더링 중 오류가 발생했습니다.');
        } catch {
          setRenderError('렌더링 중 오류가 발생했습니다.');
        }
        es.close();
        setRendering(false);
      });

      es.onerror = () => {
        if (es.readyState === EventSource.CLOSED) return;
        setRenderError('서버와의 연결이 끊어졌습니다.');
        es.close();
        setRendering(false);
      };
    } catch (e) {
      const msg = e instanceof Error ? e.message : '렌더링 실패';
      setRenderError(msg);
      toast.error('렌더링 실패', { description: msg });
      setRendering(false);
    }
  };

  // 이력에서 job 클릭 시 상태 복원
  const handleLoadJob = async (job: GenerationJob) => {
    // 이전 Job 상태 완전 초기화 후 복원
    setJobId(job.id);
    setOutline([]);
    setRequirements([]);
    setReqMapping({});
    setSections([]);
    setPreviewHtml(null);
    setTotalSectionsCount(0);
    setGeneratingIndex(-1); setGeneratingKey(null);
    setRegeneratingKey(null);
    setAnalyzeError(null);
    setMappingError(null);
    setGenerateError(null);
    setRenderError(null);
    setAnalyzing(false);
    setMapping(false);
    setGenerating(false);
    setRendering(false);

    // 문서 목록 복원
    setCustomerDocs((job.customerDocuments ?? []).map(d => ({ id: d.id, filename: d.filename, chunkCount: d.chunkCount })));
    setRefDocs((job.referenceDocuments ?? []).map(d => ({ id: d.id, filename: d.filename, chunkCount: d.chunkCount })));
    setSelectedTemplateId(job.templateId || '');
    setUserInput(job.userInput || '');
    setIncludeWebSearch(job.includeWebSearch);

    // outline 복원
    if (job.outline) {
      try { setOutline(JSON.parse(job.outline)); } catch { /* ignore */ }
    }

    // 요구사항 매핑 복원
    if (job.requirementMapping) {
      try {
        const parsed = JSON.parse(job.requirementMapping);
        setRequirements(parsed.requirements || []);
        setReqMapping(parsed.mapping || {});
      } catch { /* ignore */ }
    }

    // 섹션 복원
    if (job.generatedSections) {
      try { setSections(JSON.parse(job.generatedSections)); } catch { /* ignore */ }
    }

    // 미리보기 복원
    if (job.status === 'COMPLETE' && job.outputFilePath) {
      const token = localStorage.getItem('accessToken') || '';
      try {
        const res = await fetch(getPreviewUrl(job.id) + (token ? `?token=${token}` : ''), { headers: { 'Authorization': `Bearer ${token}` } });
        if (res.ok) setPreviewHtml(await res.text());
      } catch { /* ignore */ }
    }

    // Step 1 설정 복원
    setSelectedTemplateId(job.templateId);
    if (job.userInput) setUserInput(job.userInput);
    setIncludeWebSearch(job.includeWebSearch);
    setTotalSectionsCount(job.totalSections);

    // 현재 step 결정 + maxReached 설정
    let targetStep = 1;
    if (job.status === 'COMPLETE') targetStep = 5;
    else if (job.generatedSections) { targetStep = 4; setTotalSectionsCount(job.totalSections); }
    else if (job.requirementMapping) targetStep = 3;
    else if (job.outline) targetStep = 2;
    setMaxReachedStep(targetStep);
    setWizardStep(targetStep);
  };

  const handleDownload = async () => {
    if (!jobId) return;
    const token = localStorage.getItem('accessToken') || '';
    const res = await fetch(getDownloadUrl(jobId), { headers: { 'Authorization': `Bearer ${token}` } });
    if (!res.ok) return;
    const blob = await res.blob();
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `proposal-${new Date().toISOString().slice(0, 10)}.html`;
    a.click();
    window.URL.revokeObjectURL(url);
  };

  const handleReset = () => {
    gen.reset();
    setUserInput('');
    setCustomerDocs([]);
    setRefDocs([]);
    setOutline([]);
    setJobId(null);
    setMaxReachedStep(1);
    setWizardStep(1);
    setAnalyzing(false);
    setAnalyzeError(null);
    setRequirements([]);
    setReqMapping({});
    setMapping(false);
    setMappingError(null);
    setSections([]);
    setGenerating(false);
    setGeneratingIndex(-1); setGeneratingKey(null);
    setTotalSectionsCount(0);
    setGenerateError(null);
    setIncludeWebSearch(false);
    setRegeneratingKey(null);
    setRendering(false);
    setRenderError(null);
    setPreviewHtml(null);
  };

  return (
    <div className="flex h-screen bg-background">
      <AppSidebar>
        <div className="flex items-center gap-2 px-3 pt-3 pb-1">
          <h2 className="text-sm font-medium flex-1">생성 이력</h2>
          <Button variant="outline" size="sm" className="h-7 text-xs" onClick={handleReset}>
            <Plus className="h-3.5 w-3.5 mr-1" />새로 만들기
          </Button>
        </div>
        <div className="flex-1 overflow-y-auto p-3 space-y-1">
          {gen.jobs.length === 0 && <p className="text-xs text-sidebar-foreground/40 px-2">아직 생성된 문서가 없습니다.</p>}
          {gen.jobs.map(job => <JobHistoryItem key={job.id} job={job} onDelete={gen.removeJob} onLoad={handleLoadJob} onRename={async (id, title) => { await updateJobTitle(id, title); gen.loadJobs(); }} />)}
        </div>
      </AppSidebar>

      {/* 메인 위자드 영역 */}
      <main className="flex-1 overflow-y-auto">
        <div className="max-w-5xl mx-auto py-8 px-6">
          <StepIndicator
            current={wizardStep}
            maxReached={maxReachedStep}
            onStepClick={setWizardStep}
            title={jobId ? (gen.jobs.find(j => j.id === jobId)?.title ?? null) : null}
            jobSettings={jobId ? (() => {
              const job = gen.jobs.find(j => j.id === jobId);
              const cDocs = customerDocs.length > 0 ? customerDocs
                : (job?.customerDocuments ?? []).map(d => ({ id: d.id, filename: d.filename, chunkCount: d.chunkCount }));
              const rDocs = refDocs.length > 0 ? refDocs
                : (job?.referenceDocuments ?? []).map(d => ({ id: d.id, filename: d.filename, chunkCount: d.chunkCount }));
              return {
                customerDocs: cDocs,
                refDocs: rDocs,
                userInput: userInput || job?.userInput || '',
                includeWebSearch: includeWebSearch || (job?.includeWebSearch ?? false),
                templateName: gen.templates.find(t => t.id === (selectedTemplateId || job?.templateId))?.name || job?.templateName,
              };
            })() : null}
          />

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

                {/* 웹 검색 옵션 */}
                <div className="flex items-start gap-2 p-3 rounded-lg border bg-muted/20">
                  <Checkbox id="genWebSearch" checked={includeWebSearch} onCheckedChange={checked => setIncludeWebSearch(checked === true)} className="mt-0.5" />
                  <div>
                    <label htmlFor="genWebSearch" className="text-sm font-medium cursor-pointer">웹 검색 포함</label>
                    <p className="text-xs text-muted-foreground mt-0.5">섹션 내용 생성 시 업계 동향, 기술 트렌드 등 외부 정보를 검색하여 더 풍부한 내용을 작성합니다.</p>
                  </div>
                </div>

                {/* 분석 시작 / 다음 버튼 */}
                <div className="flex justify-end">
                  {outline.length > 0 ? (
                    <Button onClick={() => setWizardStep(2)}>
                      <ArrowRight className="h-4 w-4 mr-1.5" />다음 (목차 확인)
                    </Button>
                  ) : (
                    <Button onClick={handleAnalyze} disabled={!selectedTemplateId || customerDocs.length === 0 || analyzing}>
                      {analyzing ? <Loader2 className="h-4 w-4 mr-1.5 animate-spin" /> : <ArrowRight className="h-4 w-4 mr-1.5" />}
                      {analyzing ? '요구사항 및 목차 추출 중...' : '요구사항 및 목차 추출'}
                    </Button>
                  )}
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
                {analyzing ? (
                  <div className="flex items-center gap-3 py-12 justify-center">
                    <Loader2 className="h-5 w-5 animate-spin text-primary" />
                    <span className="text-sm text-muted-foreground">고객 문서에서 목차를 추출하고 있습니다...</span>
                  </div>
                ) : outline.length === 0 ? (
                  <p className="text-sm text-muted-foreground py-8 text-center">추출된 목차가 없습니다.</p>
                ) : (
                  <div className="border rounded-lg p-4 bg-muted/20">
                    <OutlineEditor outline={outline} onChange={setOutline} />
                  </div>
                )}

                <div className="flex justify-between">
                  <div className="flex gap-2">
                    <Button variant="outline" onClick={() => setWizardStep(1)} disabled={analyzing}>
                      <ArrowLeft className="h-4 w-4 mr-1.5" />이전
                    </Button>
                    <Button variant="outline" onClick={handleAnalyze} disabled={analyzing}>
                      {analyzing ? <Loader2 className="h-4 w-4 mr-1.5 animate-spin" /> : <RefreshCw className="h-4 w-4 mr-1.5" />}
                      {analyzing ? '추출 중...' : '목차 재추출'}
                    </Button>
                    {outline.length > 0 && (
                      <Button variant="outline" onClick={() => downloadMarkdown(buildOutlineMarkdown(outline), '목차.md')}>
                        <Download className="h-4 w-4 mr-1.5" />목차 다운로드
                      </Button>
                    )}
                  </div>
                  <Button onClick={handleSaveOutline} disabled={analyzing || outline.length === 0}>
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
                <CardDescription>
                  {mapping
                    ? 'AI가 요구사항을 추출하고 목차에 배치하고 있습니다. 잠시만 기다려 주세요.'
                    : 'AI가 추출한 요구사항을 목차에 매핑했습니다. 좌측 목차를 클릭하면 배치된 요구사항을 확인할 수 있습니다.'}
                </CardDescription>
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
                    onGenerateUnmapped={jobId ? async () => {
                      try {
                        const prevKeys = new Set<string>();
                        const collectKeys = (nodes: OutlineNode[]) => { for (const n of nodes) { prevKeys.add(n.key); collectKeys(n.children); } };
                        collectKeys(outline);

                        const updated = await generateUnmappedSections(jobId);

                        let newOutline: OutlineNode[] = [];
                        if (updated.outline) {
                          try { newOutline = JSON.parse(updated.outline); setOutline(newOutline); } catch { /* ignore */ }
                        }
                        let newMapping: Record<string, string[]> = {};
                        if (updated.requirementMapping) {
                          try {
                            const parsed = JSON.parse(updated.requirementMapping);
                            setRequirements(parsed.requirements || []);
                            newMapping = parsed.mapping || {};
                            setReqMapping(newMapping);
                          } catch { /* ignore */ }
                        }

                        // 새로 추가된 섹션 정보 수집
                        const added: { key: string; title: string; reqIds: string[] }[] = [];
                        const findNew = (nodes: OutlineNode[]) => {
                          for (const n of nodes) {
                            if (!prevKeys.has(n.key) && n.children.length === 0) {
                              added.push({ key: n.key, title: n.title, reqIds: newMapping[n.key] || [] });
                            }
                            findNew(n.children);
                          }
                        };
                        findNew(newOutline);

                        if (added.length > 0) {
                          setUnmappedResult(added);
                        } else {
                          toast.info('새로운 섹션이 생성되지 않았습니다.');
                        }
                      } catch (e) {
                        toast.error('목차 자동 생성 실패', { description: e instanceof Error ? e.message : '서버 오류가 발생했습니다.' });
                      }
                    } : undefined}
                  />
                )}

                {mappingError && (
                  <div className="flex items-center gap-2 text-destructive text-sm bg-destructive/10 rounded-md p-3">
                    <AlertCircle className="h-4 w-4 shrink-0" />{mappingError}
                  </div>
                )}

                <div className="flex justify-between">
                  <div className="flex gap-2">
                    <Button variant="outline" onClick={() => setWizardStep(2)}>
                      <ArrowLeft className="h-4 w-4 mr-1.5" />이전 (목차)
                    </Button>
                    <Button variant="outline" onClick={handleStartMapping} disabled={mapping}>
                      <RefreshCw className="h-4 w-4 mr-1.5" />요구사항 재배치
                    </Button>
                    {requirements.length > 0 && (
                      <Button variant="outline" onClick={() => downloadMarkdown(
                        buildRequirementMarkdown(outline, requirements, reqMapping), '요구사항_배치.md'
                      )}>
                        <Download className="h-4 w-4 mr-1.5" />다운로드
                      </Button>
                    )}
                  </div>
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
                  outline={outline}
                  generatingIndex={generating ? generatingIndex : -1}
                  generatingKey={generating ? generatingKey : null}
                  totalSections={totalSectionsCount || sections.length}
                  regeneratingKey={regeneratingKey}
                  checkedKeys={checkedTopKeys}
                  onCheckedChange={setCheckedTopKeys}
                  onSectionChange={handleSectionChange}
                  onRegenerate={handleRegenerateSection}
                />

                {generateError && (
                  <div className="flex items-center gap-2 text-destructive text-sm bg-destructive/10 rounded-md p-3">
                    <AlertCircle className="h-4 w-4 shrink-0" />{generateError}
                  </div>
                )}

                <div className="flex justify-between">
                  <div className="flex gap-2">
                    <Button variant="outline" onClick={() => setWizardStep(3)} disabled={generating}>
                      <ArrowLeft className="h-4 w-4 mr-1.5" />이전 (요구사항)
                    </Button>
                    <Button variant="outline" onClick={async () => { if (jobId) await clearSections(jobId); setSections([]); handleStartSectionGeneration(); }} disabled={generating}>
                      <RefreshCw className="h-4 w-4 mr-1.5" />전체 재생성
                    </Button>
                    <Button
                      variant="outline"
                      onClick={() => {
                        const keys = Array.from(checkedTopKeys);
                        const hasExisting = sections.some(s => keys.some(k => s.key === k || s.key.startsWith(k + '.')));
                        if (hasExisting) {
                          if (window.confirm('이미 생성된 상세 내용이 있습니다. 삭제하고 재생성하시겠습니까?')) {
                            handleStartSectionGeneration(keys, true);
                          }
                        } else {
                          handleStartSectionGeneration(keys);
                        }
                      }}
                      disabled={generating || checkedTopKeys.size === 0}
                    >
                      <CheckCircle2 className="h-4 w-4 mr-1.5" />선택 생성 ({checkedTopKeys.size})
                    </Button>
                  </div>
                  <Button onClick={handleStartRendering} disabled={generating || sections.length === 0}>
                    <ArrowRight className="h-4 w-4 mr-1.5" />렌더링 & 미리보기
                  </Button>
                </div>
              </CardContent>
            </Card>
          )}

          {/* ── Step 5: 미리보기 & 출력 ── */}
          {wizardStep === 5 && (
            <Card>
              <CardHeader>
                <CardTitle>미리보기 & 출력</CardTitle>
                <CardDescription>
                  {rendering ? '문서를 렌더링하고 있습니다...' :
                    previewHtml ? '렌더링이 완료되었습니다. 미리보기를 확인하고 다운로드하세요.' :
                    '렌더링을 시작합니다.'}
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-4">
                {rendering && (
                  <div className="flex items-center gap-3 py-12 justify-center">
                    <Loader2 className="h-5 w-5 animate-spin text-primary" />
                    <span className="text-sm text-muted-foreground">HTML 문서를 렌더링하고 있습니다...</span>
                  </div>
                )}

                {previewHtml && (
                  <div className="border rounded-lg overflow-hidden">
                    <div className="flex items-center justify-between px-3 py-2 bg-muted/50 border-b">
                      <span className="text-xs text-muted-foreground">미리보기</span>
                      <div className="flex gap-2">
                        <Button variant="outline" size="sm" onClick={() => {
                          const token = localStorage.getItem('accessToken') || '';
                          window.open(`${getPreviewUrl(jobId!)}?token=${token}`, '_blank');
                        }} className="h-7 text-xs">
                          새 탭에서 열기
                        </Button>
                        <Button size="sm" onClick={handleDownload} className="h-7 text-xs">
                          <Download className="h-3.5 w-3.5 mr-1" />다운로드
                        </Button>
                      </div>
                    </div>
                    <iframe
                      srcDoc={previewHtml}
                      className="w-full border-0"
                      style={{ height: '600px' }}
                      title="문서 미리보기"
                    />
                  </div>
                )}

                {renderError && (
                  <div className="flex items-center gap-2 text-destructive text-sm bg-destructive/10 rounded-md p-3">
                    <AlertCircle className="h-4 w-4 shrink-0" />{renderError}
                  </div>
                )}

                <div className="flex justify-between">
                  <Button variant="outline" onClick={async () => {
                    // sections나 outline이 비어있으면 DB에서 복원
                    if (jobId && (sections.length === 0 || outline.length === 0)) {
                      const updated = await fetchJob(jobId);
                      if (updated.outline && outline.length === 0) {
                        try { setOutline(JSON.parse(updated.outline)); } catch { /* ignore */ }
                      }
                      if (updated.generatedSections && sections.length === 0) {
                        try { setSections(JSON.parse(updated.generatedSections)); } catch { /* ignore */ }
                      }
                      setTotalSectionsCount(updated.totalSections);
                    }
                    setWizardStep(4);
                  }}>
                    <ArrowLeft className="h-4 w-4 mr-1.5" />내용 수정으로 돌아가기
                  </Button>
                  <Button variant="outline" onClick={() => { setPreviewHtml(null); handleStartRendering(); }} disabled={rendering}>
                    {rendering ? <Loader2 className="h-4 w-4 mr-1.5 animate-spin" /> : <RefreshCw className="h-4 w-4 mr-1.5" />}
                    다시 렌더링
                  </Button>
                </div>
              </CardContent>
            </Card>
          )}
        </div>
      </main>

      {/* 미배치 목차 생성 결과 모달 */}
      {unmappedResult && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50" onClick={() => setUnmappedResult(null)}>
          <div className="bg-background rounded-lg shadow-xl max-w-lg w-full mx-4 max-h-[80vh] overflow-y-auto" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between p-4 border-b">
              <h3 className="font-semibold flex items-center gap-2">
                <CheckCircle2 className="h-5 w-5 text-green-600" />
                목차 {unmappedResult.length}개 섹션 추가 완료
              </h3>
              <button onClick={() => setUnmappedResult(null)} className="p-1 rounded hover:bg-muted cursor-pointer"><X className="h-4 w-4" /></button>
            </div>
            <div className="p-4 space-y-3">
              {unmappedResult.map(section => (
                <div key={section.key} className="rounded-md border p-3">
                  <div className="flex items-center gap-2 mb-1.5">
                    <span className="font-mono text-xs text-muted-foreground bg-muted px-1.5 py-0.5 rounded">{section.key}</span>
                    <span className="text-sm font-medium">{section.title}</span>
                  </div>
                  {section.reqIds.length > 0 && (
                    <div className="flex flex-wrap gap-1">
                      {section.reqIds.map(id => (
                        <Badge key={id} variant="secondary" className="text-xs">{id}</Badge>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
            <div className="p-4 border-t flex justify-end">
              <Button onClick={() => setUnmappedResult(null)}>확인</Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function JobHistoryItem({ job, onDelete, onLoad, onRename }: { job: GenerationJob; onDelete: (id: string) => void; onLoad: (job: GenerationJob) => void; onRename: (id: string, title: string) => void }) {
  const isProcessing = ['PLANNING', 'GENERATING', 'REVIEWING', 'RENDERING', 'ANALYZING', 'MAPPING'].includes(job.status);
  const statusIcon = job.status === 'COMPLETE' ? <FileText className="h-3.5 w-3.5 text-green-500" />
    : job.status === 'FAILED' ? <AlertCircle className="h-3.5 w-3.5 text-destructive" />
    : isProcessing ? <Loader2 className="h-3.5 w-3.5 animate-spin text-primary" />
    : <Circle className="h-3.5 w-3.5 text-muted-foreground" />;

  const [editing, setEditing] = useState(false);
  const [editValue, setEditValue] = useState(job.title || job.templateName);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => { if (editing) inputRef.current?.select(); }, [editing]);

  const commitRename = () => {
    const trimmed = editValue.trim();
    if (trimmed && trimmed !== (job.title || job.templateName)) {
      onRename(job.id, trimmed);
    }
    setEditing(false);
  };

  const handleClick = async () => {
    if (editing) return;
    try {
      const updated = await fetchJob(job.id);
      onLoad(updated);
    } catch { /* ignore */ }
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
          {editing ? (
            <input
              ref={inputRef}
              value={editValue}
              onChange={e => setEditValue(e.target.value)}
              onBlur={commitRename}
              onKeyDown={e => { if (e.key === 'Enter') commitRename(); if (e.key === 'Escape') { setEditValue(job.title || job.templateName); setEditing(false); } }}
              onClick={e => e.stopPropagation()}
              className="text-sm w-full bg-transparent border-b border-primary outline-none px-0 py-0"
            />
          ) : (
            <p className="text-sm truncate" title={job.title || job.templateName}>{job.title || job.templateName}</p>
          )}
          <div className="flex items-center gap-1 text-xs text-sidebar-foreground/40">
            <Clock className="h-3 w-3" />{new Date(job.createdAt).toLocaleDateString('ko-KR')}
          </div>
        </div>
      </button>
      <button onClick={e => { e.stopPropagation(); setEditValue(job.title || job.templateName); setEditing(true); }}
        className="opacity-0 group-hover:opacity-100 p-1 rounded hover:bg-primary/10 text-muted-foreground hover:text-primary transition-all cursor-pointer shrink-0" title="이름 변경">
        <Pencil className="h-3.5 w-3.5" />
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
