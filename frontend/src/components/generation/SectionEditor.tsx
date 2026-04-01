import { useState, useMemo, useRef, useEffect } from 'react';
import { Textarea } from '@/components/ui/textarea';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { CheckCircle2, Loader2, Circle, FileText, RefreshCw, ChevronRight, ChevronDown, X, ChevronsUpDown, ChevronsDownUp } from 'lucide-react';
import { LinkifySource } from '@/components/ui/linkify-source';
import type { OutlineNode } from '@/api/generation';

interface SectionData {
  key: string;
  title: string;
  content: string;
  highlights: string[];
  tables: Array<{ caption: string; headers: string[]; rows: string[][] }>;
  references: string[];
  layoutType?: string;
  governingMessage?: string;
  visualGuide?: string;
  sources?: string[];
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

/** 리프 key 목록을 flat하게 수집 */
function collectLeafKeys(nodes: OutlineNode[]): string[] {
  return nodes.flatMap(n => n.children.length > 0 ? collectLeafKeys(n.children) : [n.key]);
}

/** non-leaf(children이 있는) 노드의 pathId 목록을 flat하게 수집 */
function collectBranchPathIds(nodes: OutlineNode[], parentPath = ''): string[] {
  return nodes.flatMap(n => {
    const pathId = parentPath ? `${parentPath}/${n.key}` : n.key;
    return n.children.length > 0 ? [pathId, ...collectBranchPathIds(n.children, pathId)] : [];
  });
}

/** 리프 key의 순서 인덱스 맵 */
function buildLeafIndexMap(nodes: OutlineNode[]): Map<string, number> {
  const keys = collectLeafKeys(nodes);
  return new Map(keys.map((k, i) => [k, i]));
}

interface SectionEditorProps {
  sections: SectionData[];
  outline?: OutlineNode[];
  sectionTitles?: string[];
  sectionKeys?: string[];
  generatingIndex: number;
  totalSections: number;
  generatingKey?: string | null;
  regeneratingKey?: string | null;
  checkedKeys?: Set<string>;
  onCheckedChange?: (keys: Set<string>) => void;
  onSectionChange: (index: number, updated: SectionData) => void;
  onRegenerate?: (sectionKey: string, userInstruction?: string) => void;
}

export function SectionEditor({ sections, outline: rawOutline, sectionTitles, sectionKeys, generatingIndex, generatingKey, totalSections, regeneratingKey, checkedKeys, onCheckedChange, onSectionChange, onRegenerate }: SectionEditorProps) {
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [showRegenInput, setShowRegenInput] = useState(false);
  const [regenInstruction, setRegenInstruction] = useState('');
  const [expanded, setExpanded] = useState<Set<string>>(() => {
    if (rawOutline) return new Set(rawOutline.map(n => n.key));  // top-level keys are unique
    return new Set<string>();
  });

  const outline = useMemo(() => rawOutline ? sortOutline(rawOutline) : null, [rawOutline]);
  const leafIndexMap = useMemo(() => outline ? buildLeafIndexMap(outline) : null, [outline]);

  // key 기반으로 생성된 섹션을 맵으로 관리
  const sectionMap = useMemo(() => {
    const map = new Map<string, { data: SectionData; index: number }>();
    sections.forEach((sec, i) => map.set(sec.key, { data: sec, index: i }));

    if (sectionKeys && sectionKeys.length > 0 && sections.length > 0) {
      const hasMatch = sectionKeys.some(k => map.has(k));
      if (!hasMatch) {
        map.clear();
        sections.forEach((sec, i) => {
          const slotKey = sectionKeys[i] || sec.key;
          map.set(slotKey, { data: sec, index: i });
        });
      }
    }
    return map;
  }, [sections, sectionKeys]);

  const toggleExpand = (pathId: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      next.has(pathId) ? next.delete(pathId) : next.add(pathId);
      return next;
    });
  };

  const collectAllPaths = (nodes: OutlineNode[], parentPath = ''): string[] => {
    const paths: string[] = [];
    for (const n of nodes) {
      const pathId = parentPath ? `${parentPath}/${n.key}` : n.key;
      if (n.children.length > 0) {
        paths.push(pathId);
        paths.push(...collectAllPaths(n.children, pathId));
      }
    }
    return paths;
  };
  const expandAll = () => { if (outline) setExpanded(new Set(collectAllPaths(outline))); };
  const collapseAll = () => setExpanded(new Set());

  const leafCount = outline ? collectLeafKeys(outline).length : (sectionKeys?.length || totalSections);

  const contentRef = useRef<HTMLDivElement>(null);
  const selectedEntry = selectedKey ? sectionMap.get(selectedKey) : null;
  const selected = selectedEntry?.data ?? null;
  const selectedIndex = selectedEntry?.index ?? -1;

  useEffect(() => {
    contentRef.current?.scrollTo(0, 0);
  }, [selectedKey]);

  /** 리프가 현재 생성 중인지 판별 (key 우선, index 폴백) */
  const isLeafGenerating = (nodeKey: string): boolean => {
    if (generatingKey != null) return nodeKey === generatingKey;
    if (generatingIndex >= 0) {
      const idx = leafIndexMap?.get(nodeKey);
      return idx !== undefined && idx === generatingIndex;
    }
    return false;
  };

  /** 하위 리프 중 현재 생성 중인 것이 있는지 확인 */
  const hasGeneratingChild = (node: OutlineNode): boolean => {
    if (node.children.length === 0) return isLeafGenerating(node.key);
    return node.children.some(hasGeneratingChild);
  };

  /** 하위 리프의 완료/전체 수 */
  const getChildProgress = (node: OutlineNode): { done: number; total: number } => {
    const leaves = collectLeafKeys([node]);
    const done = leaves.filter(k => sectionMap.has(k)).length;
    return { done, total: leaves.length };
  };

  // 계층형 목차 렌더링
  const renderOutlineNode = (node: OutlineNode, depth: number, parentPath: string) => {
    const pathId = parentPath ? `${parentPath}/${node.key}` : node.key;
    const isLeaf = node.children.length === 0;
    const isExpanded = expanded.has(pathId);
    const sec = sectionMap.get(node.key);
    const isComplete = !!sec;
    const isGenerating = isLeafGenerating(node.key);
    const isSelected = selectedKey === node.key;
    const isRegenerating = regeneratingKey === node.key;

    // 비리프 노드: 하위에 생성 중인 항목이 있는지
    const childGenerating = !isLeaf && hasGeneratingChild(node);
    const childProgress = !isLeaf ? getChildProgress(node) : null;

    return (
      <div key={pathId}>
        <div
          className={`flex items-center gap-1.5 py-1 px-2 rounded text-sm transition-colors ${
            isLeaf ? 'cursor-pointer' : ''
          } ${
            isSelected && isComplete ? 'bg-primary/10 border border-primary/30' :
            isLeaf && isComplete ? 'hover:bg-accent/50' :
            isLeaf ? 'opacity-50' : ''
          }`}
          style={{ paddingLeft: `${depth * 16 + 8}px` }}
          onClick={() => isLeaf && isComplete && setSelectedKey(node.key)}
        >
          {!isLeaf ? (
            <>
              {onCheckedChange && checkedKeys && (
                <input
                  type="checkbox"
                  checked={checkedKeys.has(pathId)}
                  onChange={(e) => {
                    e.stopPropagation();
                    const next = new Set(checkedKeys);
                    next.has(pathId) ? next.delete(pathId) : next.add(pathId);
                    onCheckedChange(next);
                  }}
                  className="h-3.5 w-3.5 shrink-0 cursor-pointer accent-primary"
                />
              )}
              <button onClick={(e) => { e.stopPropagation(); toggleExpand(pathId); }} className="p-0.5 shrink-0">
                {isExpanded ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
              </button>
              {childGenerating && <Loader2 className="h-3 w-3 animate-spin text-primary shrink-0" />}
            </>
          ) : (
            <>
              {isRegenerating ? <Loader2 className="h-3.5 w-3.5 animate-spin text-orange-500 shrink-0" /> :
                isComplete ? <CheckCircle2 className="h-3.5 w-3.5 text-green-500 shrink-0" /> :
                isGenerating ? <Loader2 className="h-3.5 w-3.5 animate-spin text-primary shrink-0" /> :
                <Circle className="h-3.5 w-3.5 text-muted-foreground shrink-0" />}
            </>
          )}
          <span className={`text-xs font-mono shrink-0 mr-1 ${!isLeaf ? 'text-muted-foreground font-semibold' : 'text-muted-foreground'}`}>{node.key}</span>
          <span className={`truncate ${!isLeaf ? 'font-semibold text-foreground' : ''}`}>
            {sec?.data.title || node.title}
          </span>
          {!isLeaf && childProgress && (
            <span className={`text-xs shrink-0 ml-auto ${childProgress.done === childProgress.total ? 'text-green-600' : 'text-muted-foreground'}`}>
              {childProgress.done}/{childProgress.total}
            </span>
          )}
        </div>
        {!isLeaf && isExpanded && node.children.map(child => renderOutlineNode(child, depth + 1, pathId))}
      </div>
    );
  };

  // flat fallback (outline이 없을 때)
  const slots = useMemo(() => {
    if (outline) return null;
    if (sectionKeys && sectionKeys.length > 0) {
      return sectionKeys.map((key, i) => ({
        key,
        title: sectionTitles?.[i] || `섹션 ${i + 1}`,
      }));
    }
    return Array.from({ length: totalSections }, (_, i) => ({
      key: sections[i]?.key || `__slot_${i}`,
      title: sectionTitles?.[i] || sections[i]?.title || `섹션 ${i + 1}`,
    }));
  }, [outline, sectionKeys, sectionTitles, totalSections, sections]);

  return (
    <div className="grid grid-cols-[340px_1fr] gap-4" style={{ height: 'calc(100vh - 280px)', minHeight: '400px' }}>
      {/* 좌측: 목차 계층 목록 */}
      <div className="border rounded-lg p-2 overflow-y-auto space-y-0.5">
        <div className="flex items-center gap-2 px-2 py-1">
          {onCheckedChange && checkedKeys && outline && (() => {
            const allBranchPathIds = collectBranchPathIds(outline);
            const allChecked = allBranchPathIds.length > 0 && allBranchPathIds.every(k => checkedKeys.has(k));
            const someChecked = checkedKeys.size > 0;
            return (
              <input
                type="checkbox"
                checked={allChecked}
                ref={el => { if (el) el.indeterminate = someChecked && !allChecked; }}
                onChange={() => {
                  onCheckedChange(allChecked ? new Set() : new Set(allBranchPathIds));
                }}
                className="h-3.5 w-3.5 cursor-pointer accent-primary"
              />
            );
          })()}
          <span className="text-xs text-muted-foreground font-medium">
            목차 ({sections.length}/{leafCount})
          </span>
          {outline && (
            <div className="flex items-center gap-0.5 ml-auto">
              <button onClick={expandAll} className="p-1 rounded text-muted-foreground hover:bg-accent/50 cursor-pointer" title="모두 열기">
                <ChevronsUpDown className="h-3 w-3" />
              </button>
              <button onClick={collapseAll} className="p-1 rounded text-muted-foreground hover:bg-accent/50 cursor-pointer" title="모두 닫기">
                <ChevronsDownUp className="h-3 w-3" />
              </button>
            </div>
          )}
        </div>
        {outline ? (
          outline.map(node => renderOutlineNode(node, 0, ''))
        ) : (
          slots?.map((slot) => {
            const sec = sectionMap.get(slot.key);
            const isComplete = !!sec;
            const isGenerating = isLeafGenerating(slot.key);
            const isSelected = selectedKey === slot.key;
            const isRegenerating = regeneratingKey === slot.key;
            return (
              <button
                key={slot.key}
                onClick={() => isComplete && setSelectedKey(slot.key)}
                disabled={!isComplete}
                className={`w-full flex items-center gap-2 px-2 py-1.5 rounded text-left text-sm transition-colors cursor-pointer ${
                  isSelected && isComplete ? 'bg-primary/10 border border-primary/30' :
                  isComplete ? 'hover:bg-accent/50' : 'opacity-50'
                }`}
              >
                {isRegenerating ? <Loader2 className="h-3.5 w-3.5 animate-spin text-orange-500 shrink-0" /> :
                  isComplete ? <CheckCircle2 className="h-3.5 w-3.5 text-green-500 shrink-0" /> :
                  isGenerating ? <Loader2 className="h-3.5 w-3.5 animate-spin text-primary shrink-0" /> :
                  <Circle className="h-3.5 w-3.5 text-muted-foreground shrink-0" />}
                <span className="truncate">{sec?.data.title || slot.title}</span>
              </button>
            );
          })
        )}
      </div>

      {/* 우측: 선택된 섹션 편집 */}
      <div ref={contentRef} className="border rounded-lg p-4 overflow-y-auto">
        {!selected ? (
          <div className="flex items-center justify-center h-full text-muted-foreground text-sm">
            {generatingKey != null ? (
              <div className="flex items-center gap-2">
                <Loader2 className="h-4 w-4 animate-spin" />
                섹션을 생성하고 있습니다... ({sections.length + 1}/{leafCount})
              </div>
            ) : generatingIndex >= 0 ? (
              <div className="flex items-center gap-2">
                <Loader2 className="h-4 w-4 animate-spin" />
                섹션을 생성하고 있습니다...
              </div>
            ) : '좌측에서 섹션을 선택하세요.'}
          </div>
        ) : (
          <div className="space-y-4">
            <div className="flex items-center justify-between">
              <div>
                <h3 className="text-lg font-semibold">{selected.title}</h3>
                {selected.governingMessage && (
                  <p className="text-sm text-primary font-medium mt-1 bg-accent/10 px-3 py-1.5 rounded">{selected.governingMessage}</p>
                )}
                {selected.layoutType && selected.layoutType !== 'TEXT_FULL' && (
                  <p className="text-xs text-muted-foreground mt-1">레이아웃: {selected.layoutType}</p>
                )}
              </div>
              {onRegenerate && (
                regeneratingKey === selected.key ? (
                  <Button variant="outline" size="sm" disabled className="shrink-0">
                    <Loader2 className="h-3.5 w-3.5 mr-1 animate-spin" />재생성 중...
                  </Button>
                ) : (
                  <Button
                    variant="outline" size="sm"
                    onClick={() => { setShowRegenInput(v => !v); setRegenInstruction(''); }}
                    className="shrink-0"
                  >
                    <RefreshCw className="h-3.5 w-3.5 mr-1" />이 섹션 재생성
                  </Button>
                )
              )}
            </div>

            {/* 재생성 지침 입력 */}
            {showRegenInput && onRegenerate && regeneratingKey !== selected.key && (
              <div className="border rounded-lg p-3 bg-muted/30 space-y-2">
                <div className="flex items-center justify-between">
                  <label className="text-sm font-medium">재생성 지침 (선택사항)</label>
                  <Button variant="ghost" size="sm" className="h-6 w-6 p-0" onClick={() => setShowRegenInput(false)}>
                    <X className="h-3.5 w-3.5" />
                  </Button>
                </div>
                <Textarea
                  value={regenInstruction}
                  onChange={e => setRegenInstruction(e.target.value)}
                  placeholder="예: 더 구체적인 수치를 포함해주세요 / 표 형식으로 정리해주세요 / 기술적 내용을 줄이고 비즈니스 관점으로 작성해주세요"
                  rows={2}
                  className="text-sm resize-none"
                />
                <div className="flex gap-2 justify-end">
                  <Button
                    variant="ghost" size="sm"
                    onClick={() => { setShowRegenInput(false); onRegenerate(selected.key); }}
                  >
                    지침 없이 재생성
                  </Button>
                  <Button
                    size="sm"
                    onClick={() => { setShowRegenInput(false); onRegenerate(selected.key, regenInstruction || undefined); }}
                  >
                    <RefreshCw className="h-3.5 w-3.5 mr-1" />재생성
                  </Button>
                </div>
              </div>
            )}

            {/* 하이라이트 */}
            {selected.highlights.length > 0 && (
              <div className="flex flex-wrap gap-1.5">
                {selected.highlights.map((h, i) => (
                  <Badge key={i} variant="outline" className="text-xs">{h}</Badge>
                ))}
              </div>
            )}

            {/* 본문 편집 */}
            <div className="space-y-1">
              <label className="text-sm font-medium">본문 (마크다운)</label>
              <Textarea
                value={selected.content}
                onChange={e => onSectionChange(selectedIndex, { ...selected, content: e.target.value })}
                rows={15}
                className="font-mono text-sm resize-y"
              />
            </div>

            {/* 표 */}
            {selected.tables.length > 0 && (
              <div className="space-y-2">
                <label className="text-sm font-medium">표 ({selected.tables.length}개)</label>
                {selected.tables.map((table, ti) => (
                  <div key={ti} className="border rounded p-2 text-xs overflow-x-auto">
                    <p className="font-medium mb-1">{table.caption}</p>
                    <table className="w-full">
                      <thead>
                        <tr className="border-b">
                          {table.headers.map((h, hi) => <th key={hi} className="px-2 py-1 text-left">{h}</th>)}
                        </tr>
                      </thead>
                      <tbody>
                        {table.rows.map((row, ri) => (
                          <tr key={ri} className="border-b last:border-0">
                            {row.map((cell, ci) => <td key={ci} className="px-2 py-1">{cell}</td>)}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ))}
              </div>
            )}

            {/* 시각 요소 가이드 */}
            {selected.visualGuide && (
              <div className="p-3 rounded border border-dashed border-yellow-400 bg-yellow-50 text-sm text-yellow-800">
                <span className="font-semibold mr-1">시각 요소 가이드:</span>
                {selected.visualGuide}
              </div>
            )}

            {/* 사용된 출처 */}
            {selected.sources && selected.sources.length > 0 && (
              <div className="space-y-1">
                <label className="text-sm font-medium">사용된 출처</label>
                <div className="border rounded p-2 bg-muted/20 space-y-0.5">
                  {selected.sources.map((s, i) => (
                    <p key={i} className="text-xs text-muted-foreground flex items-start gap-1">
                      <span className="shrink-0">{s.startsWith('[웹]') ? '🌐' : '📄'}</span>
                      <LinkifySource text={s} />
                    </p>
                  ))}
                </div>
              </div>
            )}

            {/* 참고자료 (LLM 생성) */}
            {selected.references.length > 0 && (
              <div className="space-y-1">
                <label className="text-sm font-medium">참고자료</label>
                <ul className="text-xs text-muted-foreground space-y-0.5">
                  {selected.references.map((r, i) => (
                    <li key={i} className="flex items-center gap-1"><FileText className="h-3 w-3" /><LinkifySource text={r} /></li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
