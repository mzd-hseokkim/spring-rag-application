import { useEffect, useRef } from 'react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Badge } from '@/components/ui/badge';
import type { Message } from '@/types';

interface Props {
  messages: Message[];
  streaming: boolean;
}

const STEP_ICONS: Record<string, string> = {
  compress: '\uD83D\uDD0D',
  decide: '\uD83E\uDD14',
  decompose: '\uD83D\uDCCB',
  search: '\uD83D\uDCC4',
  sub_search: '\uD83D\uDCC4',
  sub_answer: '\u270F\uFE0F',
  sub_done: '\u2705',
  synthesize: '\uD83D\uDD17',
  generate: '\uD83D\uDCAC',
  direct: '\uD83D\uDCAC',
  clarify: '\u2753',
};

export function MessageList({ messages, streaming }: Props) {
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  if (messages.length === 0) {
    return (
      <div className="flex-1 flex items-center justify-center text-muted-foreground">
        <p>문서를 업로드한 후 질문해보세요.</p>
      </div>
    );
  }

  return (
    <ScrollArea className="h-full">
      <div className="flex flex-col gap-3 p-4">
        {messages.map((msg, i) => (
          <div
            key={i}
            className={`max-w-[80%] rounded-xl px-4 py-3 text-sm leading-relaxed wrap-break-word ${
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
                  const icon = showSpinner ? '\u23F3' : (STEP_ICONS[step.step] || '\u2714\uFE0F');
                  return (
                    <div key={j} className="flex items-center gap-1.5 py-0.5 text-xs text-muted-foreground">
                      <span className={`shrink-0 ${showSpinner ? 'animate-spin' : ''}`}>{icon}</span>
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
            {msg.sources && msg.sources.length > 0 && (
              <div className="mt-2 pt-2 border-t border-border">
                <Badge variant="secondary" className="mb-1.5">출처</Badge>
                {msg.sources.map((s, j) => (
                  <div key={j} className="flex gap-1.5 items-baseline text-xs text-muted-foreground py-0.5">
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
