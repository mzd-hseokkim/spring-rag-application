import { useState, useEffect, useCallback } from 'react';
import type { LlmModel, DiscoveredModel } from '../types';

export function useModels() {
  const [models, setModels] = useState<LlmModel[]>([]);
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(async () => {
    const res = await fetch('/api/models');
    if (res.ok) setModels(await res.json());
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  const setDefault = async (id: string) => {
    await fetch(`/api/models/${id}/set-default`, { method: 'PATCH' });
    await refresh();
  };

  const testModel = async (id: string) => {
    const res = await fetch(`/api/models/${id}/test`, { method: 'POST' });
    return res.json();
  };

  const deleteModel = async (id: string) => {
    await fetch(`/api/models/${id}`, { method: 'DELETE' });
    await refresh();
  };

  const createModel = async (model: Partial<LlmModel>) => {
    await fetch('/api/models', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(model),
    });
    await refresh();
  };

  const discoverOllama = async (): Promise<DiscoveredModel[]> => {
    setLoading(true);
    try {
      const res = await fetch('/api/models/discover/ollama');
      return res.ok ? await res.json() : [];
    } finally {
      setLoading(false);
    }
  };

  return { models, loading, refresh, setDefault, testModel, deleteModel, createModel, discoverOllama };
}
