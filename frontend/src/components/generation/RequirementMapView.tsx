import { useState, useMemo, useRef, useEffect } from 'react';
import type { OutlineNode } from '@/api/generation';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { ChevronRight, ChevronDown, AlertCircle, List, FolderTree, Plus, X, Search, Wand2, Loader2 } from 'lucide-react';

/** 섹션별 배치 패널 — 배치된 요구사항 목록 + 추가/제거 */
function SectionMappingPanel({ selectedKey, selectedReqs, unmapped, mapping, onChange, readOnly }: {
  selectedKey: string | null;
  selectedReqs: Requirement[];
  unmapped: Requirement[];
  mapping: Record<string, string[]>;
  onChange?: (mapping: Record<string, string[]>) => void;
  readOnly?: boolean;
}) {
  const [showAddPanel, setShowAddPanel] = useState(false);
  const [addSearch, setAddSearch] = useState('');

  const addReqToSection = (reqId: string) => {
    if (!selectedKey || !onChange || selectedKey === '__unmapped') return;
    const updated = { ...mapping };
    const list = [...(updated[selectedKey] || [])];
    if (!list.includes(reqId)) {
      list.push(reqId);
      updated[selectedKey] = list;
      onChange(updated);
    }
  };

  const removeReqFromSection = (reqId: string) => {
    if (!selectedKey || !onChange || selectedKey === '__unmapped') return;
    const updated = { ...mapping };
    updated[selectedKey] = (updated[selectedKey] || []).filter(id => id !== reqId);
    onChange(updated);
  };

  // 추가 패널용 필터링된 미배치 요구사항
  const filteredUnmapped = useMemo(() => {
    if (!addSearch.trim()) return unmapped;
    const q = addSearch.toLowerCase();
    return unmapped.filter(r =>
      r.id.toLowerCase().includes(q) || r.item.toLowerCase().includes(q) || r.description.toLowerCase().includes(q)
    );
  }, [unmapped, addSearch]);

  if (!selectedKey) {
    return <p className="text-sm text-muted-foreground py-8 text-center">좌측에서 목차 항목을 선택하세요.</p>;
  }

  if (selectedKey === '__unmapped') {
    return (
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
    );
  }

  return (
    <>
      <div className="flex items-center justify-between mb-2">
        <p className="text-xs text-muted-foreground font-medium">
          {selectedKey} 에 배치된 요구사항 ({selectedReqs.length}개)
        </p>
        {!readOnly && unmapped.length > 0 && (
          <button
            onClick={() => { setShowAddPanel(!showAddPanel); setAddSearch(''); }}
            className={`flex items-center gap-1 text-xs px-2 py-1 rounded transition-colors cursor-pointer ${
              showAddPanel ? 'bg-primary text-primary-foreground' : 'bg-muted hover:bg-muted/80 text-foreground'
            }`}
          >
            <Plus className="h-3 w-3" />미배치에서 추가
          </button>
        )}
      </div>

      {/* 추가 패널 */}
      {showAddPanel && (
        <div className="mb-3 border rounded-lg bg-muted/20 p-2 space-y-2">
          <div className="relative">
            <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground" />
            <Input
              value={addSearch} onChange={e => setAddSearch(e.target.value)}
              placeholder="미배치 요구사항 검색..." className="h-7 text-xs pl-8" autoFocus
            />
          </div>
          <div className="max-h-48 overflow-y-auto space-y-1">
            {filteredUnmapped.length === 0 ? (
              <p className="text-xs text-muted-foreground text-center py-2">
                {unmapped.length === 0 ? '미배치 요구사항이 없습니다.' : '검색 결과가 없습니다.'}
              </p>
            ) : filteredUnmapped.map(req => (
              <button
                key={req.id}
                onClick={() => addReqToSection(req.id)}
                className="w-full flex items-center gap-2 p-1.5 rounded text-left text-xs hover:bg-accent/50 transition-colors cursor-pointer"
              >
                <Plus className="h-3 w-3 text-primary shrink-0" />
                <span className="font-mono text-muted-foreground shrink-0">{req.id}</span>
                {importanceBadge(req.importance)}
                <span className="truncate">{req.item}</span>
              </button>
            ))}
          </div>
        </div>
      )}

      {/* 배치된 요구사항 목록 */}
      {selectedReqs.length === 0 ? (
        <p className="text-sm text-muted-foreground py-4 text-center">배치된 요구사항이 없습니다.</p>
      ) : (
        <div className="space-y-2">
          {selectedReqs.map(req => (
            <div key={req.id} className="p-2 rounded border bg-muted/20 text-sm group">
              <div className="flex items-center gap-2">
                <span className="font-mono text-xs text-muted-foreground">{req.id}</span>
                {importanceBadge(req.importance)}
                <span className="font-medium flex-1">{req.item}</span>
                {!readOnly && onChange && (
                  <button
                    onClick={() => removeReqFromSection(req.id)}
                    className="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:bg-destructive/10 text-destructive transition-opacity cursor-pointer"
                    title="이 섹션에서 제거"
                  >
                    <X className="h-3.5 w-3.5" />
                  </button>
                )}
              </div>
              <p className="text-xs text-muted-foreground mt-1">{req.description}</p>
            </div>
          ))}
        </div>
      )}
    </>
  );
}

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

