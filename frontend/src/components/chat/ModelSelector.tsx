import { useState, useEffect } from 'react';
import type { LlmModel } from '../../types';

interface Props {
  selectedModelId: string | null;
  onSelect: (modelId: string | null) => void;
  disabled: boolean;
}

export function ModelSelector({ selectedModelId, onSelect, disabled }: Props) {
  const [models, setModels] = useState<LlmModel[]>([]);

  useEffect(() => {
    fetch('/api/models?purpose=CHAT')
      .then(res => res.json())
      .then(setModels)
      .catch(() => {});
  }, []);

  if (models.length === 0) return null;

  // provider별 그룹핑
  const grouped = models.reduce<Record<string, LlmModel[]>>((acc, m) => {
    const key = m.provider;
    if (!acc[key]) acc[key] = [];
    acc[key].push(m);
    return acc;
  }, {});

  const defaultModel = models.find(m => m.isDefault);
  const currentValue = selectedModelId || '';

  return (
    <select
      className="model-selector"
      value={currentValue}
      onChange={e => onSelect(e.target.value || null)}
      disabled={disabled}
    >
      <option value="">
        {defaultModel ? `${defaultModel.displayName} (기본)` : '모델 선택'}
      </option>
      {Object.entries(grouped).map(([provider, providerModels]) => (
        <optgroup key={provider} label={provider}>
          {providerModels.map(m => (
            <option key={m.id} value={m.id}>
              {m.displayName}{m.isDefault ? ' (기본)' : ''}
            </option>
          ))}
        </optgroup>
      ))}
    </select>
  );
}
