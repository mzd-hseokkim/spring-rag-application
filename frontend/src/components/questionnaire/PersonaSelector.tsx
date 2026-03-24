import { useState, useEffect } from 'react';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Badge } from '@/components/ui/badge';
import { Plus, Trash2, X, Users, Loader2, Pencil, RefreshCw, Check } from 'lucide-react';
import type { Persona, PersonaRequest } from '@/api/persona';

interface Props {
  personas: Persona[];
  selectedIds: string[];
  onToggle: (id: string) => void;
  onCreate: (request: PersonaRequest) => Promise<void>;
  onUpdate: (id: string, request: PersonaRequest) => Promise<void>;
  onRegeneratePrompt: (id: string) => Promise<void>;
  onDelete: (id: string) => Promise<void>;
}

export function PersonaSelector({ personas, selectedIds, onToggle, onCreate, onUpdate, onRegeneratePrompt, onDelete }: Props) {
  const [showForm, setShowForm] = useState(false);
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [role, setRole] = useState('');
  const [focusAreas, setFocusAreas] = useState('');

  // 편집 상태
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editPrompt, setEditPrompt] = useState('');
  const [editFocusAreas, setEditFocusAreas] = useState('');
  const [saving, setSaving] = useState(false);
  const [regenerating, setRegenerating] = useState<string | null>(null);

  const handleCreate = async () => {
    if (!name.trim() || !role.trim()) return;
    setCreating(true);
    try {
      await onCreate({ name: name.trim(), role: role.trim(), focusAreas: focusAreas.trim() || undefined });
      setName('');
      setRole('');
      setFocusAreas('');
      setShowForm(false);
    } finally {
      setCreating(false);
    }
  };

  const handleEdit = (persona: Persona) => {
    if (editingId === persona.id) {
      setEditingId(null);
      return;
    }
    setEditingId(persona.id);
    setEditPrompt(persona.prompt || '');
    setEditFocusAreas(persona.focusAreas || '');
  };

  const handleSaveEdit = async (persona: Persona) => {
    setSaving(true);
    try {
      await onUpdate(persona.id, {
        name: persona.name,
        role: persona.role,
        focusAreas: editFocusAreas.trim() || undefined,
        prompt: editPrompt.trim() || undefined,
      });
      setEditingId(null);
    } finally {
      setSaving(false);
    }
  };

  // personas prop이 변경되면 (재생성/업데이트 후) 편집 중인 값을 동기화
  useEffect(() => {
    if (editingId) {
      const p = personas.find(pp => pp.id === editingId);
      if (p) {
        setEditPrompt(p.prompt || '');
        setEditFocusAreas(p.focusAreas || '');
      }
    }
  }, [personas, editingId]);

  const handleRegenerate = async (id: string) => {
    setRegenerating(id);
    try {
      await onRegeneratePrompt(id);
    } finally {
      setRegenerating(null);
    }
  };

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <label className="text-sm font-medium flex items-center gap-1.5">
          <Users className="h-4 w-4" />
          페르소나 선택
        </label>
        <Button variant="outline" size="sm" className="h-7 text-xs" onClick={() => setShowForm(!showForm)}>
          <Plus className="h-3.5 w-3.5 mr-1" />
          추가
        </Button>
      </div>

      {selectedIds.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {selectedIds.map(id => {
            const p = personas.find(pp => pp.id === id);
            if (!p) return null;
            return (
              <Badge key={id} variant="secondary" className="gap-1 pr-1">
                {p.name}
                <button onClick={() => onToggle(id)} className="ml-0.5 rounded-full p-0.5 hover:bg-foreground/10 cursor-pointer">
                  <X className="h-3 w-3" />
                </button>
              </Badge>
            );
          })}
        </div>
      )}

      <div className="border rounded-lg p-3 space-y-1 max-h-72 overflow-y-auto bg-muted/30">
        {personas.length === 0 ? (
          <p className="text-xs text-muted-foreground py-2 text-center">페르소나가 없습니다.</p>
        ) : (
          personas.map(persona => (
            <div key={persona.id}>
              <div className="flex items-center gap-2 py-1.5 px-2 rounded-md hover:bg-accent/50">
                <Checkbox
                  checked={selectedIds.includes(persona.id)}
                  onCheckedChange={() => onToggle(persona.id)}
                />
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-1.5">
                    <span className="text-sm font-medium truncate">{persona.name}</span>
                    {persona.isDefault && <Badge variant="outline" className="text-[10px] px-1 py-0">기본</Badge>}
                    {persona.prompt ? (
                      <Badge variant="secondary" className="text-[10px] px-1 py-0 bg-green-100 text-green-700">프롬프트</Badge>
                    ) : (
                      <Badge variant="secondary" className="text-[10px] px-1 py-0 bg-orange-100 text-orange-700">미설정</Badge>
                    )}
                  </div>
                  <p className="text-xs text-muted-foreground truncate">{persona.role}</p>
                </div>
                <Button variant="ghost" size="icon" className="h-6 w-6 shrink-0" onClick={() => handleEdit(persona)} title="프롬프트 보기/편집">
                  <Pencil className="h-3 w-3 text-muted-foreground" />
                </Button>
                {!persona.isDefault && (
                  <Button variant="ghost" size="icon" className="h-6 w-6 shrink-0" onClick={() => onDelete(persona.id)}>
                    <Trash2 className="h-3 w-3 text-muted-foreground" />
                  </Button>
                )}
              </div>

              {/* 프롬프트 편집 패널 */}
              {editingId === persona.id && (
                <div className="ml-8 mr-2 mt-1 mb-2 p-3 border rounded-lg bg-background space-y-2">
                  <div>
                    <label className="text-xs text-muted-foreground">관심 분야</label>
                    <Input
                      value={editFocusAreas}
                      onChange={e => setEditFocusAreas(e.target.value)}
                      className="h-7 text-xs"
                      disabled={saving}
                    />
                  </div>
                  <div>
                    <label className="text-xs text-muted-foreground">전용 프롬프트</label>
                    <Textarea
                      value={editPrompt}
                      onChange={e => setEditPrompt(e.target.value)}
                      rows={4}
                      className="text-xs"
                      placeholder="이 페르소나가 질문을 생성할 때 사용하는 시스템 프롬프트입니다."
                      disabled={saving}
                    />
                  </div>
                  <div className="flex justify-between">
                    <Button
                      variant="outline"
                      size="sm"
                      className="h-7 text-xs"
                      onClick={() => handleRegenerate(persona.id)}
                      disabled={regenerating === persona.id || saving}
                    >
                      {regenerating === persona.id ? <Loader2 className="h-3 w-3 mr-1 animate-spin" /> : <RefreshCw className="h-3 w-3 mr-1" />}
                      AI 재생성
                    </Button>
                    <div className="flex gap-1.5">
                      <Button variant="ghost" size="sm" className="h-7 text-xs" onClick={() => setEditingId(null)} disabled={saving}>
                        취소
                      </Button>
                      <Button size="sm" className="h-7 text-xs" onClick={() => handleSaveEdit(persona)} disabled={saving}>
                        {saving ? <Loader2 className="h-3 w-3 mr-1 animate-spin" /> : <Check className="h-3 w-3 mr-1" />}
                        저장
                      </Button>
                    </div>
                  </div>
                </div>
              )}
            </div>
          ))
        )}
      </div>

      {/* 새 페르소나 추가 폼 */}
      {showForm && (
        <div className="border rounded-lg p-4 space-y-3 bg-muted/20">
          <p className="text-sm font-medium">새 페르소나 추가</p>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="text-xs text-muted-foreground">이름 *</label>
              <Input value={name} onChange={e => setName(e.target.value)} placeholder="예: 보안평가위원" className="h-8 text-sm" disabled={creating} />
            </div>
            <div>
              <label className="text-xs text-muted-foreground">역할 *</label>
              <Input value={role} onChange={e => setRole(e.target.value)} placeholder="예: 보안 취약점을 평가하는 위원" className="h-8 text-sm" disabled={creating} />
            </div>
          </div>
          <div>
            <label className="text-xs text-muted-foreground">관심 분야</label>
            <Input value={focusAreas} onChange={e => setFocusAreas(e.target.value)} placeholder="예: 정보보안, 개인정보보호, 접근제어" className="h-8 text-sm" disabled={creating} />
          </div>
          <p className="text-xs text-muted-foreground">
            이름, 역할, 관심 분야를 입력하면 AI가 페르소나 전용 프롬프트를 자동 생성합니다.
          </p>
          <div className="flex justify-end gap-2">
            <Button variant="ghost" size="sm" onClick={() => setShowForm(false)} disabled={creating}>취소</Button>
            <Button size="sm" onClick={handleCreate} disabled={!name.trim() || !role.trim() || creating}>
              {creating && <Loader2 className="h-3.5 w-3.5 mr-1 animate-spin" />}
              {creating ? '생성 중...' : '추가'}
            </Button>
          </div>
        </div>
      )}

      <p className="text-xs text-muted-foreground">
        {selectedIds.length}개 페르소나 선택됨 — 각 페르소나 관점에서 예상 질문을 생성합니다.
      </p>
    </div>
  );
}
