import { useState } from 'react';
import { toast } from 'sonner';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import type { LlmModel } from '@/types';

const PURPOSES = ['CHAT', 'EMBEDDING', 'QUERY', 'RERANK', 'EVALUATION'] as const;

type ModelState = {
  models: LlmModel[];
  setDefault: (id: string) => Promise<void>;
  testModel: (id: string) => Promise<any>;
  deleteModel: (id: string) => Promise<void>;
  createModel: (model: Partial<LlmModel>) => Promise<void>;
  discoverOllama: () => Promise<any[]>;
};

export function ModelManagement({ modelState }: { modelState: ModelState }) {
  const { models, setDefault, testModel, deleteModel, createModel, discoverOllama } = modelState;
  const [selectedPurpose, setSelectedPurpose] = useState<string>('CHAT');
  const [showAdd, setShowAdd] = useState(false);

  const filtered = models.filter(m => m.purpose === selectedPurpose);

  const handleTest = async (id: string) => {
    const result = await testModel(id);
    if (result.success) {
      toast.success('모델 테스트 성공');
    } else {
      toast.error(result.message || '모델 테스트 실패');
    }
  };

  const handleDiscover = async () => {
    const discovered = await discoverOllama();
    const existingIds = new Set(models.map(m => m.modelId));
    const newModels = discovered.filter(d => !existingIds.has(d.modelId));
    if (newModels.length === 0) {
      toast.info('새로운 모델이 없습니다.');
      return;
    }
    const names = newModels.map(d => d.modelId).join(', ');
    if (confirm(`다음 모델을 등록할까요?\n${names}`)) {
      for (const d of newModels) {
        const isEmbedding = d.modelId.toLowerCase().includes('embed');
        await createModel({
          provider: 'OLLAMA',
          modelId: d.modelId,
          displayName: d.modelId,
          purpose: isEmbedding ? 'EMBEDDING' : 'CHAT',
          baseUrl: 'http://localhost:11434',
          temperature: isEmbedding ? undefined : 0.3,
        } as Partial<LlmModel>);
      }
    }
  };

  return (
    <div className="space-y-3">
      <h2 className="text-sm font-semibold">모델 관리</h2>

      <div className="flex flex-wrap gap-1">
        {PURPOSES.map(p => (
          <Badge
            key={p}
            variant={selectedPurpose === p ? 'secondary' : 'outline'}
            className="cursor-pointer text-[11px]"
            onClick={() => setSelectedPurpose(p)}
          >
            {p}
          </Badge>
        ))}
      </div>

      <div className="space-y-2">
        {filtered.map(m => (
          <Card key={m.id} size="sm" className={`py-2 px-3 gap-2 ${m.isDefault ? 'bg-primary/5' : ''}`}>
            <div className="flex items-center gap-1.5 flex-wrap">
              <span className="text-sm font-semibold">{m.displayName}</span>
              <span className="text-[11px] text-muted-foreground">{m.provider} · {m.modelId}</span>
              {m.isDefault && <Badge variant="default" className="text-[10px] px-1.5 py-0">기본</Badge>}
            </div>
            <div className="flex gap-1">
              <Button variant="outline" size="xs" onClick={() => handleTest(m.id)}>테스트</Button>
              {!m.isDefault && <Button variant="outline" size="xs" onClick={() => setDefault(m.id)}>기본 설정</Button>}
              {!m.isDefault && <Button variant="destructive" size="xs" onClick={() => deleteModel(m.id)}>삭제</Button>}
            </div>
          </Card>
        ))}
        {filtered.length === 0 && (
          <p className="text-sm text-muted-foreground text-center py-3">등록된 모델 없음</p>
        )}
      </div>

      <div className="flex gap-1.5">
        <Button variant="outline" size="sm" className="flex-1" onClick={() => setShowAdd(!showAdd)}>+ 모델 추가</Button>
        <Button variant="outline" size="sm" className="flex-1" onClick={handleDiscover}>Ollama 자동 검색</Button>
      </div>

      {showAdd && <AddModelForm onSubmit={async (m) => { await createModel(m); setShowAdd(false); }} />}
    </div>
  );
}

