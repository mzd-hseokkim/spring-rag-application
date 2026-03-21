import { useState, useEffect, useCallback } from 'react';
import type { Document } from '../types';
import { fetchDocuments, uploadDocument } from '../api/client';

export function useDocuments() {
  const [documents, setDocuments] = useState<Document[]>([]);
  const [uploading, setUploading] = useState(false);

  const refresh = useCallback(async () => {
    const docs = await fetchDocuments();
    setDocuments(docs);
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  const upload = async (file: File, isPublic = false) => {
    setUploading(true);
    try {
      await uploadDocument(file, isPublic);
      // 폴링으로 상태 갱신
      const poll = setInterval(async () => {
        const docs = await fetchDocuments();
        setDocuments(docs);
        const allDone = docs.every(d => d.status === 'COMPLETED' || d.status === 'FAILED');
        if (allDone) clearInterval(poll);
      }, 2000);
    } finally {
      setUploading(false);
      await refresh();
    }
  };

  return { documents, uploading, upload, refresh };
}
