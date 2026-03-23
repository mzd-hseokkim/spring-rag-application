import { CheckCircle2, Circle, Loader2 } from 'lucide-react';

interface SectionProgress {
  index: number;
  title: string;
  status: 'pending' | 'generating' | 'complete';
}

interface Props {
  sections: SectionProgress[];
  statusMessage: string | null;
  currentSection: number;
  totalSections: number;
}

export function GenerationProgress({ sections, statusMessage, currentSection, totalSections }: Props) {
  const progress = totalSections > 0 ? Math.round((currentSection / totalSections) * 100) : 0;

  return (
    <div className="space-y-6">
      {statusMessage && (
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" />
          {statusMessage}
        </div>
      )}

      {totalSections > 0 && (
        <>
          <div className="space-y-2">
            <div className="flex justify-between text-sm">
              <span className="text-muted-foreground">진행률</span>
              <span className="font-medium">{progress}% ({currentSection}/{totalSections})</span>
            </div>
            <div className="h-2 bg-muted rounded-full overflow-hidden">
              <div
                className="h-full bg-primary rounded-full transition-all duration-500 ease-out"
                style={{ width: `${progress}%` }}
              />
            </div>
          </div>

          <div className="space-y-1">
            {sections.map((section) => (
              <div key={section.index} className="flex items-center gap-2.5 py-1.5 text-sm">
                {section.status === 'complete' && (
                  <CheckCircle2 className="h-4 w-4 text-green-500 shrink-0" />
                )}
                {section.status === 'generating' && (
                  <Loader2 className="h-4 w-4 text-primary animate-spin shrink-0" />
                )}
                {section.status === 'pending' && (
                  <Circle className="h-4 w-4 text-muted-foreground/40 shrink-0" />
                )}
                <span className={section.status === 'pending' ? 'text-muted-foreground/60' : ''}>
                  {section.title}
                </span>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
