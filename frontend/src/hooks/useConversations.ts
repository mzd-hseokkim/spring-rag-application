import { useState, useEffect, useCallback } from 'react';
import type { Conversation } from '../types';
import { fetchConversations, deleteConversation, updateConversationTitle } from '../api/client';

export function useConversations() {
  const [conversations, setConversations] = useState<Conversation[]>([]);

  const refresh = useCallback(async () => {
    try {
      const data = await fetchConversations();
      setConversations(data);
    } catch {
      // 목록 로드 실패 시 무시
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const remove = useCallback(async (id: string) => {
    await deleteConversation(id);
    setConversations(prev => prev.filter(c => c.id !== id));
  }, []);

  const rename = useCallback(async (id: string, title: string) => {
    const updated = await updateConversationTitle(id, title);
    setConversations(prev => prev.map(c => c.id === id ? updated : c));
  }, []);

  return { conversations, refresh, remove, rename };
}
