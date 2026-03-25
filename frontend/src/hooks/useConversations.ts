import { useState, useEffect, useCallback } from 'react';
import { toast } from 'sonner';
import type { Conversation } from '../types';
import { fetchConversations, deleteConversation, updateConversationTitle } from '../api/client';

export function useConversations() {
  const [conversations, setConversations] = useState<Conversation[]>([]);

  const refresh = useCallback(async () => {
    try {
      const data = await fetchConversations();
      setConversations(data);
      return data;
    } catch (err) {
      console.error('Failed to load conversations:', err);
      toast.error('대화 목록 조회 실패');
      return [];
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const remove = useCallback(async (id: string) => {
    try {
      await deleteConversation(id);
      setConversations(prev => prev.filter(c => c.id !== id));
    } catch (err) {
      toast.error('대화 삭제 실패');
      console.error(err);
    }
  }, []);

  const rename = useCallback(async (id: string, title: string) => {
    try {
      const updated = await updateConversationTitle(id, title);
      setConversations(prev => prev.map(c => c.id === id ? updated : c));
    } catch (err) {
      toast.error('제목 수정 실패');
      console.error(err);
    }
  }, []);

  return { conversations, refresh, remove, rename };
}
