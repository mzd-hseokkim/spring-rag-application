import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useGeneration } from '@/hooks/useGeneration';
import { useDocuments } from '@/hooks/useDocuments';
import { GenerationProgress } from '@/components/generation/GenerationProgress';
import { GenerationResult } from '@/components/generation/GenerationResult';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger } from '@/components/ui/select';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Checkbox } from '@/components/ui/checkbox';
import { ArrowLeft, FileText, Loader2, AlertCircle, Clock, Paperclip, X } from 'lucide-react';
import type { GenerationJob } from '@/api/generation';
import type { Document } from '@/types';
import { getDownloadUrl, getPreviewUrl } from '@/api/generation';

export function GeneratePage() {
  const navigate = useNavigate();
  const gen = useGeneration();
  const { documents } = useDocuments();

  const [selectedTemplateId, setSelectedTemplateId] = useState<string>('');
  const [userInput, setUserInput] = useState('');
  const [selectedDocIds, setSelectedDocIds] = useState<string[]>([]);
  const [showDocPicker, setShowDocPicker] = useState(false);

  useEffect(() => {
    gen.loadTemplates();
    gen.loadJobs();
  }, []);

  const selectedTemplate = gen.templates.find(t => t.id === selectedTemplateId);
  const templateDisplayText = selectedTemplate ? selectedTemplate.name : '템플릿을 선택하세요';
  const completedDocs = documents.filter(d => d.status === 'COMPLETED');
  const selectedDocs = completedDocs.filter(d => selectedDocIds.includes(d.id));

  const handleToggleDoc = (docId: string) => {
    setSelectedDocIds(prev =>
      prev.includes(docId) ? prev.filter(id => id !== docId) : [...prev, docId]
    );
  };

  const handleRemoveDoc = (docId: string) => {
    setSelectedDocIds(prev => prev.filter(id => id !== docId));
  };

  const canStart = selectedTemplateId && (userInput.trim() || selectedDocIds.length > 0);

  const handleStart = () => {
    if (!canStart) return;

    // 첨부 문서 정보를 입력에 포함
    let finalInput = userInput.trim();
    if (selectedDocs.length > 0) {
      const docList = selectedDocs.map(d => d.filename).join(', ');
      const prefix = `[첨부 문서: ${docList}]\n\n`;
      finalInput = prefix + finalInput;
    }

    gen.startGeneration({
      templateId: selectedTemplateId,
      userInput: finalInput,
      options: {
        documentIds: selectedDocIds.length > 0 ? selectedDocIds : undefined,
      },
    });
  };

  const handleReset = () => {
    gen.reset();
    setUserInput('');
    setSelectedDocIds([]);
  };

  const step = gen.currentJob?.status === 'COMPLETE'
    ? 'result'
    : gen.isGenerating
      ? 'progress'
      : 'input';

  return (
    <div className="flex h-screen bg-background">
      {/* 사이드바: 생성 이력 */}
      <aside className="w-72 border-r bg-sidebar text-sidebar-foreground flex flex-col shrink-0">
        <div className="flex items-center gap-2 px-3 pt-3 pb-1">
          <Button variant="ghost" size="icon" className="h-8 w-8" onClick={() => navigate('/')} title="채팅으로 돌아가기">
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <h2 className="text-sm font-medium">문서 생성</h2>
        </div>

        <div className="flex-1 overflow-y-auto p-3 space-y-1">
          <p className="text-xs text-sidebar-foreground/50 mb-2">생성 이력</p>
          {gen.jobs.length === 0 && (
            <p className="text-xs text-sidebar-foreground/40 px-2">아직 생성된 문서가 없습니다.</p>
          )}
          {gen.jobs.map((job) => (
            <JobHistoryItem key={job.id} job={job} />
          ))}
        </div>
      </aside>

      {/* 메인 영역 */}
      <main className="flex-1 overflow-y-auto">
        <div className="max-w-3xl mx-auto py-8 px-6">
          {step === 'input' && (
            <Card>
              <CardHeader>
                <CardTitle>새 문서 생성</CardTitle>
                <CardDescription>템플릿을 선택하고 입력을 작성하면 AI가 문서를 생성합니다.</CardDescription>
              </CardHeader>
              <CardContent className="space-y-5">
                {/* 템플릿 선택 */}
                <div className="space-y-1.5">
                  <label className="text-sm font-medium">템플릿</label>
                  <Select value={selectedTemplateId} onValueChange={(v) => setSelectedTemplateId(v ?? '')}>
                    <SelectTrigger className="w-full">
                      <span className={`truncate ${!selectedTemplate ? 'text-muted-foreground' : ''}`}>
                        {templateDisplayText}
                      </span>
                    </SelectTrigger>
                    <SelectContent>
                      {gen.templates.map((t) => (
                        <SelectItem key={t.id} value={t.id}>
                          <span>{t.name}</span>
                          <span className="ml-2 text-xs text-muted-foreground">{t.outputFormat}</span>
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  {selectedTemplate && (
                    <p className="text-xs text-muted-foreground">{selectedTemplate.description}</p>
                  )}
                </div>

                {/* 문서 첨부 */}
                <div className="space-y-1.5">
                  <div className="flex items-center justify-between">
                    <label className="text-sm font-medium">참고 문서 첨부</label>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setShowDocPicker(!showDocPicker)}
                      className="h-7 text-xs"
                    >
                      <Paperclip className="h-3.5 w-3.5 mr-1" />
                      문서 선택
                    </Button>
                  </div>

                  {/* 선택된 문서 태그 */}
                  {selectedDocs.length > 0 && (
                    <div className="flex flex-wrap gap-1.5">
                      {selectedDocs.map((doc) => (
                        <Badge key={doc.id} variant="secondary" className="gap-1 pr-1">
                          <FileText className="h-3 w-3" />
                          <span className="max-w-[150px] truncate">{doc.filename}</span>
                          <button
                            onClick={() => handleRemoveDoc(doc.id)}
                            className="ml-0.5 rounded-full p-0.5 hover:bg-foreground/10 cursor-pointer"
                          >
                            <X className="h-3 w-3" />
                          </button>
                        </Badge>
                      ))}
                    </div>
                  )}

                  {/* 문서 선택 패널 */}
                  {showDocPicker && (
                    <div className="border rounded-lg p-3 space-y-1 max-h-48 overflow-y-auto bg-muted/30">
                      {completedDocs.length === 0 ? (
                        <p className="text-xs text-muted-foreground py-2 text-center">업로드된 문서가 없습니다.</p>
                      ) : (
                        completedDocs.map((doc) => (
                          <label
                            key={doc.id}
                            className="flex items-center gap-2 py-1.5 px-2 rounded-md hover:bg-accent/50 cursor-pointer text-sm"
                          >
                            <Checkbox
                              checked={selectedDocIds.includes(doc.id)}
                              onCheckedChange={() => handleToggleDoc(doc.id)}
                            />
                            <FileText className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
                            <span className="truncate">{doc.filename}</span>
                            <span className="ml-auto text-xs text-muted-foreground shrink-0">
                              {doc.chunkCount}청크
                            </span>
                          </label>
                        ))
                      )}
                    </div>
                  )}

                  <p className="text-xs text-muted-foreground">
                    이미 업로드된 문서를 선택하면 RAG 검색 시 해당 문서를 우선 참고합니다.
                  </p>
                </div>

                {/* 사용자 입력 */}
                <div className="space-y-1.5">
                  <label className="text-sm font-medium">입력 내용</label>
                  <Textarea
                    value={userInput}
                    onChange={(e) => setUserInput(e.target.value)}
                    placeholder="제안요청서 내용, 보고서 주제, 또는 문서화할 내용을 입력하세요..."
                    rows={10}
                    className="resize-y"
                  />
                  <p className="text-xs text-muted-foreground">{userInput.length}자 입력</p>
                </div>

                {/* 생성 버튼 */}
                <div className="flex justify-end">
                  <Button onClick={handleStart} disabled={!canStart}>
                    <FileText className="h-4 w-4 mr-1.5" />
                    문서 생성 시작
                  </Button>
                </div>

                {gen.error && (
                  <div className="flex items-center gap-2 text-destructive text-sm bg-destructive/10 rounded-md p-3">
                    <AlertCircle className="h-4 w-4 shrink-0" />
                    {gen.error}
                  </div>
                )}
              </CardContent>
            </Card>
          )}

          {step === 'progress' && gen.currentJob && (
            <Card>
              <CardHeader>
                <CardTitle className="flex items-center gap-2">
                  <Loader2 className="h-5 w-5 animate-spin text-primary" />
                  {gen.currentJob.templateName} 생성 중
                </CardTitle>
              </CardHeader>
              <CardContent>
                <GenerationProgress
                  sections={gen.sections}
                  statusMessage={gen.statusMessage}
                  currentSection={gen.currentJob.currentSection}
                  totalSections={gen.currentJob.totalSections}
                />
              </CardContent>
            </Card>
          )}

          {step === 'result' && gen.currentJob && (
            <Card>
              <CardHeader>
                <CardTitle>{gen.currentJob.templateName}</CardTitle>
              </CardHeader>
              <CardContent>
                <GenerationResult
                  jobId={gen.currentJob.id}
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

function JobHistoryItem({ job }: { job: GenerationJob }) {
  const statusIcon = {
    COMPLETE: <FileText className="h-3.5 w-3.5 text-green-500" />,
    FAILED: <AlertCircle className="h-3.5 w-3.5 text-destructive" />,
    PLANNING: <Loader2 className="h-3.5 w-3.5 animate-spin text-primary" />,
    GENERATING: <Loader2 className="h-3.5 w-3.5 animate-spin text-primary" />,
    REVIEWING: <Loader2 className="h-3.5 w-3.5 animate-spin text-primary" />,
    RENDERING: <Loader2 className="h-3.5 w-3.5 animate-spin text-primary" />,
  };

  const handleClick = () => {
    if (job.status === 'COMPLETE') {
      const token = localStorage.getItem('accessToken') || '';
      window.open(`${getPreviewUrl(job.id)}?token=${token}`, '_blank');
    }
  };

  return (
    <button
      onClick={handleClick}
      className="flex items-center gap-2 w-full px-2 py-2 text-left rounded-md hover:bg-sidebar-accent/50 transition-colors cursor-pointer"
    >
      {statusIcon[job.status]}
      <div className="flex-1 min-w-0">
        <p className="text-sm truncate">{job.templateName}</p>
        <div className="flex items-center gap-1 text-xs text-sidebar-foreground/40">
          <Clock className="h-3 w-3" />
          {new Date(job.createdAt).toLocaleDateString('ko-KR')}
        </div>
      </div>
    </button>
  );
}
