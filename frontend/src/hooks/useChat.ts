import { useReducer, useCallback, useRef } from 'react';
import type { Message, Source, AgentStep } from '../types';

function generateSessionId(): string {
  return crypto.randomUUID();
}

type ChatState = {
  messages: Message[];
  streaming: boolean;
};

type ChatAction =
  | { type: 'SEND_MESSAGE'; content: string }
  | { type: 'APPEND_TOKEN'; content: string }
  | { type: 'ADD_AGENT_STEP'; step: AgentStep }
  | { type: 'SET_SOURCES'; sources: Source[] }
  | { type: 'SET_ERROR'; message: string }
  | { type: 'STREAM_START' }
  | { type: 'STREAM_END' }
  | { type: 'NEW_SESSION' };

function chatReducer(state: ChatState, action: ChatAction): ChatState {
  const updateLast = (updater: (last: Message) => Message): ChatState => {
    const msgs = [...state.messages];
    const last = msgs[msgs.length - 1];
    if (!last || last.role !== 'assistant') return state;
    msgs[msgs.length - 1] = updater(last);
    return { ...state, messages: msgs };
  };

  switch (action.type) {
    case 'SEND_MESSAGE':
      return {
        ...state,
        messages: [
          ...state.messages,
          { role: 'user', content: action.content },
          { role: 'assistant', content: '' },
        ],
      };
    case 'APPEND_TOKEN':
      return updateLast(last => ({ ...last, content: last.content + action.content }));
    case 'ADD_AGENT_STEP':
      return updateLast(last => ({ ...last, agentSteps: [...(last.agentSteps || []), action.step] }));
    case 'SET_SOURCES':
      return updateLast(last => ({ ...last, sources: action.sources }));
    case 'SET_ERROR':
      return updateLast(() => ({ role: 'assistant', content: action.message }));
    case 'STREAM_START':
      return { ...state, streaming: true };
    case 'STREAM_END':
      return { ...state, streaming: false };
    case 'NEW_SESSION':
      return { messages: [], streaming: false };
    default:
      return state;
  }
}

export function useChat() {
  const [state, dispatch] = useReducer(chatReducer, { messages: [], streaming: false });
  const sessionIdRef = useRef(generateSessionId());

  const sendMessage = useCallback(async (content: string, modelId?: string | null) => {
    dispatch({ type: 'SEND_MESSAGE', content });
    dispatch({ type: 'STREAM_START' });

    try {
      const payload: Record<string, string> = {
        sessionId: sessionIdRef.current,
        message: content,
      };
      if (modelId) payload.modelId = modelId;

      const res = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
        body: JSON.stringify(payload),
      });

      if (!res.ok || !res.body) throw new Error('Chat request failed');

      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let currentEvent = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (line.startsWith('event:')) {
            currentEvent = line.slice(6).trim();
          } else if (line.startsWith('data:')) {
            const data = line.slice(5).trim();
            if (!data) continue;

            try {
              const parsed = JSON.parse(data);
              if (currentEvent === 'agent_step') {
                dispatch({ type: 'ADD_AGENT_STEP', step: { step: parsed.step, message: parsed.message } });
              } else if (currentEvent === 'token' && parsed.content) {
                dispatch({ type: 'APPEND_TOKEN', content: parsed.content });
              } else if (currentEvent === 'sources') {
                const sources: Source[] = Array.isArray(parsed) ? parsed : [];
                if (sources.length > 0) {
                  dispatch({ type: 'SET_SOURCES', sources });
                }
              }
            } catch { /* skip malformed JSON */ }
          }
        }
      }
    } catch (err) {
      dispatch({ type: 'SET_ERROR', message: '오류가 발생했습니다. 다시 시도해주세요.' });
    } finally {
      dispatch({ type: 'STREAM_END' });
    }
  }, []);

  const newSession = useCallback(() => {
    sessionIdRef.current = generateSessionId();
    dispatch({ type: 'NEW_SESSION' });
  }, []);

  return { messages: state.messages, streaming: state.streaming, sendMessage, newSession };
}
