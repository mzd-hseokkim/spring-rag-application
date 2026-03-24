import { CheckCircle2, Circle, Loader2 } from 'lucide-react';

interface PersonaProgress {
  index: number;
  name: string;
  status: 'pending' | 'generating' | 'complete';
}

interface Props {
  personas: PersonaProgress[];
  statusMessage: string | null;
  currentPersona: number;
  totalPersonas: number;
}

export function QuestionnaireProgress({ personas, statusMessage, currentPersona, totalPersonas }: Props) {
  const progress = totalPersonas > 0 ? Math.round((currentPersona / totalPersonas) * 100) : 0;

  return (
    <div className="space-y-6">
      {statusMessage && (
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="h-4 w-4 animate-spin" />
          {statusMessage}
        </div>
      )}

      {totalPersonas > 0 && (
        <>
          <div className="space-y-2">
            <div className="flex justify-between text-sm">
              <span className="text-muted-foreground">진행률</span>
              <span className="font-medium">{progress}% ({currentPersona}/{totalPersonas})</span>
            </div>
            <div className="h-2 bg-muted rounded-full overflow-hidden">
              <div
                className="h-full bg-primary rounded-full transition-all duration-500 ease-out"
                style={{ width: `${progress}%` }}
              />
            </div>
          </div>

          <div className="space-y-1">
            {personas.map((persona) => (
              <div key={persona.index} className="flex items-center gap-2.5 py-1.5 text-sm">
                {persona.status === 'complete' && (
                  <CheckCircle2 className="h-4 w-4 text-green-500 shrink-0" />
                )}
                {persona.status === 'generating' && (
                  <Loader2 className="h-4 w-4 text-primary animate-spin shrink-0" />
                )}
                {persona.status === 'pending' && (
                  <Circle className="h-4 w-4 text-muted-foreground/40 shrink-0" />
                )}
                <span className={persona.status === 'pending' ? 'text-muted-foreground/60' : ''}>
                  {persona.name}
                </span>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
