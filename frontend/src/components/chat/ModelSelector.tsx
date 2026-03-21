import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
} from '@/components/ui/select';
import { Badge } from '@/components/ui/badge';
import type { LlmModel } from '@/types';

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
  const selectedModel = chatModels.find(m => m.id === selectedModelId);
  const currentValue = selectedModelId || '';

  const displayText = selectedModel
    ? selectedModel.displayName
    : defaultModel
      ? `${defaultModel.displayName} (기본)`
      : '모델 선택';

  return (
    <Select
      value={currentValue}
      onValueChange={(v) => onSelect(v || null)}
      disabled={disabled}
    >
      <SelectTrigger size="sm">
        <span className="truncate">{displayText}</span>
      </SelectTrigger>
      <SelectContent>
        {Object.entries(grouped).map(([provider, providerModels]) => (
          <SelectGroup key={provider}>
            <SelectLabel>{provider}</SelectLabel>
            {providerModels.map(m => (
              <SelectItem key={m.id} value={m.id}>
                {m.displayName}
                {m.isDefault && <Badge variant="secondary" className="ml-1.5 text-[10px] px-1.5 py-0">기본</Badge>}
              </SelectItem>
            ))}
          </SelectGroup>
        ))}
      </SelectContent>
    </Select>
  );
}
