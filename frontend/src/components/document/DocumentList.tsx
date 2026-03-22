import { useState, useEffect } from 'react';
import { toast } from 'sonner';
import { Badge } from '@/components/ui/badge';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Checkbox } from '@/components/ui/checkbox';
import { Settings2 } from 'lucide-react';
import { fetchTags, fetchCollections, setDocumentTags, setDocumentCollections } from '@/api/client';
import type { Document, DocumentTag, DocumentCollection } from '@/types';

interface Props {
  documents: Document[];
  onRefresh?: () => void;
  refreshKey?: number;
}

const STATUS_CONFIG: Record<string, { label: string; variant: 'default' | 'secondary' | 'destructive' | 'outline'; className: string }> = {
  PENDING: { label: '대기', variant: 'outline', className: 'text-muted-foreground' },
  PROCESSING: { label: '처리 중', variant: 'outline', className: 'border-yellow-500 text-yellow-600' },
  COMPLETED: { label: '완료', variant: 'outline', className: 'border-green-500 text-green-600' },
  FAILED: { label: '실패', variant: 'destructive', className: '' },
};

export function DocumentList({ documents, onRefresh, refreshKey = 0 }: Props) {
  const [allTags, setAllTags] = useState<DocumentTag[]>([]);
  const [allCollections, setAllCollections] = useState<DocumentCollection[]>([]);

  useEffect(() => {
    fetchTags().then(setAllTags).catch(() => {});
    fetchCollections().then(setAllCollections).catch(() => {});
  }, [refreshKey]);

  const handleTagToggle = async (doc: Document, tagId: string, checked: boolean) => {
    const currentIds = doc.tags?.map(t => t.id) || [];
    const newIds = checked
      ? [...currentIds, tagId]
      : currentIds.filter(id => id !== tagId);
    try {
      await setDocumentTags(doc.id, newIds);
      onRefresh?.();
    } catch {
      toast.error('태그 설정 실패');
    }
  };

  const handleCollectionToggle = async (doc: Document, colId: string, checked: boolean) => {
    const currentIds = doc.collections?.map(c => c.id) || [];
    const newIds = checked
      ? [...currentIds, colId]
      : currentIds.filter(id => id !== colId);
    try {
      await setDocumentCollections(doc.id, newIds);
      onRefresh?.();
    } catch {
      toast.error('컬렉션 설정 실패');
    }
  };

  if (documents.length === 0) {
    return <p className="text-sm text-muted-foreground text-center">업로드된 문서 없음</p>;
  }

  const hasOptions = allTags.length > 0 || allCollections.length > 0;

  return (
    <ul className="space-y-0">
      {documents.map(doc => {
        const config = STATUS_CONFIG[doc.status];
        const docTagIds = new Set(doc.tags?.map(t => t.id) || []);
        const docColIds = new Set(doc.collections?.map(c => c.id) || []);
        return (
          <li key={doc.id} className="flex items-center gap-2 py-1.5 text-sm border-b border-border last:border-b-0">
            <Badge variant={config.variant} className={`text-[10px] shrink-0 ${config.className}`}>
              {config.label}
            </Badge>
            {doc.isPublic && (
              <Badge variant="secondary" className="text-[10px] shrink-0">공용</Badge>
            )}
            <div className="flex-1 min-w-0">
              <span className="truncate block" title={doc.filename}>{doc.filename}</span>
              {((doc.tags && doc.tags.length > 0) || (doc.collections && doc.collections.length > 0)) && (
                <div className="flex gap-1 mt-0.5 flex-wrap">
                  {doc.tags?.map(tag => (
                    <Badge key={tag.id} variant="outline" className="text-[9px] px-1 py-0 h-4">
                      {tag.name}
                    </Badge>
                  ))}
                  {doc.collections?.map(col => (
                    <Badge key={col.id} variant="secondary" className="text-[9px] px-1 py-0 h-4">
                      {col.name}
                    </Badge>
                  ))}
                </div>
              )}
            </div>
            {doc.status === 'COMPLETED' && hasOptions && (
              <Popover>
                <PopoverTrigger className="inline-flex items-center justify-center h-6 w-6 shrink-0 rounded-md hover:bg-accent cursor-pointer">
                  <Settings2 className="h-3 w-3" />
                </PopoverTrigger>
                <PopoverContent className="w-44 p-2" align="end">
                  {allTags.length > 0 && (
                    <div className="mb-2">
                      <p className="text-xs font-medium text-muted-foreground mb-1">태그</p>
                      {allTags.map(tag => (
                        <label key={tag.id} className="flex items-center gap-2 py-0.5 text-xs cursor-pointer">
                          <Checkbox
                            checked={docTagIds.has(tag.id)}
                            onCheckedChange={(checked) => handleTagToggle(doc, tag.id, checked === true)}
                          />
                          {tag.name}
                        </label>
                      ))}
                    </div>
                  )}
                  {allCollections.length > 0 && (
                    <div>
                      <p className="text-xs font-medium text-muted-foreground mb-1">컬렉션</p>
                      {allCollections.map(col => (
                        <label key={col.id} className="flex items-center gap-2 py-0.5 text-xs cursor-pointer">
                          <Checkbox
                            checked={docColIds.has(col.id)}
                            onCheckedChange={(checked) => handleCollectionToggle(doc, col.id, checked === true)}
                          />
                          {col.name}
                        </label>
                      ))}
                    </div>
                  )}
                </PopoverContent>
              </Popover>
            )}
            {doc.status === 'COMPLETED' && !hasOptions && (
              <span className="text-xs text-muted-foreground shrink-0">{doc.chunkCount} chunks</span>
            )}
            {doc.status === 'FAILED' && (
              <span className="text-xs text-destructive shrink-0" title={doc.errorMessage || ''}>error</span>
            )}
          </li>
        );
      })}
    </ul>
  );
}
