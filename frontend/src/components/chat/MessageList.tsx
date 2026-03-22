import { useEffect, useRef, useState, type ReactNode } from 'react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Button } from '@/components/ui/button';
import {
  Search, Brain, ListTree, FileText, PenLine, CheckCircle2,
  Link, MessageSquare, HelpCircle, Loader2, Check,
  ThumbsUp, ThumbsDown, Paperclip, ChevronDown, ChevronUp,
  Upload, Sparkles,
} from 'lucide-react';
import type { Message } from '@/types';

interface Props {
  messages: Message[];
  streaming: boolean;
}

const STEP_ICONS: Record<string, ReactNode> = {
  analyze: <Brain className="size-3.5" />,
  compress: <Search className="size-3.5" />,
  decide: <Brain className="size-3.5" />,
  decompose: <ListTree className="size-3.5" />,
  search: <FileText className="size-3.5" />,
  sub_search: <FileText className="size-3.5" />,
  sub_answer: <PenLine className="size-3.5" />,
  sub_done: <CheckCircle2 className="size-3.5" />,
  synthesize: <Link className="size-3.5" />,
  generate: <MessageSquare className="size-3.5" />,
  direct: <MessageSquare className="size-3.5" />,
  clarify: <HelpCircle className="size-3.5" />,
};

