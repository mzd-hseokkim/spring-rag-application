import { useEffect, useRef } from 'react';
import type { Message } from '../../types';

interface Props {
  messages: Message[];
}

export function MessageList({ messages }: Props) {
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  if (messages.length === 0) {
    return (
      <div className="message-list empty">
        <p>문서를 업로드한 후 질문해보세요.</p>
      </div>
    );
  }

  return (
    <div className="message-list">
      {messages.map((msg, i) => (
        <div key={i} className={`message ${msg.role}`}>
          <div className="message-role">{msg.role === 'user' ? '사용자' : 'AI'}</div>
          <div className="message-content">{msg.content}</div>
          {msg.sources && msg.sources.length > 0 && (
            <div className="sources">
              <div className="sources-title">출처</div>
              {msg.sources.map((s, j) => (
                <div key={j} className="source-item">
                  <span className="source-file">{s.filename}</span>
                  <span className="source-chunk">#{s.chunkIndex}</span>
                  <span className="source-excerpt">{s.excerpt}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      ))}
      <div ref={endRef} />
    </div>
  );
}
