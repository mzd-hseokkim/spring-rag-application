import { useRef, useState, useEffect } from 'react';
import { toast } from 'sonner';
import { Textarea } from '@/components/ui/textarea';
import { Button } from '@/components/ui/button';
import { Switch } from '@/components/ui/switch';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Badge } from '@/components/ui/badge';
import { ArrowUpIcon, Square, Upload, Plus, Wrench, Globe } from 'lucide-react';

const ACCEPT_TYPES = [
  'application/pdf',
  'text/plain',
  'text/markdown',
  'application/octet-stream',
];
const ACCEPT_EXT = ['.pdf', '.txt', '.md'];

export interface AgenticToolState {
  enableWebSearch: boolean;
}

interface Props {
  onSend: (message: string) => void;
  onStop?: () => void;
  onFileDrop?: (file: File) => void;
  disabled: boolean;
  streaming?: boolean;
  focusKey?: string;
  agenticTools: AgenticToolState;
  onAgenticToolChange: (tool: keyof AgenticToolState, enabled: boolean) => void;
}

export function ChatInput({ onSend, onStop, onFileDrop, disabled, streaming, focusKey, agenticTools, onAgenticToolChange }: Props) {
  const [input, setInput] = useState('');
  const [dragOver, setDragOver] = useState(false);
  const activeToolCount = Object.values(agenticTools).filter(Boolean).length;
  const formRef = useRef<HTMLFormElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    textareaRef.current?.focus();
  }, [focusKey]);

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

  const isAcceptedFile = (file: File) => {
    if (ACCEPT_TYPES.includes(file.type)) return true;
    return ACCEPT_EXT.some(ext => file.name.toLowerCase().endsWith(ext));
  };

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setDragOver(true);
  };

  const handleDragLeave = (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setDragOver(false);
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setDragOver(false);

    const file = e.dataTransfer.files[0];
    if (!file) return;

    if (!isAcceptedFile(file)) {
      toast.error('PDF, TXT, MD 파일만 지원합니다.');
      return;
    }

    onFileDrop?.(file);
  };

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (!isAcceptedFile(file)) {
      toast.error('PDF, TXT, MD 파일만 지원합니다.');
      return;
    }
    onFileDrop?.(file);
    e.target.value = '';
  };

  return (
    <form
      ref={formRef}
      className={`relative flex items-center gap-2 px-3 h-14 border-t bg-background transition-colors ${
        dragOver ? 'bg-primary/5 border-primary' : ''
      }`}
      onSubmit={handleSubmit}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
    >
      {dragOver && (
        <div className="absolute inset-0 flex items-center justify-center bg-primary/5 border-2 border-dashed border-primary rounded-lg z-10 pointer-events-none">
          <div className="flex items-center gap-2 text-primary text-sm font-medium">
            <Upload className="size-4" />
            파일을 놓아 업로드
          </div>
        </div>
      )}
      <input
        ref={fileInputRef}
        type="file"
        accept=".pdf,.txt,.md"
        className="hidden"
        onChange={handleFileSelect}
      />
      <Button type="button" variant="ghost" size="icon" className="shrink-0"
        onClick={() => fileInputRef.current?.click()} title="파일 업로드">
        <Plus className="size-4" />
      </Button>
      <Popover>
        <PopoverTrigger asChild>
          <Button type="button" variant="ghost" size="icon" className="shrink-0 relative" title="Agentic Tools">
            <Wrench className="size-4" />
            {activeToolCount > 0 && (
              <Badge variant="default" className="absolute -top-1 -right-1 h-4 min-w-4 px-1 text-[10px] leading-none flex items-center justify-center">
                {activeToolCount}
              </Badge>
            )}
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-56 p-0" align="start" side="top">
          <div className="px-3 py-2 border-b">
            <p className="text-xs font-semibold text-foreground">Agentic Tools</p>
          </div>
          <div className="p-2 space-y-1">
            <label className="flex items-center justify-between gap-3 px-2 py-1.5 rounded-md hover:bg-accent cursor-pointer">
              <div className="flex items-center gap-2 min-w-0">
                <Globe className="size-3.5 shrink-0 text-muted-foreground" />
                <span className="text-sm truncate">웹 검색</span>
              </div>
              <Switch
                size="sm"
                checked={agenticTools.enableWebSearch}
                onCheckedChange={(checked: boolean) => onAgenticToolChange('enableWebSearch', checked)}
              />
            </label>
          </div>
        </PopoverContent>
      </Popover>
      <Textarea
        ref={textareaRef}
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
