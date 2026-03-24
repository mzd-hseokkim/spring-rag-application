import { useState, useCallback } from 'react';
import {
  fetchPersonas,
  createPersona as apiCreatePersona,
  updatePersona as apiUpdatePersona,
  regeneratePersonaPrompt as apiRegeneratePrompt,
  deletePersona as apiDeletePersona,
  type Persona,
  type PersonaRequest,
} from '@/api/persona';

export function usePersonas() {
  const [personas, setPersonas] = useState<Persona[]>([]);
  const [loading, setLoading] = useState(false);

  const loadPersonas = useCallback(async () => {
    setLoading(true);
    try {
      const data = await fetchPersonas();
      setPersonas(data);
    } finally {
      setLoading(false);
    }
  }, []);

  const createPersona = useCallback(async (request: PersonaRequest) => {
    const created = await apiCreatePersona(request);
    setPersonas(prev => [...prev, created]);
    return created;
  }, []);

  const updatePersona = useCallback(async (id: string, request: PersonaRequest) => {
    const updated = await apiUpdatePersona(id, request);
    setPersonas(prev => prev.map(p => p.id === id ? updated : p));
    return updated;
  }, []);

  const regeneratePrompt = useCallback(async (id: string) => {
    const updated = await apiRegeneratePrompt(id);
    setPersonas(prev => prev.map(p => p.id === id ? updated : p));
    return updated;
  }, []);

  const deletePersona = useCallback(async (id: string) => {
    await apiDeletePersona(id);
    setPersonas(prev => prev.filter(p => p.id !== id));
  }, []);

  return {
    personas,
    loading,
    loadPersonas,
    createPersona,
    updatePersona,
    regeneratePrompt,
    deletePersona,
  };
}
