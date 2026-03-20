import { useState, useCallback } from 'react';
import type { Message, Source } from '../types';

export function useChat() {
  const [messages, setMessages] = useState<Message[]>([]);
  const [streaming, setStreaming] = useState(false);

  const sendMessage = useCallback(async (content: string) => {
    const userMessage: Message = { role: 'user', content };
    setMessages(prev => [...prev, userMessage]);
    setStreaming(true);

    // 빈 assistant 메시지 추가
    setMessages(prev => [...prev, { role: 'assistant', content: '' }]);

    try {
      const res = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
        body: JSON.stringify({ message: content }),
      });

      if (!res.ok || !res.body) throw new Error('Chat request failed');

      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let sources: Source[] = [];

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        let currentEvent = '';
        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEvent = line.slice(6).trim();
          } else if (line.startsWith('data:')) {
            const data = line.slice(5).trim();
            if (!data) continue;

            try {
              const parsed = JSON.parse(data);
              if (currentEvent === 'token' && parsed.content) {
                setMessages(prev => {
                  const updated = [...prev];
                  const last = updated[updated.length - 1];
                  updated[updated.length - 1] = { ...last, content: last.content + parsed.content };
                  return updated;
                });
              } else if (currentEvent === 'sources') {
                sources = Array.isArray(parsed) ? parsed : [];
              }
            } catch { /* skip malformed JSON */ }
          }
        }
      }

      // sources 추가
      if (sources.length > 0) {
        setMessages(prev => {
          const updated = [...prev];
          const last = updated[updated.length - 1];
          updated[updated.length - 1] = { ...last, sources };
          return updated;
        });
      }
    } catch (err) {
      setMessages(prev => {
        const updated = [...prev];
        updated[updated.length - 1] = { role: 'assistant', content: '오류가 발생했습니다. 다시 시도해주세요.' };
        return updated;
      });
    } finally {
      setStreaming(false);
    }
  }, []);

  return { messages, streaming, sendMessage };
}
