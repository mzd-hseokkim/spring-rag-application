import { useCallback, useState } from 'react';

interface Props {
  onUpload: (file: File) => void;
  uploading: boolean;
}

export function DocumentUpload({ onUpload, uploading }: Props) {
  const [dragOver, setDragOver] = useState(false);

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    const file = e.dataTransfer.files[0];
    if (file) onUpload(file);
  }, [onUpload]);

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) onUpload(file);
    e.target.value = '';
  };

  return (
    <div
      className={`document-upload ${dragOver ? 'drag-over' : ''}`}
      onDragOver={e => { e.preventDefault(); setDragOver(true); }}
      onDragLeave={() => setDragOver(false)}
      onDrop={handleDrop}
    >
      {uploading ? (
        <p>업로드 중...</p>
      ) : (
        <>
          <p>파일을 드래그하거나 클릭하세요</p>
          <p className="upload-hint">PDF, TXT, Markdown</p>
          <input type="file" accept=".pdf,.txt,.md" onChange={handleFileSelect} />
        </>
      )}
    </div>
  );
}
