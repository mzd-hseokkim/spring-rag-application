import { useState } from 'react';
import { Textarea } from '@/components/ui/textarea';
import { Badge } from '@/components/ui/badge';
import { CheckCircle2, Loader2, Circle, FileText } from 'lucide-react';

interface SectionData {
  key: string;
  title: string;
  content: string;
  highlights: string[];
  tables: Array<{ caption: string; headers: string[]; rows: string[][] }>;
  references: string[];
  layoutHint?: string;
}

interface SectionEditorProps {
  sections: SectionData[];
  generatingIndex: number; // -1 = all done, 0-based index of currently generating
  totalSections: number;
  onSectionChange: (index: number, updated: SectionData) => void;
}

export function SectionEditor({ sections, generatingIndex, totalSections, onSectionChange }: SectionEditorProps) {
  const [selectedIndex, setSelectedIndex] = useState(0);

  const selected = sections[selectedIndex];

  return (
    <div className="grid grid-cols-[240px_1fr] gap-4 min-h-[500px]">
      {/* 좌측: 섹션 목록 */}
      <div className="border rounded-lg p-2 overflow-y-auto space-y-0.5">
        <p className="text-xs text-muted-foreground px-2 py-1 font-medium">섹션 목록</p>
        {Array.from({ length: totalSections }, (_, i) => {
          const sec = sections[i];
          const isComplete = !!sec;
          const isGenerating = i === generatingIndex;
          const isPending = !isComplete && !isGenerating;

          return (
            <button
              key={i}
              onClick={() => isComplete && setSelectedIndex(i)}
              disabled={!isComplete}
              className={`w-full flex items-center gap-2 px-2 py-1.5 rounded text-left text-sm transition-colors cursor-pointer ${
                selectedIndex === i && isComplete ? 'bg-primary/10 border border-primary/30' :
                isComplete ? 'hover:bg-accent/50' : 'opacity-50'
              }`}
            >
              {isComplete ? <CheckCircle2 className="h-3.5 w-3.5 text-green-500 shrink-0" /> :
                isGenerating ? <Loader2 className="h-3.5 w-3.5 animate-spin text-primary shrink-0" /> :
                <Circle className="h-3.5 w-3.5 text-muted-foreground shrink-0" />}
              <span className="truncate">{sec?.title || `섹션 ${i + 1}`}</span>
            </button>
          );
        })}
      </div>

      {/* 우측: 선택된 섹션 편집 */}
      <div className="border rounded-lg p-4 overflow-y-auto">
        {!selected ? (
          <div className="flex items-center justify-center h-full text-muted-foreground text-sm">
            {generatingIndex >= 0 ? (
              <div className="flex items-center gap-2">
                <Loader2 className="h-4 w-4 animate-spin" />
                섹션을 생성하고 있습니다... ({generatingIndex + 1}/{totalSections})
              </div>
            ) : '좌측에서 섹션을 선택하세요.'}
          </div>
        ) : (
          <div className="space-y-4">
            <div>
              <h3 className="text-lg font-semibold">{selected.title}</h3>
              {selected.layoutHint && (
                <p className="text-xs text-muted-foreground mt-1 italic">배치 권장: {selected.layoutHint}</p>
              )}
            </div>

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

            {/* 참고자료 */}
            {selected.references.length > 0 && (
              <div className="space-y-1">
                <label className="text-sm font-medium">참고자료</label>
                <ul className="text-xs text-muted-foreground space-y-0.5">
                  {selected.references.map((r, i) => (
                    <li key={i} className="flex items-center gap-1"><FileText className="h-3 w-3" />{r}</li>
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
