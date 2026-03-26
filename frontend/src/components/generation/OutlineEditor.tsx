import { useState, useMemo } from 'react';
import type { OutlineNode } from '@/api/generation';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { ChevronRight, ChevronDown, Plus, Trash2, ArrowUp, ArrowDown } from 'lucide-react';

/** "1.2.10" 같은 계층 번호를 자연수 순서로 비교 */
function compareKeys(a: string, b: string): number {
  const pa = a.split('.').map(s => parseInt(s, 10) || 0);
  const pb = b.split('.').map(s => parseInt(s, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const diff = (pa[i] ?? -1) - (pb[i] ?? -1);
    if (diff !== 0) return diff;
  }
  return 0;
}

function sortOutline(nodes: OutlineNode[]): OutlineNode[] {
  return [...nodes]
    .sort((a, b) => compareKeys(a.key, b.key))
    .map(n => ({ ...n, children: sortOutline(n.children) }));
}

interface OutlineEditorProps {
  outline: OutlineNode[];
  onChange: (outline: OutlineNode[]) => void;
  readOnly?: boolean;
}

export function OutlineEditor({ outline: rawOutline, onChange, readOnly }: OutlineEditorProps) {
  const outline = useMemo(() => sortOutline(rawOutline), [rawOutline]);
  const [expanded, setExpanded] = useState<Set<string>>(new Set(outline.map(n => n.key)));

  const toggleExpand = (key: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      next.has(key) ? next.delete(key) : next.add(key);
      return next;
    });
  };

  const updateNode = (keys: string[], field: 'title' | 'description', value: string) => {
    const update = (nodes: OutlineNode[], path: string[]): OutlineNode[] => {
      if (path.length === 0) return nodes;
      return nodes.map(n => {
        if (n.key === path[0]) {
          if (path.length === 1) return { ...n, [field]: value };
          return { ...n, children: update(n.children, path.slice(1)) };
        }
        return n;
      });
    };
    onChange(update(outline, keys));
  };

  const removeNode = (keys: string[]) => {
    const remove = (nodes: OutlineNode[], path: string[]): OutlineNode[] => {
      if (path.length === 1) return nodes.filter(n => n.key !== path[0]);
      return nodes.map(n => {
        if (n.key === path[0]) return { ...n, children: remove(n.children, path.slice(1)) };
        return n;
      });
    };
    onChange(remove(outline, keys));
  };

  const moveNode = (keys: string[], direction: -1 | 1) => {
    const move = (nodes: OutlineNode[], path: string[]): OutlineNode[] => {
      if (path.length === 1) {
        const idx = nodes.findIndex(n => n.key === path[0]);
        if (idx < 0) return nodes;
        const newIdx = idx + direction;
        if (newIdx < 0 || newIdx >= nodes.length) return nodes;
        const arr = [...nodes];
        [arr[idx], arr[newIdx]] = [arr[newIdx], arr[idx]];
        return arr;
      }
      return nodes.map(n => {
        if (n.key === path[0]) return { ...n, children: move(n.children, path.slice(1)) };
        return n;
      });
    };
    onChange(move(outline, keys));
  };

  const addChild = (parentKeys: string[]) => {
    const add = (nodes: OutlineNode[], path: string[]): OutlineNode[] => {
      if (path.length === 0) {
        const newKey = String(nodes.length + 1);
        return [...nodes, { key: newKey, title: '새 항목', description: '', children: [] }];
      }
      return nodes.map(n => {
        if (n.key === path[0]) {
          if (path.length === 1) {
            const newKey = `${n.key}.${n.children.length + 1}`;
            return { ...n, children: [...n.children, { key: newKey, title: '새 하위 항목', description: '', children: [] }] };
          }
          return { ...n, children: add(n.children, path.slice(1)) };
        }
        return n;
      });
    };
    onChange(add(outline, parentKeys));
    if (parentKeys.length > 0) setExpanded(prev => new Set([...prev, parentKeys[parentKeys.length - 1]]));
  };

  const renderNode = (node: OutlineNode, path: string[], depth: number) => {
    const isExpanded = expanded.has(node.key);
    const hasChildren = node.children.length > 0;

    return (
      <div key={node.key} className="select-none">
        <div className={`flex items-center gap-1 py-1.5 px-2 rounded-md hover:bg-accent/50 group ${depth > 0 ? 'ml-6' : ''}`}>
          {hasChildren ? (
            <button onClick={() => toggleExpand(node.key)} className="p-0.5 cursor-pointer">
              {isExpanded ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
            </button>
          ) : (
            <span className="w-4.5" />
          )}

          <span className="text-xs text-muted-foreground font-mono w-8 shrink-0">{node.key}</span>

          {readOnly ? (
            <span className="text-sm flex-1">{node.title}</span>
          ) : (
            <Input
              value={node.title}
              onChange={(e) => updateNode(path, 'title', e.target.value)}
              className="h-7 text-sm flex-1"
            />
          )}

          {!readOnly && (
            <div className="flex items-center gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity">
              <button onClick={() => moveNode(path, -1)} className="p-0.5 rounded hover:bg-muted cursor-pointer" title="위로">
                <ArrowUp className="h-3.5 w-3.5" />
              </button>
              <button onClick={() => moveNode(path, 1)} className="p-0.5 rounded hover:bg-muted cursor-pointer" title="아래로">
                <ArrowDown className="h-3.5 w-3.5" />
              </button>
              {depth < 2 && (
                <button onClick={() => addChild(path)} className="p-0.5 rounded hover:bg-muted cursor-pointer" title="하위 항목 추가">
                  <Plus className="h-3.5 w-3.5" />
                </button>
              )}
              <button onClick={() => removeNode(path)} className="p-0.5 rounded hover:bg-destructive/10 text-destructive cursor-pointer" title="삭제">
                <Trash2 className="h-3.5 w-3.5" />
              </button>
            </div>
          )}
        </div>

        {node.description && (
          <p className={`text-xs text-muted-foreground px-2 pb-1 ${depth > 0 ? 'ml-6' : ''} ml-10`}>{node.description}</p>
        )}

        {isExpanded && node.children.map(child =>
          renderNode(child, [...path, child.key], depth + 1)
        )}
      </div>
    );
  };

  return (
    <div className="flex flex-col" style={{ height: 'calc(100vh - 320px)', minHeight: '400px' }}>
      <div className="flex-1 overflow-y-auto space-y-1 pr-1">
        {outline.map(node => renderNode(node, [node.key], 0))}
      </div>
      {!readOnly && (
        <Button variant="outline" size="sm" onClick={() => addChild([])} className="mt-2 shrink-0">
          <Plus className="h-3.5 w-3.5 mr-1" />
          대분류 추가
        </Button>
      )}
    </div>
  );
}
