import { useChat } from '../../hooks/useChat';
import { ChatInput } from './ChatInput';
import { MessageList } from './MessageList';

export function ChatView() {
  const { messages, streaming, sendMessage } = useChat();

  return (
    <div className="chat-view">
      <MessageList messages={messages} />
      <ChatInput onSend={sendMessage} disabled={streaming} />
    </div>
  );
}
