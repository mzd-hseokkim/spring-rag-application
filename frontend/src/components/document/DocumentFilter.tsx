import { useState, useEffect } from 'react';
import { Checkbox } from '@/components/ui/checkbox';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Filter } from 'lucide-react';
import { fetchTags, fetchCollections } from '@/api/client';
import type { DocumentTag, DocumentCollection } from '@/types';

interface Props {
  refreshKey?: number;
  onFilterChange: (tagIds: Set<string>, collectionIds: Set<string>) => void;
}

export function DocumentFilter({ refreshKey = 0, onFilterChange }: Props) {
  const [tags, setTags] = useState<DocumentTag[]>([]);
  const [collections, setCollections] = useState<DocumentCollection[]>([]);
  const [selectedTagIds, setSelectedTagIds] = useState<Set<string>>(
    () => new Set(JSON.parse(localStorage.getItem('docFilter:tagIds') || '[]'))
  );
  const [selectedCollectionIds, setSelectedCollectionIds] = useState<Set<string>>(
    () => new Set(JSON.parse(localStorage.getItem('docFilter:collectionIds') || '[]'))
  );

  useEffect(() => {
    fetchTags().then(setTags).catch(() => {});
    fetchCollections().then(setCollections).catch(() => {});
  }, [refreshKey]);

  useEffect(() => {
    localStorage.setItem('docFilter:tagIds', JSON.stringify([...selectedTagIds]));
    onFilterChange(selectedTagIds, selectedCollectionIds);
  }, [selectedTagIds]);

  useEffect(() => {
    localStorage.setItem('docFilter:collectionIds', JSON.stringify([...selectedCollectionIds]));
    onFilterChange(selectedTagIds, selectedCollectionIds);
  }, [selectedCollectionIds]);

  const toggleTag = (id: string) => {
    setSelectedTagIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const toggleCollection = (id: string) => {
    setSelectedCollectionIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const clearFilters = () => {
    setSelectedTagIds(new Set());
    setSelectedCollectionIds(new Set());
  };

  const hasFilters = tags.length > 0 || collections.length > 0;
  if (!hasFilters) return null;

  const activeFilterCount = selectedTagIds.size + selectedCollectionIds.size;
  const selectedLabels: string[] = [
    ...tags.filter(t => selectedTagIds.has(t.id)).map(t => t.name),
    ...collections.filter(c => selectedCollectionIds.has(c.id)).map(c => c.name),
  ];

  return (
    <Popover>
      <PopoverTrigger
        className="inline-flex items-center gap-1.5 w-full rounded-md border border-input bg-background px-3 h-8 text-xs shadow-xs hover:bg-accent hover:text-accent-foreground cursor-pointer"
      >
        <Filter className="h-3 w-3 shrink-0" />
        {activeFilterCount > 0 ? (
          <span className="truncate flex-1 text-left">{selectedLabels.join(', ')}</span>
        ) : (
          <span className="flex-1 text-left text-muted-foreground">문서 필터</span>
        )}
        {activeFilterCount > 0 && (
          <Badge variant="secondary" className="text-[10px] px-1 py-0 h-4 shrink-0">
            {activeFilterCount}
          </Badge>
        )}
      </PopoverTrigger>
      <PopoverContent className="w-52 p-3" align="start">
        <div className="space-y-3">
          {tags.length > 0 && (
            <div>
              <p className="text-xs font-medium text-muted-foreground mb-1.5">태그</p>
              <div className="space-y-1">
                {tags.map(tag => (
                  <label key={tag.id} className="flex items-center gap-2 text-sm cursor-pointer py-0.5">
                    <Checkbox
                      checked={selectedTagIds.has(tag.id)}
                      onCheckedChange={() => toggleTag(tag.id)}
                    />
                    {tag.name}
                  </label>
                ))}
              </div>
            </div>
          )}
          {collections.length > 0 && (
            <div>
              <p className="text-xs font-medium text-muted-foreground mb-1.5">컬렉션</p>
              <div className="space-y-1">
                {collections.map(col => (
                  <label key={col.id} className="flex items-center gap-2 text-sm cursor-pointer py-0.5">
                    <Checkbox
                      checked={selectedCollectionIds.has(col.id)}
                      onCheckedChange={() => toggleCollection(col.id)}
                    />
                    {col.name}
                  </label>
                ))}
              </div>
            </div>
          )}
          {activeFilterCount > 0 && (
            <Button variant="ghost" size="sm" className="w-full text-xs" onClick={clearFilters}>
              필터 초기화
            </Button>
          )}
        </div>
      </PopoverContent>
    </Popover>
  );
}
