import { useState } from 'react';
import { useChat } from '../../hooks/useChat';
import { ChatInput } from './ChatInput';
import { MessageList } from './MessageList';
import { ModelSelector } from './ModelSelector';
import type { LlmModel } from '../../types';

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
    <div className="chat-view">
      <div className="chat-header">
        <h2>채팅</h2>
        <div className="chat-header-actions">
          <ModelSelector
            models={models}
            selectedModelId={selectedModelId}
            onSelect={setSelectedModelId}
            disabled={streaming}
          />
          <button className="new-session-btn" onClick={newSession} disabled={streaming}>
            새 대화
          </button>
        </div>
      </div>
      <MessageList messages={messages} />
      <ChatInput onSend={handleSend} disabled={streaming} />
    </div>
  );
}
