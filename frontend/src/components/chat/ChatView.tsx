import { useState, useEffect } from 'react';
import { ChatInput, type AgenticToolState } from '@/components/chat/ChatInput';
import { MessageList } from '@/components/chat/MessageList';
import { ModelSelector } from '@/components/chat/ModelSelector';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Switch } from '@/components/ui/switch';
import { Checkbox } from '@/components/ui/checkbox';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Badge } from '@/components/ui/badge';
import { Filter, FileUp, Check, Loader2, X } from 'lucide-react';
import { fetchTags, fetchCollections } from '@/api/client';
import type { LlmModel, Message, DocumentTag, DocumentCollection } from '@/types';

interface ChatState {
  messages: Message[];
  streaming: boolean;
  sessionId: string;
  sendMessage: (content: string, modelId?: string | null, includePublicDocs?: boolean,
                tagIds?: string[], collectionIds?: string[], enableWebSearch?: boolean) => Promise<void>;
  stopGeneration: () => void;
}

export interface FileUploadStatus {
  filename: string;
  status: 'uploading' | 'processing' | 'completed' | 'failed';
}

interface Props {
  models: LlmModel[];
  chat: ChatState;
  onNewSession: () => void;
  onSendComplete: () => void;
  onFileDrop?: (file: File) => void;
  uploadStatus?: FileUploadStatus | null;
  onDismissUpload?: () => void;
  filterRefreshKey?: number;
}

