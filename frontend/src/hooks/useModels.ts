import { useState, useEffect, useCallback } from 'react';
import { toast } from 'sonner';
import type { LlmModel, DiscoveredModel } from '../types';

function authHeaders(extra?: Record<string, string>): Record<string, string> {
  const headers: Record<string, string> = { ...extra };
  const token = localStorage.getItem('accessToken');
  if (token) headers['Authorization'] = `Bearer ${token}`;
  return headers;
}

export function useModels() {
  const [models, setModels] = useState<LlmModel[]>([]);
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const res = await fetch('/api/models', { headers: authHeaders() });
      if (res.ok) setModels(await res.json());
      else {
        console.error('Failed to load models:', res.status);
        toast.error('모델 목록 조회 실패');
      }
    } catch (err) {
      console.error('Failed to load models:', err);
      toast.error('모델 목록 조회 실패');
    }
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  const setDefault = async (id: string) => {
    await fetch(`/api/models/${id}/set-default`, { method: 'PATCH', headers: authHeaders() });
    await refresh();
  };

  const testModel = async (id: string) => {
    const res = await fetch(`/api/models/${id}/test`, { method: 'POST', headers: authHeaders() });
    return res.json();
  };

  const deleteModel = async (id: string) => {
    await fetch(`/api/models/${id}`, { method: 'DELETE', headers: authHeaders() });
    await refresh();
  };

  const createModel = async (model: Partial<LlmModel>) => {
    await fetch('/api/models', {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(model),
    });
    await refresh();
  };

  const discoverOllama = async (): Promise<DiscoveredModel[]> => {
    setLoading(true);
    try {
      const res = await fetch('/api/models/discover/ollama', { headers: authHeaders() });
      return res.ok ? await res.json() : [];
    } finally {
      setLoading(false);
    }
  };

  return { models, loading, refresh, setDefault, testModel, deleteModel, createModel, discoverOllama };
}
