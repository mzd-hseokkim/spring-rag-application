import { useRef, useState } from 'react';
import { Textarea } from '@/components/ui/textarea';
import { Button } from '@/components/ui/button';
import { ArrowUpIcon, Square } from 'lucide-react';

interface Props {
  onSend: (message: string) => void;
  onStop?: () => void;
  disabled: boolean;
  streaming?: boolean;
}

export function ChatInput({ onSend, onStop, disabled, streaming }: Props) {
  const [input, setInput] = useState('');
  const formRef = useRef<HTMLFormElement>(null);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!input.trim() || disabled) return;
    onSend(input.trim());
    setInput('');
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      formRef.current?.requestSubmit();
    }
  };

  return (
    <form ref={formRef} className="flex items-center gap-2 px-3 h-16 border-t bg-background" onSubmit={handleSubmit}>
      <Textarea
        value={input}
        onChange={e => setInput(e.target.value)}
        onKeyDown={handleKeyDown}
        placeholder="질문을 입력하세요..."
        disabled={disabled}
        className="min-h-10 max-h-32 resize-none"
        rows={1}
      />
      {streaming ? (
        <Button type="button" variant="destructive" size="icon" onClick={onStop}>
          <Square className="size-4" />
        </Button>
      ) : (
        <Button type="submit" size="icon" disabled={disabled || !input.trim()}>
          <ArrowUpIcon className="size-4" />
        </Button>
      )}
    </form>
  );
}
