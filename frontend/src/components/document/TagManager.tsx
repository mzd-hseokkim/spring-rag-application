import { useState, useEffect, useCallback } from 'react';
import { toast } from 'sonner';
import { Plus, X } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { fetchTags, createTag, deleteTag } from '@/api/client';
import type { DocumentTag } from '@/types';

interface Props {
  onTagsChange?: () => void;
}

export function TagManager({ onTagsChange }: Props) {
  const [tags, setTags] = useState<DocumentTag[]>([]);
  const [newName, setNewName] = useState('');
  const [adding, setAdding] = useState(false);

  const load = useCallback(async () => {
    try {
      setTags(await fetchTags());
    } catch {
      toast.error('태그 목록 조회 실패');
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleAdd = async () => {
    if (!newName.trim()) return;
    try {
      await createTag(newName.trim());
      setNewName('');
      setAdding(false);
      await load();
      onTagsChange?.();
    } catch {
      toast.error('태그 생성 실패');
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await deleteTag(id);
      await load();
      onTagsChange?.();
    } catch {
      toast.error('태그 삭제 실패');
    }
  };

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <h3 className="text-xs font-medium text-muted-foreground">태그</h3>
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
            placeholder="태그 이름"
            className="h-7 text-xs"
            autoFocus
          />
          <Button size="sm" className="h-7 text-xs px-2" onClick={handleAdd}>추가</Button>
        </div>
      )}
      <div className="flex flex-wrap gap-1">
        {tags.map(tag => (
          <Badge key={tag.id} variant="outline" className="text-xs gap-1 pr-1">
            {tag.name}
            <button onClick={() => handleDelete(tag.id)} className="hover:text-destructive">
              <X className="h-3 w-3" />
            </button>
          </Badge>
        ))}
        {tags.length === 0 && !adding && (
          <p className="text-xs text-muted-foreground">등록된 태그 없음</p>
        )}
      </div>
    </div>
  );
}
