import { useState, useEffect, useCallback } from 'react';
import { toast } from 'sonner';
import type { Document } from '../types';
import { fetchDocuments, uploadDocument } from '../api/client';

export function useDocuments() {
  const [documents, setDocuments] = useState<Document[]>([]);
  const [uploading, setUploading] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const docs = await fetchDocuments();
      setDocuments(docs);
      return docs;
    } catch (err) {
      console.error('Failed to load documents:', err);
      toast.error('문서 목록 조회 실패');
      return [];
    }
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  const upload = async (file: File, isPublic = false) => {
    setUploading(true);
    try {
      await uploadDocument(file, isPublic);
      const poll = setInterval(async () => {
        try {
          const docs = await fetchDocuments();
          setDocuments(docs);
          const allDone = docs.every(d => d.status === 'COMPLETED' || d.status === 'FAILED');
          if (allDone) clearInterval(poll);
        } catch {
          clearInterval(poll);
        }
      }, 2000);
    } catch (err) {
      toast.error('문서 업로드 실패');
      console.error(err);
    } finally {
      setUploading(false);
      await refresh();
    }
  };

  return { documents, uploading, upload, refresh };
}
