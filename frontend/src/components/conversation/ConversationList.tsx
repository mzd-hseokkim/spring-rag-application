import { useState } from 'react';
import { MessageSquare, Trash2, Pencil, Check, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import type { Conversation } from '@/types';

interface Props {
  conversations: Conversation[];
  activeSessionId: string;
  onSelect: (conversation: Conversation) => void;
  onDelete: (id: string) => void;
  onRename: (id: string, title: string) => void;
}

export function ConversationList({ conversations, activeSessionId, onSelect, onDelete, onRename }: Props) {
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editTitle, setEditTitle] = useState('');

  const startEdit = (conv: Conversation) => {
    setEditingId(conv.id);
    setEditTitle(conv.title || '');
  };

  const confirmEdit = () => {
    if (editingId && editTitle.trim()) {
      onRename(editingId, editTitle.trim());
    }
    setEditingId(null);
  };

  const cancelEdit = () => {
    setEditingId(null);
  };

  const formatDate = (dateStr: string) => {
    const date = new Date(dateStr);
    const now = new Date();
    const diff = now.getTime() - date.getTime();
    const minutes = Math.floor(diff / 60000);
    const hours = Math.floor(diff / 3600000);
    const days = Math.floor(diff / 86400000);

    if (minutes < 1) return '방금 전';
    if (minutes < 60) return `${minutes}분 전`;
    if (hours < 24) return `${hours}시간 전`;
    if (days < 7) return `${days}일 전`;
    return date.toLocaleDateString('ko-KR', { month: 'short', day: 'numeric' });
  };

  if (conversations.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-8 text-muted-foreground text-sm">
        <MessageSquare className="h-8 w-8 mb-2 opacity-40" />
        <p>대화 이력이 없습니다</p>
        <p className="text-xs mt-1">새 대화를 시작해보세요</p>
      </div>
    );
  }

  return (
    <div className="space-y-1">
      {conversations.map(conv => {
        const isActive = conv.sessionId === activeSessionId;
        const isEditing = editingId === conv.id;

        return (
          <div
            key={conv.id}
            className={`group flex items-center gap-2 rounded-md px-2 py-2 cursor-pointer transition-colors ${
              isActive
                ? 'bg-sidebar-accent text-sidebar-accent-foreground'
                : 'hover:bg-sidebar-accent/50'
            }`}
            onClick={() => !isEditing && onSelect(conv)}
          >
            <MessageSquare className="h-4 w-4 shrink-0 opacity-50" />
            <div className="flex-1 min-w-0">
              {isEditing ? (
                <div className="flex items-center gap-1">
                  <Input
                    value={editTitle}
                    onChange={e => setEditTitle(e.target.value)}
                    onKeyDown={e => {
                      if (e.key === 'Enter') confirmEdit();
                      if (e.key === 'Escape') cancelEdit();
                    }}
                    className="h-6 text-xs"
                    autoFocus
                    onClick={e => e.stopPropagation()}
                  />
                  <Button variant="ghost" size="icon" className="h-5 w-5" onClick={(e) => { e.stopPropagation(); confirmEdit(); }}>
                    <Check className="h-3 w-3" />
                  </Button>
                  <Button variant="ghost" size="icon" className="h-5 w-5" onClick={(e) => { e.stopPropagation(); cancelEdit(); }}>
                    <X className="h-3 w-3" />
                  </Button>
                </div>
              ) : (
                <>
                  <p className="text-sm truncate">{conv.title || '새 대화'}</p>
                  <div className="flex items-center gap-1 text-xs text-muted-foreground">
                    <span>{formatDate(conv.updatedAt)}</span>
                    {conv.modelName && (
                      <>
                        <span>·</span>
                        <span className="truncate">{conv.modelName}</span>
                      </>
                    )}
                  </div>
                </>
              )}
            </div>
            {!isEditing && (
              <div className="flex shrink-0 opacity-0 group-hover:opacity-100 transition-opacity">
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-6 w-6"
                  onClick={(e) => { e.stopPropagation(); startEdit(conv); }}
                >
                  <Pencil className="h-3 w-3" />
                </Button>
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-6 w-6 text-destructive hover:text-destructive"
                  onClick={(e) => { e.stopPropagation(); onDelete(conv.id); }}
                >
                  <Trash2 className="h-3 w-3" />
                </Button>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
