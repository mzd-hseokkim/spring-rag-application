import { useReducer, useCallback, useRef, useEffect } from 'react';
import { toast } from 'sonner';
import { Client } from '@stomp/stompjs';
import type { Message, Source, AgentStep } from '../types';
import { fetchConversationDetail } from '../api/client';

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
  | { type: 'NEW_SESSION' }
  | { type: 'LOAD_MESSAGES'; messages: Message[] };

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
    case 'LOAD_MESSAGES':
      return { messages: action.messages, streaming: false };
    default:
      return state;
  }
}

export function useChat() {
  const [state, dispatch] = useReducer(chatReducer, { messages: [], streaming: false });
  const sessionIdRef = useRef(generateSessionId());
  const clientRef = useRef<Client | null>(null);
  const connectedRef = useRef(false);

  // WebSocket 연결
  useEffect(() => {
    const token = localStorage.getItem('accessToken');
    if (!token) return;

    const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsHost = window.location.host;

    const client = new Client({
      brokerURL: `${wsProtocol}//${wsHost}/ws/chat`,
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
    });

    client.onConnect = () => {
      connectedRef.current = true;

      // 메시지 수신 구독
      client.subscribe('/user/queue/chat', (frame) => {
        try {
          const msg = JSON.parse(frame.body);
          switch (msg.type) {
            case 'agent_step':
              dispatch({ type: 'ADD_AGENT_STEP', step: { step: msg.step, message: msg.message } });
              break;
            case 'token':
              dispatch({ type: 'APPEND_TOKEN', content: msg.content });
              break;
            case 'sources':
              if (Array.isArray(msg.sources) && msg.sources.length > 0) {
                dispatch({ type: 'SET_SOURCES', sources: msg.sources });
              }
              break;
            case 'done':
            case 'stopped':
              dispatch({ type: 'STREAM_END' });
              break;
            case 'error':
              dispatch({ type: 'SET_ERROR', message: msg.message });
              toast.error('채팅 오류', { description: msg.message });
              dispatch({ type: 'STREAM_END' });
              break;
          }
        } catch { /* skip malformed */ }
      });
    };

    client.onDisconnect = () => {
      connectedRef.current = false;
    };

    client.onStompError = (frame) => {
      console.error('STOMP error:', frame.headers['message']);
    };

    client.activate();
    clientRef.current = client;

    return () => {
      client.deactivate();
    };
  }, []);

  const sendMessage = useCallback(async (content: string, modelId?: string | null,
      includePublicDocs?: boolean, tagIds?: string[], collectionIds?: string[]) => {
    dispatch({ type: 'SEND_MESSAGE', content });
    dispatch({ type: 'STREAM_START' });

    const payload: Record<string, unknown> = {
      sessionId: sessionIdRef.current,
      message: content,
      includePublicDocs: includePublicDocs ?? true,
    };
    if (modelId) payload.modelId = modelId;
    if (tagIds && tagIds.length > 0) payload.tagIds = tagIds;
    if (collectionIds && collectionIds.length > 0) payload.collectionIds = collectionIds;

    if (clientRef.current?.connected) {
      // WebSocket으로 전송
      clientRef.current.publish({
        destination: '/app/chat/send',
        body: JSON.stringify(payload),
      });
    } else {
      // SSE 폴백
      try {
        const token = localStorage.getItem('accessToken');
        const res = await fetch('/api/chat', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Accept': 'text/event-stream',
            ...(token ? { 'Authorization': `Bearer ${token}` } : {}),
          },
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
                  if (sources.length > 0) dispatch({ type: 'SET_SOURCES', sources });
                } else if (currentEvent === 'error') {
                  dispatch({ type: 'SET_ERROR', message: parsed.message });
                  toast.error('채팅 오류', { description: parsed.message });
                }
              } catch { /* skip */ }
            }
          }
        }
      } catch (err) {
        const errorMsg = err instanceof Error ? err.message : '오류가 발생했습니다.';
        dispatch({ type: 'SET_ERROR', message: errorMsg });
        toast.error('채팅 오류', { description: errorMsg });
      } finally {
        dispatch({ type: 'STREAM_END' });
      }
    }
  }, []);

  const stopGeneration = useCallback(() => {
    if (clientRef.current?.connected) {
      clientRef.current.publish({
        destination: '/app/chat/stop',
        body: JSON.stringify({ sessionId: sessionIdRef.current }),
      });
    }
    dispatch({ type: 'STREAM_END' });
  }, []);

  const newSession = useCallback(() => {
    sessionIdRef.current = generateSessionId();
    dispatch({ type: 'NEW_SESSION' });
  }, []);

  const loadConversation = useCallback(async (conversationId: string, sessionId: string) => {
    sessionIdRef.current = sessionId;
    try {
      const detail = await fetchConversationDetail(conversationId);
      const messages: Message[] = detail.messages.map(m => ({
        role: m.role as 'user' | 'assistant',
        content: m.content,
        ...(m.sources && m.sources.length > 0 ? { sources: m.sources } : {}),
      }));
      dispatch({ type: 'LOAD_MESSAGES', messages });
    } catch (err) {
      console.error('Failed to load conversation:', err);
      toast.error('대화 이력 조회 실패');
      dispatch({ type: 'LOAD_MESSAGES', messages: [] });
    }
  }, []);

  return {
    messages: state.messages,
    streaming: state.streaming,
    sessionId: sessionIdRef.current,
    sendMessage,
    stopGeneration,
    newSession,
    loadConversation,
  };
}
