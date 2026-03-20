import type { Document } from '../../types';

interface Props {
  documents: Document[];
}

const STATUS_LABEL: Record<string, string> = {
  PENDING: '대기',
  PROCESSING: '처리 중',
  COMPLETED: '완료',
  FAILED: '실패',
};

const STATUS_ICON: Record<string, string> = {
  PENDING: '...',
  PROCESSING: '~',
  COMPLETED: 'v',
  FAILED: 'x',
};

export function DocumentList({ documents }: Props) {
  if (documents.length === 0) {
    return <p className="no-documents">업로드된 문서 없음</p>;
  }

  return (
    <ul className="document-list">
      {documents.map(doc => (
        <li key={doc.id} className={`doc-item status-${doc.status.toLowerCase()}`}>
          <span className="doc-status" title={STATUS_LABEL[doc.status]}>
            [{STATUS_ICON[doc.status]}]
          </span>
          <span className="doc-name" title={doc.filename}>{doc.filename}</span>
          {doc.status === 'COMPLETED' && (
            <span className="doc-chunks">{doc.chunkCount} chunks</span>
          )}
          {doc.status === 'FAILED' && (
            <span className="doc-error" title={doc.errorMessage || ''}>error</span>
          )}
        </li>
      ))}
    </ul>
  );
}
