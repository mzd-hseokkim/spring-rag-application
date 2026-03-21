import { useState, useEffect, useCallback } from 'react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Trash2, Eye } from 'lucide-react';
import { fetchAdminConversations, fetchAdminConversationDetail, deleteAdminConversation } from '@/api/admin';

interface AdminConversation {
  id: string;
  title: string | null;
  ownerEmail: string | null;
  modelName: string | null;
  messageCount: number;
  updatedAt: string;
}

interface PageData {
  content: AdminConversation[];
  totalPages: number;
  number: number;
}

interface ConversationDetail {
  conversation: AdminConversation;
  messages: { role: string; content: string; timestamp: string }[];
}

export function AdminConversationsPage() {
  const [data, setData] = useState<PageData | null>(null);
  const [page, setPage] = useState(0);
  const [detail, setDetail] = useState<ConversationDetail | null>(null);

  const load = useCallback(async () => {
    try {
      const res = await fetchAdminConversations(page, 20);
      setData(res);
    } catch (err) {
      console.error('Failed to load conversations:', err);
      toast.error('대화 목록 조회 실패');
    }
  }, [page]);

  useEffect(() => { load(); }, [load]);

  const handleView = async (id: string) => {
    if (detail?.conversation.id === id) {
      setDetail(null);
      return;
    }
    try {
      const res = await fetchAdminConversationDetail(id);
      setDetail(res);
    } catch (err) {
      console.error(err);
      toast.error('대화 상세 조회 실패');
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm('이 대화를 삭제하시겠습니까?')) return;
    try {
      await deleteAdminConversation(id);
      if (detail?.conversation.id === id) setDetail(null);
      await load();
      toast.success('대화가 삭제되었습니다.');
    } catch (err) {
      console.error(err);
      toast.error('대화 삭제 실패');
    }
  };

  const formatDate = (dateStr: string) => {
    const d = new Date(dateStr);
    return d.toLocaleDateString('ko-KR') + ' ' + d.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' });
  };

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">대화 관리</h1>

      <div className="border rounded-lg">
        <table className="w-full text-sm">
          <thead className="bg-muted/50">
            <tr>
              <th className="text-left px-4 py-3 font-medium">제목</th>
              <th className="text-left px-4 py-3 font-medium">사용자</th>
              <th className="text-left px-4 py-3 font-medium">모델</th>
              <th className="text-left px-4 py-3 font-medium">최근 활동</th>
              <th className="px-4 py-3 w-24"></th>
            </tr>
          </thead>
          <tbody>
            {data?.content.map(conv => (
              <tr key={conv.id} className={`border-t hover:bg-muted/30 ${detail?.conversation.id === conv.id ? 'bg-muted/20' : ''}`}>
                <td className="px-4 py-3 max-w-60 truncate">{conv.title || '(제목 없음)'}</td>
                <td className="px-4 py-3 text-muted-foreground">{conv.ownerEmail || '-'}</td>
                <td className="px-4 py-3 text-muted-foreground text-xs">{conv.modelName || '-'}</td>
                <td className="px-4 py-3 text-muted-foreground text-xs">{formatDate(conv.updatedAt)}</td>
                <td className="px-4 py-3 flex gap-1">
                  <Button variant="ghost" size="icon" className="h-8 w-8"
                    onClick={() => handleView(conv.id)}>
                    <Eye className="h-4 w-4" />
                  </Button>
                  <Button variant="ghost" size="icon" className="h-8 w-8 text-destructive"
                    onClick={() => handleDelete(conv.id)}>
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {detail && (
        <div className="border rounded-lg p-4 space-y-3">
          <h3 className="font-medium">{detail.conversation.title || '(제목 없음)'}</h3>
          <div className="space-y-2 max-h-96 overflow-y-auto">
            {detail.messages.length === 0 ? (
              <p className="text-sm text-muted-foreground">메시지 없음</p>
            ) : detail.messages.map((msg, i) => (
              <div key={i} className={`text-sm p-2 rounded ${msg.role === 'user' ? 'bg-muted' : 'bg-secondary/50'}`}>
                <span className="font-medium text-xs text-muted-foreground">
                  {msg.role === 'user' ? '사용자' : 'AI'}
                </span>
                <p className="mt-0.5 whitespace-pre-wrap">{msg.content.length > 500 ? msg.content.slice(0, 500) + '...' : msg.content}</p>
              </div>
            ))}
          </div>
        </div>
      )}

      {data && data.totalPages > 1 && (
        <div className="flex justify-center gap-2">
          <Button variant="outline" size="sm" disabled={page === 0}
            onClick={() => setPage(p => p - 1)}>이전</Button>
          <span className="text-sm text-muted-foreground py-1.5">
            {page + 1} / {data.totalPages}
          </span>
          <Button variant="outline" size="sm" disabled={page >= data.totalPages - 1}
            onClick={() => setPage(p => p + 1)}>다음</Button>
        </div>
      )}
    </div>
  );
}
