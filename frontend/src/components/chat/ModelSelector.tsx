import type { LlmModel } from '../../types';

interface Props {
  models: LlmModel[];
  selectedModelId: string | null;
  onSelect: (modelId: string | null) => void;
  disabled: boolean;
}

export function ModelSelector({ models, selectedModelId, onSelect, disabled }: Props) {
  const chatModels = models.filter(m => m.purpose === 'CHAT');
  if (chatModels.length === 0) return null;

  const grouped = chatModels.reduce<Record<string, LlmModel[]>>((acc, m) => {
    if (!acc[m.provider]) acc[m.provider] = [];
    acc[m.provider].push(m);
    return acc;
  }, {});

  const defaultModel = chatModels.find(m => m.isDefault);
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
