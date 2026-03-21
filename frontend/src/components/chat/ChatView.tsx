import { useState } from 'react';
import { useChat } from '@/hooks/useChat';
import { ChatInput } from '@/components/chat/ChatInput';
import { MessageList } from '@/components/chat/MessageList';
import { ModelSelector } from '@/components/chat/ModelSelector';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import type { LlmModel } from '@/types';

interface Props {
  models: LlmModel[];
}

export function ChatView({ models }: Props) {
  const [selectedModelId, setSelectedModelId] = useState<string | null>(null);
  const { messages, streaming, sendMessage, newSession } = useChat();

  const handleSend = (content: string) => {
    sendMessage(content, selectedModelId);
  };

  return (
    <div className="flex flex-col h-full">
      <Card className="shrink-0 rounded-none border-x-0 border-t-0 ring-0 py-0">
        <div className="flex items-center justify-between px-4 py-3">
          <h2 className="text-base font-semibold text-muted-foreground">채팅</h2>
          <div className="flex items-center gap-2">
            <ModelSelector
              models={models}
              selectedModelId={selectedModelId}
              onSelect={setSelectedModelId}
              disabled={streaming}
            />
            <Button variant="outline" size="sm" onClick={newSession} disabled={streaming}>
              새 대화
            </Button>
          </div>
        </div>
      </Card>
      <div className="flex-1 min-h-0">
        <MessageList messages={messages} streaming={streaming} />
      </div>
      <div className="shrink-0">
        <ChatInput onSend={handleSend} disabled={streaming} />
      </div>
    </div>
  );
}