export function MessageList({ messages, streaming }: Props) {
  const endRef = useRef<HTMLDivElement>(null);
  const [feedbacks, setFeedbacks] = useState<Record<number, 'up' | 'down'>>({});
  const [expandedSources, setExpandedSources] = useState<Record<number, boolean>>({});

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const submitFeedback = async (index: number, rating: 'up' | 'down') => {
    setFeedbacks(prev => ({ ...prev, [index]: rating }));
    try {
      await fetch('/api/chat/feedback', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: '', messageIndex: index, rating }),
      });
    } catch (err) {
      console.error('Failed to submit feedback:', err);
    }
  };

  const toggleSources = (index: number) => {
    setExpandedSources(prev => ({ ...prev, [index]: !prev[index] }));
  };

  if (messages.length === 0) {
    return (
      <div className="h-full flex flex-col items-center justify-center gap-4 text-muted-foreground/50 animate-page-in">
        <div className="relative">
          <div className="w-16 h-16 rounded-2xl bg-primary/10 flex items-center justify-center">
            <Sparkles className="size-8 text-primary/60" />
          </div>
        </div>
        <div className="text-center space-y-1.5">
          <p className="text-base font-medium text-foreground/70">무엇이든 물어보세요</p>
          <p className="text-sm text-muted-foreground/60">문서를 업로드하고 질문하면 AI가 답변해드립니다</p>
        </div>
        <div className="flex gap-2 mt-2">
          <span className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-muted text-xs text-muted-foreground">
            <Upload className="size-3" /> PDF, TXT, MD 지원
          </span>
        </div>
      </div>
    );
  }

  return (
    <ScrollArea className="h-full">
      <div className="flex flex-col gap-4 p-4 w-full max-w-250 mx-auto">
        {messages.map((msg, i) => {
          const isUser = msg.role === 'user';
          const isLastMsg = i === messages.length - 1;

          return (
            <div
              key={i}
              className={`flex ${isUser ? 'justify-end' : 'justify-start'} animate-page-in`}
            >
              <div
                className={`max-w-[80%] rounded-2xl px-4 py-3 text-sm leading-relaxed wrap-break-word ${
                  isUser
                    ? 'bg-primary text-primary-foreground rounded-br-md'
                    : 'bg-card border border-border shadow-sm rounded-bl-md'
                }`}
              >
                {msg.agentSteps && msg.agentSteps.length > 0 && (
                  <div className="mb-2.5 p-2.5 bg-muted/50 rounded-lg border-l-3 border-primary/60">
                    {msg.agentSteps.map((step, j) => {
                      const isLastStep = j === msg.agentSteps!.length - 1;
                      const showSpinner = isLastMsg && isLastStep && streaming;
                      const icon = showSpinner
                        ? <Loader2 className="size-3.5 animate-spin text-primary" />
                        : (STEP_ICONS[step.step] || <Check className="size-3.5 text-green-600 dark:text-green-400" />);
                      return (
                        <div key={j} className="flex items-center gap-1.5 py-0.5 text-xs text-muted-foreground">
                          <span className="shrink-0">{icon}</span>
                          <span>{step.message}</span>
                        </div>
                      );
                    })}
                  </div>
                )}

                {/* 스트리밍 중 빈 콘텐츠 → 타이핑 인디케이터 */}
                {!isUser && isLastMsg && streaming && !msg.content ? (
                  <div className="flex items-center gap-1.5 py-1">
                    <span className="typing-dot w-1.5 h-1.5 rounded-full bg-muted-foreground/50" />
                    <span className="typing-dot w-1.5 h-1.5 rounded-full bg-muted-foreground/50" />
                    <span className="typing-dot w-1.5 h-1.5 rounded-full bg-muted-foreground/50" />
                  </div>
                ) : (
                  <div className={`prose-sm [&_h1]:text-lg [&_h1]:font-bold [&_h1]:mt-4 [&_h1]:mb-2 [&_h2]:text-base [&_h2]:font-semibold [&_h2]:mt-3 [&_h2]:mb-1.5 [&_h3]:text-[15px] [&_h3]:font-semibold [&_h3]:mt-3 [&_h3]:mb-1 [&_p]:my-2 [&_ul]:my-2 [&_ul]:pl-6 [&_ol]:my-2 [&_ol]:pl-6 [&_li]:my-1 [&_strong]:font-semibold [&_code]:bg-muted [&_code]:px-1.5 [&_code]:py-0.5 [&_code]:rounded [&_code]:text-[13px] [&_code]:font-mono [&_pre]:bg-[#1e1e1e] [&_pre]:text-[#d4d4d4] [&_pre]:p-3 [&_pre]:rounded-lg [&_pre]:overflow-x-auto [&_pre]:my-2.5 [&_pre]:text-[13px] [&_pre_code]:bg-transparent [&_pre_code]:p-0 [&_pre_code]:text-inherit [&_blockquote]:border-l-3 [&_blockquote]:border-primary/40 [&_blockquote]:px-3 [&_blockquote]:py-1 [&_blockquote]:my-2 [&_blockquote]:text-muted-foreground [&_blockquote]:bg-muted/30 [&_blockquote]:rounded-r [&_table]:border-collapse [&_table]:my-2.5 [&_table]:text-[13px] [&_table]:w-full [&_th]:border [&_th]:border-border [&_th]:px-2.5 [&_th]:py-1.5 [&_th]:text-left [&_th]:bg-muted [&_th]:font-semibold [&_td]:border [&_td]:border-border [&_td]:px-2.5 [&_td]:py-1.5 [&_hr]:border-t [&_hr]:border-border [&_hr]:my-3`}>
                    {msg.role === 'assistant' ? (
                      <Markdown remarkPlugins={[remarkGfm]}>{msg.content}</Markdown>
                    ) : (
                      msg.content
                    )}
                  </div>
                )}

                {msg.role === 'assistant' && msg.content && (
                  <div className="mt-2 pt-2 border-t border-border/50 flex items-center gap-2">
                    <div className="flex gap-0.5">
                      <Button
                        variant="ghost"
                        size="sm"
                        className={`h-7 w-7 p-0 ${feedbacks[i] === 'up' ? 'bg-green-100 text-green-600 dark:bg-green-900/30 dark:text-green-400' : ''}`}
                        onClick={() => submitFeedback(i, 'up')}
                      >
                        <ThumbsUp className="size-3.5" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        className={`h-7 w-7 p-0 ${feedbacks[i] === 'down' ? 'bg-red-100 text-red-600 dark:bg-red-900/30 dark:text-red-400' : ''}`}
                        onClick={() => submitFeedback(i, 'down')}
                      >
                        <ThumbsDown className="size-3.5" />
                      </Button>
                    </div>

                    {msg.sources && msg.sources.length > 0 && (
                      <Button
                        variant="ghost"
                        size="sm"
                        className="h-7 text-xs text-muted-foreground gap-1"
                        onClick={() => toggleSources(i)}
                      >
                        <Paperclip className="size-3" />
                        출처 {msg.sources.length}건
                        {expandedSources[i] ? <ChevronUp className="size-3" /> : <ChevronDown className="size-3" />}
                      </Button>
                    )}
                  </div>
                )}

                {expandedSources[i] && msg.sources && msg.sources.length > 0 && (
                  <div className="mt-1.5 space-y-1.5">
                    {msg.sources.map((s, j) => {
                      const isTableExcerpt = s.excerpt.trimStart().startsWith('|');
                      return (
                        <div key={j} className="text-xs text-muted-foreground py-0.5 pl-1">
                          <div className="flex gap-1.5 items-baseline">
                            <span className="font-medium text-primary">{s.filename}</span>
                            <span className="text-[11px]">#{s.chunkIndex}</span>
                            {!isTableExcerpt && <span className="text-[11px] truncate">{s.excerpt}</span>}
                          </div>
                          {isTableExcerpt && (
                            <div className="mt-1 overflow-x-auto [&_table]:border-collapse [&_table]:text-[11px] [&_table]:w-full [&_th]:border [&_th]:border-border [&_th]:px-1.5 [&_th]:py-0.5 [&_th]:bg-muted [&_th]:font-semibold [&_td]:border [&_td]:border-border [&_td]:px-1.5 [&_td]:py-0.5">
                              <Markdown remarkPlugins={[remarkGfm]}>{s.excerpt}</Markdown>
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            </div>
          );
        })}
        <div ref={endRef} />
      </div>
    </ScrollArea>
  );
}
