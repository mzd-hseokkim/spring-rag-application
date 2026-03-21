import { Badge } from '@/components/ui/badge';
import type { Document } from '@/types';

interface Props {
  documents: Document[];
}

const STATUS_CONFIG: Record<string, { label: string; variant: 'default' | 'secondary' | 'destructive' | 'outline'; className: string }> = {
  PENDING: { label: '대기', variant: 'outline', className: 'text-muted-foreground' },
  PROCESSING: { label: '처리 중', variant: 'outline', className: 'border-yellow-500 text-yellow-600' },
  COMPLETED: { label: '완료', variant: 'outline', className: 'border-green-500 text-green-600' },
  FAILED: { label: '실패', variant: 'destructive', className: '' },
};

export function DocumentList({ documents }: Props) {
  if (documents.length === 0) {
    return <p className="text-sm text-muted-foreground text-center">업로드된 문서 없음</p>;
  }

  return (
    <ul className="space-y-0">
      {documents.map(doc => {
        const config = STATUS_CONFIG[doc.status];
        return (
          <li key={doc.id} className="flex items-center gap-2 py-1.5 text-sm border-b border-border last:border-b-0">
            <Badge variant={config.variant} className={`text-[10px] shrink-0 ${config.className}`}>
              {config.label}
            </Badge>
            <span className="truncate flex-1" title={doc.filename}>{doc.filename}</span>
            {doc.status === 'COMPLETED' && (
              <span className="text-xs text-muted-foreground shrink-0">{doc.chunkCount} chunks</span>
            )}
            {doc.status === 'FAILED' && (
              <span className="text-xs text-destructive shrink-0" title={doc.errorMessage || ''}>error</span>
            )}
          </li>
        );
      })}
    </ul>
  );
}
