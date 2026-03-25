import { useState, useEffect, useCallback } from 'react';
import { toast } from 'sonner';
import { Plus, X } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { fetchCollections, createCollection, deleteCollection } from '@/api/client';
import type { DocumentCollection } from '@/types';

interface Props {
  onCollectionsChange?: () => void;
}

export function CollectionManager({ onCollectionsChange }: Props) {
  const [collections, setCollections] = useState<DocumentCollection[]>([]);
  const [newName, setNewName] = useState('');
  const [adding, setAdding] = useState(false);

  const load = useCallback(async () => {
    try {
      setCollections(await fetchCollections());
    } catch {
      toast.error('컬렉션 목록 조회 실패');
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleAdd = async () => {
    if (!newName.trim()) return;
    try {
      await createCollection(newName.trim());
      setNewName('');
      setAdding(false);
      await load();
      onCollectionsChange?.();
    } catch {
      toast.error('컬렉션 생성 실패');
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await deleteCollection(id);
      await load();
      onCollectionsChange?.();
    } catch {
      toast.error('컬렉션 삭제 실패');
    }
  };

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <h3 className="text-xs font-medium text-muted-foreground">컬렉션</h3>
        <Button variant="ghost" size="icon" className="h-5 w-5" onClick={() => setAdding(!adding)}>
          <Plus className="h-3 w-3" />
        </Button>
      </div>
      {adding && (
        <div className="flex gap-1">
          <Input
            value={newName}
            onChange={e => setNewName(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && handleAdd()}
            placeholder="컬렉션 이름"
            className="h-7 text-xs"
            autoFocus
          />
          <Button size="sm" className="h-7 text-xs px-2" onClick={handleAdd}>추가</Button>
        </div>
      )}
      <div className="flex flex-wrap gap-1">
        {collections.map(col => (
          <Badge key={col.id} variant="outline" className="text-xs gap-1 pr-1">
            {col.name}
            <button onClick={() => handleDelete(col.id)} className="hover:text-destructive cursor-pointer">
              <X className="h-3 w-3" />
            </button>
          </Badge>
        ))}
        {collections.length === 0 && !adding && (
          <p className="text-xs text-muted-foreground">등록된 컬렉션 없음</p>
        )}
      </div>
    </div>
  );
}