function AddModelForm({ onSubmit }: { onSubmit: (m: Partial<LlmModel>) => Promise<void> }) {
  const [provider, setProvider] = useState<'OLLAMA' | 'ANTHROPIC' | 'AZURE_OPENAI'>('OLLAMA');
  const [modelId, setModelId] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [selectedPurposes, setSelectedPurposes] = useState<Set<string>>(new Set(['CHAT']));
  const [baseUrl, setBaseUrl] = useState('http://localhost:11434');
  const [apiKeyRef, setApiKeyRef] = useState('ANTHROPIC_API_KEY');

  const togglePurpose = (p: string) => {
    setSelectedPurposes(prev => {
      const next = new Set(prev);
      if (next.has(p)) {
        next.delete(p);
      } else {
        next.add(p);
      }
      return next;
    });
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (selectedPurposes.size === 0) return;
    for (const purpose of selectedPurposes) {
      await onSubmit({
        provider, modelId, displayName, purpose: purpose as LlmModel['purpose'],
        baseUrl: (provider === 'OLLAMA' || provider === 'AZURE_OPENAI') ? baseUrl : null,
        apiKeyRef: (provider === 'ANTHROPIC' || provider === 'AZURE_OPENAI') ? apiKeyRef : null,
        temperature: 0.3,
      } as Partial<LlmModel>);
    }
  };

  return (
    <form className="flex flex-col gap-2 p-3 border rounded-lg bg-muted/30" onSubmit={handleSubmit}>
      <Select value={provider} onValueChange={(v) => setProvider((v ?? 'OLLAMA') as 'OLLAMA' | 'ANTHROPIC' | 'AZURE_OPENAI')}>
        <SelectTrigger size="sm">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="OLLAMA">Ollama</SelectItem>
          <SelectItem value="ANTHROPIC">Anthropic</SelectItem>
          <SelectItem value="AZURE_OPENAI">Azure OpenAI</SelectItem>
        </SelectContent>
      </Select>
      <Input placeholder={provider === 'AZURE_OPENAI' ? '배포 이름 (deployment name)' : '모델 ID (예: gpt-oss:20b)'} value={modelId} onChange={e => setModelId(e.target.value)} required className="h-8 text-xs" />
      <Input placeholder="표시 이름" value={displayName} onChange={e => setDisplayName(e.target.value)} required className="h-8 text-xs" />
      <div className="space-y-1.5">
        <span className="text-xs text-muted-foreground">용도 (복수 선택 가능)</span>
        <div className="flex flex-wrap gap-x-3 gap-y-1.5">
          {PURPOSES.map(p => (
            <label key={p} className="flex items-center gap-1.5 text-xs cursor-pointer">
              <Checkbox checked={selectedPurposes.has(p)} onCheckedChange={() => togglePurpose(p)} />
              {p}
            </label>
          ))}
        </div>
      </div>
      {provider === 'OLLAMA' && <Input placeholder="Base URL" value={baseUrl} onChange={e => setBaseUrl(e.target.value)} className="h-8 text-xs" />}
      {provider === 'AZURE_OPENAI' && <Input placeholder="Azure 엔드포인트 (예: https://xxx.openai.azure.com/)" value={baseUrl} onChange={e => setBaseUrl(e.target.value)} className="h-8 text-xs" />}
      {(provider === 'ANTHROPIC' || provider === 'AZURE_OPENAI') && <Input placeholder="API Key 또는 환경변수명" value={apiKeyRef} onChange={e => setApiKeyRef(e.target.value)} className="h-8 text-xs" />}
      <Button type="submit" size="sm" disabled={selectedPurposes.size === 0}>등록</Button>
    </form>
  );
}