export function ChatView({ models, chat, onNewSession, onSendComplete, onFileDrop, uploadStatus, onDismissUpload, filterRefreshKey = 0 }: Props) {
  const [selectedModelId, setSelectedModelId] = useState<string | null>(
    () => localStorage.getItem('chat:modelId')
  );
  const [includePublicDocs, setIncludePublicDocs] = useState(
    () => localStorage.getItem('chat:includePublicDocs') !== 'false'
  );
  const [selectedTagIds, setSelectedTagIds] = useState<Set<string>>(
    () => new Set(JSON.parse(localStorage.getItem('chat:tagIds') || '[]'))
  );
  const [selectedCollectionIds, setSelectedCollectionIds] = useState<Set<string>>(
    () => new Set(JSON.parse(localStorage.getItem('chat:collectionIds') || '[]'))
  );
  const [agenticTools, setAgenticTools] = useState<AgenticToolState>(
    () => ({ enableWebSearch: localStorage.getItem('chat:enableWebSearch') === 'true' })
  );
  const [tags, setTags] = useState<DocumentTag[]>([]);
  const [collections, setCollections] = useState<DocumentCollection[]>([]);

  // localStorage 동기화
  useEffect(() => {
    if (selectedModelId) localStorage.setItem('chat:modelId', selectedModelId);
    else localStorage.removeItem('chat:modelId');
  }, [selectedModelId]);
  useEffect(() => {
    localStorage.setItem('chat:includePublicDocs', String(includePublicDocs));
  }, [includePublicDocs]);
  useEffect(() => {
    localStorage.setItem('chat:tagIds', JSON.stringify([...selectedTagIds]));
  }, [selectedTagIds]);
  useEffect(() => {
    localStorage.setItem('chat:collectionIds', JSON.stringify([...selectedCollectionIds]));
  }, [selectedCollectionIds]);
  useEffect(() => {
    localStorage.setItem('chat:enableWebSearch', String(agenticTools.enableWebSearch));
  }, [agenticTools.enableWebSearch]);

  useEffect(() => {
    fetchTags().then(setTags).catch(() => {});
    fetchCollections().then(setCollections).catch(() => {});
  }, [filterRefreshKey]);

  const toggleTag = (id: string) => {
    setSelectedTagIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const toggleCollection = (id: string) => {
    setSelectedCollectionIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const clearFilters = () => {
    setSelectedTagIds(new Set());
    setSelectedCollectionIds(new Set());
  };

  const handleSend = async (content: string) => {
    const tagIds = selectedTagIds.size > 0 ? [...selectedTagIds] : undefined;
    const collectionIds = selectedCollectionIds.size > 0 ? [...selectedCollectionIds] : undefined;
    await chat.sendMessage(content, selectedModelId, includePublicDocs, tagIds, collectionIds, agenticTools.enableWebSearch);
    onSendComplete();
  };

  const handleAgenticToolChange = (tool: keyof AgenticToolState, enabled: boolean) => {
    setAgenticTools(prev => ({ ...prev, [tool]: enabled }));
  };

  const hasFilters = tags.length > 0 || collections.length > 0;
  const activeFilterCount = selectedTagIds.size + selectedCollectionIds.size;

  // 선택된 필터 라벨
  const selectedLabels: string[] = [
    ...tags.filter(t => selectedTagIds.has(t.id)).map(t => t.name),
    ...collections.filter(c => selectedCollectionIds.has(c.id)).map(c => c.name),
  ];

  return (
    <div className="flex flex-col h-full">
      <Card className="shrink-0 rounded-none border-x-0 border-t-0 ring-0 py-0">
        <div className="flex items-center justify-between px-4 py-3">
          <div className="flex items-center gap-2">
            <h2 className="text-base font-semibold text-muted-foreground">채팅</h2>
            <Button variant="outline" size="sm" onClick={onNewSession} disabled={chat.streaming}>
              새 대화
            </Button>
          </div>
          <div className="flex items-center gap-3">
            {hasFilters && (
              <Popover>
                <PopoverTrigger
                  disabled={chat.streaming}
                  className="inline-flex items-center gap-1.5 rounded-md border border-input bg-background px-3 h-8 text-xs shadow-xs hover:bg-accent hover:text-accent-foreground disabled:opacity-50 cursor-pointer"
                >
                  <Filter className="h-3 w-3" />
                  {activeFilterCount > 0 ? (
                    <span className="truncate max-w-32">{selectedLabels.join(', ')}</span>
                  ) : (
                    '검색 범위'
                  )}
                  {activeFilterCount > 0 && (
                    <Badge variant="secondary" className="text-[10px] px-1 py-0 h-4 ml-0.5">
                      {activeFilterCount}
                    </Badge>
                  )}
                </PopoverTrigger>
                <PopoverContent className="w-52 p-3" align="end">
                  <div className="space-y-3">
                    {tags.length > 0 && (
                      <div>
                        <p className="text-xs font-medium text-muted-foreground mb-1.5">태그</p>
                        <div className="space-y-1">
                          {tags.map(tag => (
                            <label key={tag.id} className="flex items-center gap-2 text-sm cursor-pointer py-0.5">
                              <Checkbox
                                checked={selectedTagIds.has(tag.id)}
                                onCheckedChange={() => toggleTag(tag.id)}
                              />
                              {tag.name}
                            </label>
                          ))}
                        </div>
                      </div>
                    )}
                    {collections.length > 0 && (
                      <div>
                        <p className="text-xs font-medium text-muted-foreground mb-1.5">컬렉션</p>
                        <div className="space-y-1">
                          {collections.map(col => (
                            <label key={col.id} className="flex items-center gap-2 text-sm cursor-pointer py-0.5">
                              <Checkbox
                                checked={selectedCollectionIds.has(col.id)}
                                onCheckedChange={() => toggleCollection(col.id)}
                              />
                              {col.name}
                            </label>
                          ))}
                        </div>
                      </div>
                    )}
                    {activeFilterCount > 0 && (
                      <Button variant="ghost" size="sm" className="w-full text-xs" onClick={clearFilters}>
                        필터 초기화
                      </Button>
                    )}
                  </div>
                </PopoverContent>
              </Popover>
            )}
            <label className="flex items-center gap-1.5 cursor-pointer">
              <Switch
                checked={includePublicDocs}
                onCheckedChange={setIncludePublicDocs}
                disabled={chat.streaming}
              />
              <span className="text-xs text-muted-foreground whitespace-nowrap">공용 문서</span>
            </label>
            <ModelSelector
              models={models}
              selectedModelId={selectedModelId}
              onSelect={setSelectedModelId}
              disabled={chat.streaming}
            />
          </div>
        </div>
      </Card>
      <div className="flex-1 min-h-0">
        <MessageList messages={chat.messages} streaming={chat.streaming} />
      </div>
      {uploadStatus && (
        <div className="shrink-0 px-4 py-2 border-t bg-muted/30 animate-page-in">
          <div className="flex items-center gap-2 text-sm">
            {uploadStatus.status === 'uploading' && <Loader2 className="size-4 animate-spin text-primary" />}
            {uploadStatus.status === 'processing' && <Loader2 className="size-4 animate-spin text-yellow-600" />}
            {uploadStatus.status === 'completed' && <Check className="size-4 text-green-600" />}
            {uploadStatus.status === 'failed' && <X className="size-4 text-destructive" />}
            <FileUp className="size-3.5 text-muted-foreground" />
            <span className="truncate flex-1">{uploadStatus.filename}</span>
            <span className="text-xs text-muted-foreground shrink-0">
              {uploadStatus.status === 'uploading' && '업로드 중...'}
              {uploadStatus.status === 'processing' && '처리 중...'}
              {uploadStatus.status === 'completed' && '완료'}
              {uploadStatus.status === 'failed' && '실패'}
            </span>
            {(uploadStatus.status === 'completed' || uploadStatus.status === 'failed') && (
              <button onClick={onDismissUpload} className="text-muted-foreground hover:text-foreground">
                <X className="size-3.5" />
              </button>
            )}
          </div>
        </div>
      )}
      <div className="shrink-0">
        <ChatInput
          onSend={handleSend}
          onStop={chat.stopGeneration}
          onFileDrop={onFileDrop}
          disabled={chat.streaming}
          streaming={chat.streaming}
          focusKey={chat.sessionId}
          agenticTools={agenticTools}
          onAgenticToolChange={handleAgenticToolChange}
        />
      </div>
    </div>
  );
}
