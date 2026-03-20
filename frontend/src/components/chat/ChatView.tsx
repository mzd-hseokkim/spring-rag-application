import { useChat } from '../../hooks/useChat';
import { ChatInput } from './ChatInput';
import { MessageList } from './MessageList';

export function ChatView() {
  const { messages, streaming, sendMessage, newSession } = useChat();

  return (
    <div className="chat-view">
      <div className="chat-header">
        <h2>채팅</h2>
        <button className="new-session-btn" onClick={newSession} disabled={streaming}>
          새 대화
        </button>
      </div>
      <MessageList messages={messages} />
      <ChatInput onSend={sendMessage} disabled={streaming} />
    </div>
  );
}
