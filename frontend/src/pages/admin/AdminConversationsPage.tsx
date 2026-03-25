import React, { useState, useEffect, useCallback } from 'react';
import { toast } from 'sonner';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Button } from '@/components/ui/button';
import { Trash2 } from 'lucide-react';
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
  const [expandedId, setExpandedId] = useState<string | null>(null);
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

  const handleToggle = async (id: string) => {
    if (expandedId === id) {
      setExpandedId(null);
      return;
    }
    try {
      const res = await fetchAdminConversationDetail(id);
      setDetail(res);
      setExpandedId(id);
    } catch (err) {
      console.error(err);
      toast.error('대화 상세 조회 실패');
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm('이 대화를 삭제하시겠습니까?')) return;
    try {
      await deleteAdminConversation(id);
      if (expandedId === id) setExpandedId(null);
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
              <th className="px-4 py-3 w-12"></th>
            </tr>
          </thead>
          <tbody>
            {data?.content.map(conv => (
              <React.Fragment key={conv.id}>
                <tr className={`border-t hover:bg-muted/30 ${expandedId === conv.id ? 'bg-muted/20' : ''}`}>
                  <td className="px-4 py-3 max-w-60">
                    <button
                      type="button"
                      className="text-left truncate block max-w-full underline decoration-dotted underline-offset-2 hover:decoration-solid cursor-pointer"
                      onClick={() => handleToggle(conv.id)}
                    >
                      {conv.title || '(제목 없음)'}
                    </button>
                  </td>
                  <td className="px-4 py-3 text-muted-foreground">{conv.ownerEmail || '-'}</td>
                  <td className="px-4 py-3 text-muted-foreground text-xs">{conv.modelName || '-'}</td>
                  <td className="px-4 py-3 text-muted-foreground text-xs">{formatDate(conv.updatedAt)}</td>
                  <td className="px-4 py-3">
                    <Button variant="ghost" size="icon" className="h-8 w-8 text-destructive"
                      onClick={() => handleDelete(conv.id)}>
                      <Trash2 className="h-4 w-4" />
                    </Button>
                  </td>
                </tr>
                <tr>
                  <td colSpan={5} className="p-0">
                    <div className="grid transition-all duration-300 ease-in-out"
                         style={{ gridTemplateRows: expandedId === conv.id ? '1fr' : '0fr' }}>
                      <div className="overflow-hidden">
                        {detail?.conversation.id === conv.id && (
                          <div className="px-4 py-3 bg-muted/10 border-t space-y-2 max-h-125 overflow-y-auto">
                            {detail.messages.length === 0 ? (
                              <p className="text-sm text-muted-foreground">메시지 없음</p>
                            ) : detail.messages.map((msg, i) => (
                              <div key={i} className={`text-sm p-3 rounded-lg ${msg.role === 'user' ? 'bg-muted' : 'bg-card border border-border'}`}>
                                <span className="font-medium text-xs text-muted-foreground">
                                  {msg.role === 'user' ? '사용자' : 'AI'}
                                </span>
                                {msg.role === 'assistant' ? (
                                  <div className="mt-1 prose-sm [&_h1]:text-lg [&_h1]:font-bold [&_h1]:mt-4 [&_h1]:mb-2 [&_h2]:text-base [&_h2]:font-semibold [&_h2]:mt-3 [&_h2]:mb-1.5 [&_h3]:text-[15px] [&_h3]:font-semibold [&_h3]:mt-3 [&_h3]:mb-1 [&_p]:my-2 [&_ul]:my-2 [&_ul]:pl-6 [&_ul]:list-disc [&_ol]:my-2 [&_ol]:pl-6 [&_ol]:list-decimal [&_li]:my-1 [&_strong]:font-semibold [&_code]:bg-muted [&_code]:px-1.5 [&_code]:py-0.5 [&_code]:rounded [&_code]:text-[13px] [&_code]:font-mono [&_pre]:bg-[#1e1e1e] [&_pre]:text-[#d4d4d4] [&_pre]:p-3 [&_pre]:rounded-lg [&_pre]:overflow-x-auto [&_pre]:my-2.5 [&_pre]:text-[13px] [&_pre_code]:bg-transparent [&_pre_code]:p-0 [&_pre_code]:text-inherit [&_blockquote]:border-l-3 [&_blockquote]:border-primary/40 [&_blockquote]:px-3 [&_blockquote]:py-1 [&_blockquote]:my-2 [&_blockquote]:text-muted-foreground [&_blockquote]:bg-muted/30 [&_blockquote]:rounded-r [&_table]:border-collapse [&_table]:my-2.5 [&_table]:text-[13px] [&_table]:w-full [&_th]:border [&_th]:border-border [&_th]:px-2.5 [&_th]:py-1.5 [&_th]:text-left [&_th]:bg-muted [&_th]:font-semibold [&_td]:border [&_td]:border-border [&_td]:px-2.5 [&_td]:py-1.5 [&_hr]:border-t [&_hr]:border-border [&_hr]:my-3">
                                    <Markdown remarkPlugins={[remarkGfm]}>{msg.content}</Markdown>
                                  </div>
                                ) : (
                                  <p className="mt-0.5 whitespace-pre-wrap">{msg.content}</p>
                                )}
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    </div>
                  </td>
                </tr>
              </React.Fragment>
            ))}
          </tbody>
        </table>
      </div>

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