/** 요구사항 ID 정렬: "SFR-001" < "SFR-002" < "NFR-001" < "REQ-01" (접두어별 그룹, 숫자순) */
function compareReqIds(a: string, b: string): number {
  const parseId = (id: string) => {
    const m = id.match(/^([A-Za-z]+)-?(\d+)$/);
    return m ? { prefix: m[1], num: parseInt(m[2], 10) } : { prefix: id, num: 0 };
  };
  const pa = parseId(a), pb = parseId(b);
  if (pa.prefix !== pb.prefix) return pa.prefix.localeCompare(pb.prefix);
  return pa.num - pb.num;
}

function sortRequirements(reqs: Requirement[]): Requirement[] {
  return [...reqs].sort((a, b) => compareReqIds(a.id, b.id));
}

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
  onGenerateUnmapped?: () => Promise<void>;
  readOnly?: boolean;
}

const importanceBadge = (imp: string) => {
  const cls = imp === '상' ? 'bg-red-100 text-red-700' : imp === '중' ? 'bg-yellow-100 text-yellow-700' : 'bg-gray-100 text-gray-600';
  return <span className={`px-1.5 py-0.5 rounded text-xs font-medium ${cls}`}>{imp}</span>;
};

type RightTab = 'section' | 'all';

export function RequirementMapView({ outline: rawOutline, requirements, mapping, onChange, onGenerateUnmapped, readOnly }: RequirementMapViewProps) {
  const outline = useMemo(() => sortOutline(rawOutline), [rawOutline]);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set(outline.map(n => n.key)));  // top-level keys are unique
  const [rightTab, setRightTab] = useState<RightTab>('all');
  const [showUnmappedOnly, setShowUnmappedOnly] = useState(false);
  const [dragOverKey, setDragOverKey] = useState<string | null>(null);
  const [generatingUnmapped, setGeneratingUnmapped] = useState(false);
  const allReqScrollRef = useRef<HTMLDivElement>(null);
  const prevReqCountRef = useRef(requirements.length);

  // 요구사항이 추가되면 전체 요구사항 탭 하단으로 자동 스크롤
  useEffect(() => {
    if (requirements.length > prevReqCountRef.current && rightTab === 'all' && allReqScrollRef.current) {
      allReqScrollRef.current.scrollTop = allReqScrollRef.current.scrollHeight;
    }
    prevReqCountRef.current = requirements.length;
  }, [requirements.length, rightTab]);

  const reqMap = new Map(requirements.map(r => [r.id, r]));

  // 요구사항 ID → 배치된 섹션 목록 (역방향 매핑)
  const reqToSections = useMemo(() => {
    const result = new Map<string, string[]>();
    for (const [sectionKey, reqIds] of Object.entries(mapping)) {
      for (const reqId of reqIds) {
        const list = result.get(reqId) || [];
        list.push(sectionKey);
        result.set(reqId, list);
      }
    }
    return result;
  }, [mapping]);

  // 섹션 key → title 플랫 맵
  const sectionTitles = useMemo(() => {
    const result = new Map<string, string>();
    const collect = (nodes: OutlineNode[]) => {
      for (const node of nodes) {
        result.set(node.key, node.title);
        collect(node.children);
      }
    };
    collect(outline);
    return result;
  }, [outline]);

  const toggleExpand = (pathId: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      next.has(pathId) ? next.delete(pathId) : next.add(pathId);
      return next;
    });
  };

  // DnD: 요구사항을 목차에 드롭하여 배치
  const handleDragStart = (e: React.DragEvent, reqId: string) => {
    e.dataTransfer.setData('text/plain', reqId);
    e.dataTransfer.effectAllowed = 'copy';
  };

  const handleDragOver = (e: React.DragEvent, nodeKey: string, isLeaf: boolean) => {
    if (!isLeaf || readOnly || !onChange) return;
    e.preventDefault();
    e.dataTransfer.dropEffect = 'copy';
    setDragOverKey(nodeKey);
  };

  const handleDragLeave = () => {
    setDragOverKey(null);
  };

  const handleDrop = (e: React.DragEvent, nodeKey: string) => {
    e.preventDefault();
    setDragOverKey(null);
    if (!onChange) return;
    const reqId = e.dataTransfer.getData('text/plain');
    if (!reqId) return;
    const updated = { ...mapping };
    const list = [...(updated[nodeKey] || [])];
    if (!list.includes(reqId)) {
      list.push(reqId);
      updated[nodeKey] = list;
      onChange(updated);
    }
  };

  const getReqCount = (key: string): number => {
    return (mapping[key] || []).length;
  };

  const renderOutlineNode = (node: OutlineNode, depth: number, parentPath: string) => {
    const pathId = parentPath ? `${parentPath}/${node.key}` : node.key;
    const isExpanded = expanded.has(pathId);
    const hasChildren = node.children.length > 0;
    const count = getReqCount(node.key);
    const isSelected = selectedKey === node.key;
    const isLeaf = !hasChildren;

    const isDragOver = dragOverKey === node.key;

    return (
      <div key={pathId}>
        <div
          className={`flex items-center gap-1.5 py-1.5 px-2 rounded-md transition-colors ${
            isDragOver ? 'bg-primary/20 border border-primary border-dashed' :
            isSelected ? 'bg-primary/10 border border-primary/30' : 'hover:bg-accent/50'
          } ${isLeaf ? 'cursor-pointer' : ''} ${depth > 0 ? 'ml-5' : ''}`}
          onClick={() => { if (isLeaf) { setSelectedKey(node.key); setRightTab('section'); } }}
          onDragOver={isLeaf ? (e) => handleDragOver(e, node.key, true) : undefined}
          onDragLeave={isLeaf ? handleDragLeave : undefined}
          onDrop={isLeaf ? (e) => handleDrop(e, node.key) : undefined}
        >
          {hasChildren ? (
            <button onClick={(e) => { e.stopPropagation(); toggleExpand(pathId); }} className="p-0.5">
              {isExpanded ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
            </button>
          ) : <span className="w-4.5" />}

          <span className="text-xs text-muted-foreground font-mono shrink-0 mr-1">{node.key}</span>
          <span className="text-sm flex-1 truncate">{node.title}</span>
          {isLeaf && count > 0 && (
            <Badge variant="secondary" className="text-xs">{count}개</Badge>
          )}
          {isDragOver && <Plus className="h-3.5 w-3.5 text-primary shrink-0" />}
        </div>

        {isExpanded && node.children.map(child => renderOutlineNode(child, depth + 1, pathId))}
      </div>
    );
  };

  const selectedReqs = selectedKey
    ? sortRequirements((mapping[selectedKey] || []).map(id => reqMap.get(id)).filter(Boolean) as Requirement[])
    : [];

  // 매핑되지 않은 요구사항
  const allMappedIds = new Set(Object.values(mapping).flat());
  const unmapped = sortRequirements(requirements.filter(r => !allMappedIds.has(r.id)));

  // 카테고리별 그룹 (각 그룹 내 ID순 정렬)
  const categorized = useMemo(() => {
    const groups = new Map<string, Requirement[]>();
    for (const req of requirements) {
      const cat = req.category || '기타';
      const list = groups.get(cat) || [];
      list.push(req);
      groups.set(cat, list);
    }
    // 각 그룹 내 정렬
    for (const [key, reqs] of groups) {
      groups.set(key, sortRequirements(reqs));
    }
    return groups;
  }, [requirements]);

  return (
    <div className="grid grid-cols-[1fr_1fr] gap-4" style={{ height: 'calc(100vh - 280px)', minHeight: '400px' }}>
      {/* 좌측: 목차 트리 */}
      <div className="border rounded-lg p-3 overflow-y-auto">
        <p className="text-xs text-muted-foreground mb-2 font-medium">목차 (클릭하여 요구사항 확인)</p>
        {outline.map(node => renderOutlineNode(node, 0, ''))}

        {unmapped.length > 0 && (
          <div className="mt-3 pt-3 border-t">
            <div
              className={`flex items-center gap-1.5 py-1.5 px-2 rounded-md cursor-pointer transition-colors ${
                selectedKey === '__unmapped' ? 'bg-destructive/10 border border-destructive/30' : 'hover:bg-accent/50'
              }`}
              onClick={() => { setSelectedKey('__unmapped'); setRightTab('section'); }}
            >
              <AlertCircle className="h-3.5 w-3.5 text-destructive" />
              <span className="text-sm text-destructive">미배치 요구사항</span>
              <Badge variant="destructive" className="text-xs ml-auto">{unmapped.length}개</Badge>
            </div>
            {!readOnly && onGenerateUnmapped && (
              <button
                onClick={async (e) => {
                  e.stopPropagation();
                  setGeneratingUnmapped(true);
                  try { await onGenerateUnmapped(); } finally { setGeneratingUnmapped(false); }
                }}
                disabled={generatingUnmapped}
                className="mt-1.5 w-full flex items-center justify-center gap-1.5 px-2 py-1.5 rounded-md text-xs font-medium bg-primary/10 text-primary hover:bg-primary/20 transition-colors disabled:opacity-50 cursor-pointer disabled:cursor-wait"
              >
                {generatingUnmapped
                  ? <><Loader2 className="h-3.5 w-3.5 animate-spin" />목차 생성 중...</>
                  : <><Wand2 className="h-3.5 w-3.5" />미배치 목차 자동 생성</>
                }
              </button>
            )}
          </div>
        )}
      </div>

      {/* 우측: 탭 전환 (섹션별 / 전체) */}
      <div className="border rounded-lg overflow-hidden flex flex-col">
        {/* 탭 헤더 */}
        <div className="flex border-b bg-muted/30 shrink-0">
          <button
            className={`flex-1 flex items-center justify-center gap-1.5 px-3 py-2 text-sm font-medium transition-colors ${
              rightTab === 'all' ? 'bg-background border-b-2 border-primary text-primary' : 'text-muted-foreground hover:text-foreground'
            }`}
            onClick={() => setRightTab('all')}
          >
            <List className="h-3.5 w-3.5" />
            {showUnmappedOnly ? `미배치 (${unmapped.length})` : `전체 요구사항 (${requirements.length})`}
          </button>
          {rightTab === 'all' && (
            <button
              onClick={() => setShowUnmappedOnly(v => !v)}
              className={`flex items-center gap-1 px-2 text-xs font-medium transition-colors border-b-2 ${
                showUnmappedOnly ? 'border-destructive text-destructive bg-destructive/5' : 'border-transparent text-muted-foreground hover:text-foreground'
              }`}
              title={showUnmappedOnly ? '전체 보기' : '미배치만 보기'}
            >
              <AlertCircle className="h-3.5 w-3.5" />
            </button>
          )}
          <button
            className={`flex-1 flex items-center justify-center gap-1.5 px-3 py-2 text-sm font-medium transition-colors ${
              rightTab === 'section' ? 'bg-background border-b-2 border-primary text-primary' : 'text-muted-foreground hover:text-foreground'
            }`}
            onClick={() => setRightTab('section')}
          >
            <FolderTree className="h-3.5 w-3.5" />
            섹션별 배치
          </button>
        </div>

        {/* 미배치 목차 자동 생성 버튼 */}
        {unmapped.length > 0 && !readOnly && onGenerateUnmapped && (
          <div className="px-3 pt-2 shrink-0">
            <button
              onClick={async () => {
                setGeneratingUnmapped(true);
                try { await onGenerateUnmapped(); } finally { setGeneratingUnmapped(false); }
              }}
              disabled={generatingUnmapped}
              className="w-full flex items-center justify-center gap-1.5 px-3 py-2 rounded-md text-sm font-medium bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50 cursor-pointer disabled:cursor-wait"
            >
              {generatingUnmapped
                ? <><Loader2 className="h-4 w-4 animate-spin" />미배치 목차 생성 중...</>
                : <><Wand2 className="h-4 w-4" />미배치 요구사항 목차 자동 생성 ({unmapped.length}건)</>
              }
            </button>
          </div>
        )}

        {/* 탭 내용 */}
        <div ref={rightTab === 'all' ? allReqScrollRef : undefined} className="flex-1 overflow-y-auto p-3">
          {rightTab === 'all' ? (
            /* ── 전체 요구사항 목록 (카테고리별, 필터 적용) ── */
            <div className="space-y-4">
              {Array.from(categorized.entries()).map(([category, reqs]) => {
                const filtered = showUnmappedOnly ? reqs.filter(r => !(reqToSections.get(r.id)?.length)) : reqs;
                if (filtered.length === 0) return null;
                return (
                <div key={category}>
                  <p className="text-xs font-semibold text-muted-foreground mb-1.5 uppercase tracking-wide">{category} ({filtered.length})</p>
                  <div className="space-y-1.5">
                    {filtered.map(req => {
                      const assignedTo = reqToSections.get(req.id) || [];
                      const isUnmapped = assignedTo.length === 0;
                      return (
                        <div
                          key={req.id}
                          draggable={!readOnly && !!onChange && isUnmapped}
                          onDragStart={!readOnly && onChange && isUnmapped ? (e) => handleDragStart(e, req.id) : undefined}
                          className={`p-2 rounded border text-sm ${isUnmapped ? 'border-destructive/30 bg-destructive/5' : 'bg-muted/20'} ${!readOnly && onChange && isUnmapped ? 'cursor-grab active:cursor-grabbing' : ''}`}
                        >
                          <div className="flex items-center gap-2 flex-wrap">
                            <span className="font-mono text-xs text-muted-foreground">{req.id}</span>
                            {importanceBadge(req.importance)}
                            <span className="font-medium flex-1">{req.item}</span>
                          </div>
                          <p className="text-xs text-muted-foreground mt-1">{req.description}</p>
                          {/* 배치 위치 */}
                          <div className="mt-1.5 flex items-center gap-1 flex-wrap">
                            {isUnmapped ? (
                              <span className="text-xs text-destructive flex items-center gap-0.5">
                                <AlertCircle className="h-3 w-3" />미배치
                              </span>
                            ) : (
                              assignedTo.map(sKey => (
                                <button
                                  key={sKey}
                                  className="text-xs px-1.5 py-0.5 rounded bg-primary/10 text-primary hover:bg-primary/20 transition-colors cursor-pointer"
                                  onClick={() => { setSelectedKey(sKey); setRightTab('section'); }}
                                  title={sectionTitles.get(sKey) || sKey}
                                >
                                  {sKey} {sectionTitles.get(sKey) || ''}
                                </button>
                              ))
                            )}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
                );
              })}
            </div>
          ) : (
            /* ── 섹션별 배치 (추가/제거 가능) ── */
            <SectionMappingPanel
              selectedKey={selectedKey}
              selectedReqs={selectedReqs}
              unmapped={unmapped}
              mapping={mapping}
              onChange={onChange}
              readOnly={readOnly}
            />
          )}
        </div>
      </div>
    </div>
  );
}
