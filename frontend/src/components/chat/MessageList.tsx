import { useEffect, useRef, useState, type ReactNode } from 'react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Button } from '@/components/ui/button';
import {
  Search, Brain, ListTree, FileText, PenLine, CheckCircle2,
  Link, MessageSquare, HelpCircle, Loader2, Check,
  ThumbsUp, ThumbsDown, Paperclip, ChevronDown, ChevronUp,
} from 'lucide-react';
import type { Message } from '@/types';

interface Props {
  messages: Message[];
  streaming: boolean;
}

const STEP_ICONS: Record<string, ReactNode> = {
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
    } catch { /* ignore */ }
  };

  const toggleSources = (index: number) => {
    setExpandedSources(prev => ({ ...prev, [index]: !prev[index] }));
  };

  if (messages.length === 0) {
    return (
      <div className="h-full flex flex-col items-center justify-center gap-2 text-muted-foreground/60">
        <MessageSquare className="size-10 stroke-1" />
        <p className="text-sm font-medium">문서를 업로드한 후 질문해보세요.</p>
      </div>
    );
  }

  return (
    <ScrollArea className="h-full">
      <div className="flex flex-col gap-3 p-4">
        {messages.map((msg, i) => (
          <div
            key={i}
            className={`max-w-[85%] rounded-xl px-4 py-3 text-sm leading-relaxed wrap-break-word ${
              msg.role === 'user'
                ? 'self-end bg-secondary text-secondary-foreground whitespace-pre-wrap'
                : 'self-start bg-card border ring-1 ring-foreground/5 shadow-sm'
            }`}
          >
            <div className="text-[11px] font-semibold mb-1.5 opacity-60 uppercase tracking-wide">
              {msg.role === 'user' ? '사용자' : 'AI'}
            </div>
            {msg.agentSteps && msg.agentSteps.length > 0 && (
              <div className="mb-2 p-2 bg-muted/50 rounded border-l-3 border-primary">
                {msg.agentSteps.map((step, j) => {
                  const isLastMsg = i === messages.length - 1;
                  const isLastStep = j === msg.agentSteps!.length - 1;
                  const showSpinner = isLastMsg && isLastStep && streaming;
                  const icon = showSpinner
                    ? <Loader2 className="size-3.5 animate-spin" />
                    : (STEP_ICONS[step.step] || <Check className="size-3.5" />);
                  return (
                    <div key={j} className="flex items-center gap-1.5 py-0.5 text-xs text-muted-foreground">
                      <span className="shrink-0">{icon}</span>
                      <span>{step.message}</span>
                    </div>
                  );
                })}
              </div>
            )}
            <div className="prose-sm [&_h1]:text-lg [&_h1]:font-bold [&_h1]:mt-4 [&_h1]:mb-2 [&_h2]:text-base [&_h2]:font-semibold [&_h2]:mt-3 [&_h2]:mb-1.5 [&_h3]:text-[15px] [&_h3]:font-semibold [&_h3]:mt-3 [&_h3]:mb-1 [&_p]:my-2 [&_ul]:my-2 [&_ul]:pl-6 [&_ol]:my-2 [&_ol]:pl-6 [&_li]:my-1 [&_strong]:font-semibold [&_code]:bg-muted [&_code]:px-1.5 [&_code]:py-0.5 [&_code]:rounded [&_code]:text-[13px] [&_code]:font-mono [&_pre]:bg-[#1e1e1e] [&_pre]:text-[#d4d4d4] [&_pre]:p-3 [&_pre]:rounded-lg [&_pre]:overflow-x-auto [&_pre]:my-2.5 [&_pre]:text-[13px] [&_pre_code]:bg-transparent [&_pre_code]:p-0 [&_pre_code]:text-inherit [&_blockquote]:border-l-3 [&_blockquote]:border-primary [&_blockquote]:px-3 [&_blockquote]:py-1 [&_blockquote]:my-2 [&_blockquote]:text-muted-foreground [&_blockquote]:bg-muted/30 [&_blockquote]:rounded-r [&_table]:border-collapse [&_table]:my-2.5 [&_table]:text-[13px] [&_table]:w-full [&_th]:border [&_th]:border-border [&_th]:px-2.5 [&_th]:py-1.5 [&_th]:text-left [&_th]:bg-muted [&_th]:font-semibold [&_td]:border [&_td]:border-border [&_td]:px-2.5 [&_td]:py-1.5 [&_hr]:border-t [&_hr]:border-border [&_hr]:my-3">
              {msg.role === 'assistant' ? (
                <Markdown remarkPlugins={[remarkGfm]}>{msg.content}</Markdown>
              ) : (
                msg.content
              )}
            </div>

            {msg.role === 'assistant' && msg.content && (
              <div className="mt-2 pt-2 border-t border-border flex items-center gap-2">
                <div className="flex gap-0.5">
                  <Button
                    variant="ghost"
                    size="sm"
                    className={`h-7 w-7 p-0 ${feedbacks[i] === 'up' ? 'bg-green-100 text-green-600' : ''}`}
                    onClick={() => submitFeedback(i, 'up')}
                  >
                    <ThumbsUp className="size-3.5" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    className={`h-7 w-7 p-0 ${feedbacks[i] === 'down' ? 'bg-red-100 text-red-600' : ''}`}
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
              <div className="mt-1.5 space-y-1">
                {msg.sources.map((s, j) => (
                  <div key={j} className="flex gap-1.5 items-baseline text-xs text-muted-foreground py-0.5 pl-1">
                    <span className="font-medium text-primary">{s.filename}</span>
                    <span className="text-[11px]">#{s.chunkIndex}</span>
                    <span className="text-[11px] truncate">{s.excerpt}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        ))}
        <div ref={endRef} />
      </div>
    </ScrollArea>
  );
}
