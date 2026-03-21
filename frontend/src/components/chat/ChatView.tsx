import { useState } from 'react';
import { ChatInput } from '@/components/chat/ChatInput';
import { MessageList } from '@/components/chat/MessageList';
import { ModelSelector } from '@/components/chat/ModelSelector';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Switch } from '@/components/ui/switch';
import type { LlmModel, Message } from '@/types';

interface ChatState {
  messages: Message[];
  streaming: boolean;
  sessionId: string;
  sendMessage: (content: string, modelId?: string | null, includePublicDocs?: boolean) => Promise<void>;
}

interface Props {
  models: LlmModel[];
  chat: ChatState;
  onNewSession: () => void;
  onSendComplete: () => void;
}

export function ChatView({ models, chat, onNewSession, onSendComplete }: Props) {
  const [selectedModelId, setSelectedModelId] = useState<string | null>(null);
  const [includePublicDocs, setIncludePublicDocs] = useState(true);

  const handleSend = async (content: string) => {
    await chat.sendMessage(content, selectedModelId, includePublicDocs);
    onSendComplete();
  };

  return (
    <div className="flex flex-col h-full">
      <Card className="shrink-0 rounded-none border-x-0 border-t-0 ring-0 py-0">
        <div className="flex items-center justify-between px-4 py-3">
          <h2 className="text-base font-semibold text-muted-foreground">채팅</h2>
          <div className="flex items-center gap-3">
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
            <Button variant="outline" size="sm" onClick={onNewSession} disabled={chat.streaming}>
              새 대화
            </Button>
          </div>
        </div>
      </Card>
      <div className="flex-1 min-h-0">
        <MessageList messages={chat.messages} streaming={chat.streaming} />
      </div>
      <div className="shrink-0">
        <ChatInput onSend={handleSend} disabled={chat.streaming} />
      </div>
    </div>
  );
}
