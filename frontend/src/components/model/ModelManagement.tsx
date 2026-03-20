import { useState } from 'react';
import { useModels } from '../../hooks/useModels';
import type { LlmModel } from '../../types';

const PURPOSES = ['CHAT', 'EMBEDDING', 'QUERY', 'RERANK', 'EVALUATION'] as const;

export function ModelManagement() {
  const { models, setDefault, testModel, deleteModel, createModel, discoverOllama } = useModels();
  const [selectedPurpose, setSelectedPurpose] = useState<string>('CHAT');
  const [testResults, setTestResults] = useState<Record<string, { success: boolean; message: string }>>({});
  const [showAdd, setShowAdd] = useState(false);

  const filtered = models.filter(m => m.purpose === selectedPurpose);

  const handleTest = async (id: string) => {
    const result = await testModel(id);
    setTestResults(prev => ({ ...prev, [id]: result }));
  };

  const handleDiscover = async () => {
    const discovered = await discoverOllama();
    const existingIds = new Set(models.map(m => m.modelId));
    const newModels = discovered.filter(d => !existingIds.has(d.modelId));
    if (newModels.length === 0) {
      alert('새로운 모델이 없습니다.');
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
    <div className="model-management">
      <h2>모델 관리</h2>

      <div className="purpose-tabs">
        {PURPOSES.map(p => (
          <button
            key={p}
            className={`purpose-tab ${selectedPurpose === p ? 'active' : ''}`}
            onClick={() => setSelectedPurpose(p)}
          >
            {p}
          </button>
        ))}
      </div>

      <ul className="model-list">
        {filtered.map(m => (
          <li key={m.id} className={`model-item ${m.isDefault ? 'is-default' : ''}`}>
            <div className="model-info">
              <span className="model-name">{m.displayName}</span>
              <span className="model-meta">{m.provider} · {m.modelId}</span>
              {m.isDefault && <span className="default-badge">기본</span>}
            </div>
            <div className="model-actions">
              <button onClick={() => handleTest(m.id)}>테스트</button>
              {!m.isDefault && <button onClick={() => setDefault(m.id)}>기본 설정</button>}
              {!m.isDefault && <button className="danger" onClick={() => deleteModel(m.id)}>삭제</button>}
            </div>
            {testResults[m.id] && (
              <div className={`test-result ${testResults[m.id].success ? 'success' : 'fail'}`}>
                {testResults[m.id].success ? 'OK' : testResults[m.id].message}
              </div>
            )}
          </li>
        ))}
        {filtered.length === 0 && <li className="no-models">등록된 모델 없음</li>}
      </ul>

      <div className="model-bottom-actions">
        <button onClick={() => setShowAdd(!showAdd)}>+ 모델 추가</button>
        <button onClick={handleDiscover}>Ollama 자동 검색</button>
      </div>

      {showAdd && <AddModelForm onSubmit={async (m) => { await createModel(m); setShowAdd(false); }} />}
    </div>
  );
}

function AddModelForm({ onSubmit }: { onSubmit: (m: Partial<LlmModel>) => Promise<void> }) {
  const [provider, setProvider] = useState<'OLLAMA' | 'ANTHROPIC'>('OLLAMA');
  const [modelId, setModelId] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [purpose, setPurpose] = useState<string>('CHAT');
  const [baseUrl, setBaseUrl] = useState('http://localhost:11434');
  const [apiKeyRef, setApiKeyRef] = useState('ANTHROPIC_API_KEY');

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    onSubmit({
      provider, modelId, displayName, purpose: purpose as LlmModel['purpose'],
      baseUrl: provider === 'OLLAMA' ? baseUrl : null,
      apiKeyRef: provider === 'ANTHROPIC' ? apiKeyRef : null,
      temperature: 0.3,
    } as Partial<LlmModel>);
  };

  return (
    <form className="add-model-form" onSubmit={handleSubmit}>
      <select value={provider} onChange={e => setProvider(e.target.value as 'OLLAMA' | 'ANTHROPIC')}>
        <option value="OLLAMA">Ollama</option>
        <option value="ANTHROPIC">Anthropic</option>
      </select>
      <input placeholder="모델 ID (예: gpt-oss:20b)" value={modelId} onChange={e => setModelId(e.target.value)} required />
      <input placeholder="표시 이름" value={displayName} onChange={e => setDisplayName(e.target.value)} required />
      <select value={purpose} onChange={e => setPurpose(e.target.value)}>
        {PURPOSES.map(p => <option key={p} value={p}>{p}</option>)}
      </select>
      {provider === 'OLLAMA' && <input placeholder="Base URL" value={baseUrl} onChange={e => setBaseUrl(e.target.value)} />}
      {provider === 'ANTHROPIC' && <input placeholder="API Key 환경변수명" value={apiKeyRef} onChange={e => setApiKeyRef(e.target.value)} />}
      <button type="submit">등록</button>
    </form>
  );
}
