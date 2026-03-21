import { useCallback, useState } from 'react';
import { Card } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';

interface Props {
  onUpload: (file: File, isPublic: boolean) => void;
  uploading: boolean;
  isAdmin?: boolean;
}

export function DocumentUpload({ onUpload, uploading, isAdmin }: Props) {
  const [dragOver, setDragOver] = useState(false);
  const [isPublic, setIsPublic] = useState(false);

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    const file = e.dataTransfer.files[0];
    if (file) onUpload(file, isPublic);
  }, [onUpload, isPublic]);

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) onUpload(file, isPublic);
    e.target.value = '';
  };

  return (
    <div className="space-y-2">
      <Card
        className={`relative cursor-pointer border-2 border-dashed text-center ring-0 py-5 transition-colors ${
          dragOver ? 'border-primary bg-primary/5' : 'border-border'
        }`}
        onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
        onDragLeave={() => setDragOver(false)}
        onDrop={handleDrop}
      >
        {uploading ? (
          <p className="text-sm text-muted-foreground px-4">업로드 중...</p>
        ) : (
          <>
            <p className="text-sm text-muted-foreground px-4">파일을 드래그하거나 클릭하세요</p>
            <p className="text-xs text-muted-foreground/70 mt-1 px-4">PDF, TXT, Markdown</p>
            <input
              type="file"
              accept=".pdf,.txt,.md"
              onChange={handleFileSelect}
              className="absolute inset-0 opacity-0 cursor-pointer"
            />
          </>
        )}
      </Card>
      {isAdmin && (
        <label className="flex items-center gap-2 text-xs text-muted-foreground cursor-pointer px-1">
          <Checkbox
            checked={isPublic}
            onCheckedChange={(checked) => setIsPublic(checked === true)}
          />
          공용 문서로 등록
        </label>
      )}
    </div>
  );
}
