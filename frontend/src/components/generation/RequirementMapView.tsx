import { useState } from 'react';
import type { OutlineNode } from '@/api/generation';
import { Badge } from '@/components/ui/badge';
import { ChevronRight, ChevronDown, AlertCircle, CheckCircle2 } from 'lucide-react';

interface Requirement {
  id: string;
  category: string;
  item: string;
  description: string;
  importance: string;
}

interface RequirementMapViewProps {
  outline: OutlineNode[];
  requirements: Requirement[];
  mapping: Record<string, string[]>;
  onChange?: (mapping: Record<string, string[]>) => void;
  readOnly?: boolean;
}

const importanceBadge = (imp: string) => {
  const cls = imp === '상' ? 'bg-red-100 text-red-700' : imp === '중' ? 'bg-yellow-100 text-yellow-700' : 'bg-gray-100 text-gray-600';
  return <span className={`px-1.5 py-0.5 rounded text-xs font-medium ${cls}`}>{imp}</span>;
};

export function RequirementMapView({ outline, requirements, mapping, onChange, readOnly }: RequirementMapViewProps) {
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set(outline.map(n => n.key)));

  const reqMap = new Map(requirements.map(r => [r.id, r]));

  const toggleExpand = (key: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      next.has(key) ? next.delete(key) : next.add(key);
      return next;
    });
  };

  const getReqCount = (key: string): number => {
    return (mapping[key] || []).length;
  };

  const renderOutlineNode = (node: OutlineNode, depth: number) => {
    const isExpanded = expanded.has(node.key);
    const hasChildren = node.children.length > 0;
    const count = getReqCount(node.key);
    const isSelected = selectedKey === node.key;
    const isLeaf = !hasChildren;

    return (
      <div key={node.key}>
        <div
          className={`flex items-center gap-1.5 py-1.5 px-2 rounded-md cursor-pointer transition-colors ${
            isSelected ? 'bg-primary/10 border border-primary/30' : 'hover:bg-accent/50'
          } ${depth > 0 ? 'ml-5' : ''}`}
          onClick={() => isLeaf && setSelectedKey(node.key)}
        >
          {hasChildren ? (
            <button onClick={(e) => { e.stopPropagation(); toggleExpand(node.key); }} className="p-0.5">
              {isExpanded ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
            </button>
          ) : <span className="w-4.5" />}

          <span className="text-xs text-muted-foreground font-mono w-8 shrink-0">{node.key}</span>
          <span className="text-sm flex-1 truncate">{node.title}</span>
          {isLeaf && count > 0 && (
            <Badge variant="secondary" className="text-xs">{count}개</Badge>
          )}
        </div>

        {isExpanded && node.children.map(child => renderOutlineNode(child, depth + 1))}
      </div>
    );
  };

  const selectedReqs = selectedKey ? (mapping[selectedKey] || []).map(id => reqMap.get(id)).filter(Boolean) as Requirement[] : [];

  // 매핑되지 않은 요구사항
  const allMappedIds = new Set(Object.values(mapping).flat());
  const unmapped = requirements.filter(r => !allMappedIds.has(r.id));

  return (
    <div className="grid grid-cols-[1fr_1fr] gap-4 min-h-[400px]">
      {/* 좌측: 목차 트리 */}
      <div className="border rounded-lg p-3 overflow-y-auto">
        <p className="text-xs text-muted-foreground mb-2 font-medium">목차 (클릭하여 요구사항 확인)</p>
        {outline.map(node => renderOutlineNode(node, 0))}

        {unmapped.length > 0 && (
          <div className="mt-3 pt-3 border-t">
            <div
              className={`flex items-center gap-1.5 py-1.5 px-2 rounded-md cursor-pointer transition-colors ${
                selectedKey === '__unmapped' ? 'bg-destructive/10 border border-destructive/30' : 'hover:bg-accent/50'
              }`}
              onClick={() => setSelectedKey('__unmapped')}
            >
              <AlertCircle className="h-3.5 w-3.5 text-destructive" />
              <span className="text-sm text-destructive">미배치 요구사항</span>
              <Badge variant="destructive" className="text-xs ml-auto">{unmapped.length}개</Badge>
            </div>
          </div>
        )}
      </div>

      {/* 우측: 선택된 목차의 요구사항 */}
      <div className="border rounded-lg p-3 overflow-y-auto">
        {!selectedKey ? (
          <p className="text-sm text-muted-foreground py-8 text-center">좌측에서 목차 항목을 선택하세요.</p>
        ) : selectedKey === '__unmapped' ? (
          <>
            <p className="text-xs text-muted-foreground mb-2 font-medium">미배치 요구사항 ({unmapped.length}개)</p>
            <div className="space-y-2">
              {unmapped.map(req => (
                <div key={req.id} className="p-2 rounded border bg-muted/20 text-sm">
                  <div className="flex items-center gap-2">
                    <span className="font-mono text-xs text-muted-foreground">{req.id}</span>
                    {importanceBadge(req.importance)}
                    <span className="font-medium">{req.item}</span>
                  </div>
                  <p className="text-xs text-muted-foreground mt-1">{req.description}</p>
                </div>
              ))}
            </div>
          </>
        ) : (
          <>
            <p className="text-xs text-muted-foreground mb-2 font-medium">
              {selectedKey} 에 배치된 요구사항 ({selectedReqs.length}개)
            </p>
            {selectedReqs.length === 0 ? (
              <p className="text-sm text-muted-foreground py-4 text-center">배치된 요구사항이 없습니다.</p>
            ) : (
              <div className="space-y-2">
                {selectedReqs.map(req => (
                  <div key={req.id} className="p-2 rounded border bg-muted/20 text-sm">
                    <div className="flex items-center gap-2">
                      <span className="font-mono text-xs text-muted-foreground">{req.id}</span>
                      {importanceBadge(req.importance)}
                      <span className="font-medium">{req.item}</span>
                    </div>
                    <p className="text-xs text-muted-foreground mt-1">{req.description}</p>
                  </div>
                ))}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
