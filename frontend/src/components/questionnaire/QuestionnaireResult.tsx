import { useState, useEffect } from 'react';
import { Button } from '@/components/ui/button';
import { getQuestionnaireDownloadUrl, getQuestionnairePreviewUrl } from '@/api/questionnaire';
import { Download, RotateCcw, ExternalLink } from 'lucide-react';

interface Props {
  jobId: string;
  onRegenerate: () => void;
}

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('accessToken');
  return token ? { 'Authorization': `Bearer ${token}` } : {};
}

export function QuestionnaireResult({ jobId, onRegenerate }: Props) {
  const [previewHtml, setPreviewHtml] = useState<string | null>(null);

  useEffect(() => {
    fetch(getQuestionnairePreviewUrl(jobId), { headers: authHeaders() })
      .then(res => res.ok ? res.text() : '')
      .then(setPreviewHtml)
      .catch(() => setPreviewHtml(''));
  }, [jobId]);

  const handleDownload = async () => {
    const res = await fetch(getQuestionnaireDownloadUrl(jobId), { headers: authHeaders() });
    if (!res.ok) return;
    const blob = await res.blob();
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    const disposition = res.headers.get('Content-Disposition');
    a.download = disposition?.split('filename=')[1]?.replace(/"/g, '') || 'questionnaire.xlsx';
    a.click();
    window.URL.revokeObjectURL(url);
  };

  const handleOpenPreview = () => {
    const token = localStorage.getItem('accessToken') || '';
    window.open(`${getQuestionnairePreviewUrl(jobId)}?token=${token}`, '_blank');
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2 text-green-600 dark:text-green-400">
        <svg className="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
          <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
        </svg>
        <span className="font-medium">예상 질의서 생성 완료</span>
      </div>

      <div className="flex gap-2">
        <Button onClick={handleDownload} size="sm">
          <Download className="h-4 w-4 mr-1.5" />
          엑셀 다운로드
        </Button>
        <Button variant="outline" size="sm" onClick={handleOpenPreview}>
          <ExternalLink className="h-4 w-4 mr-1.5" />
          미리보기
        </Button>
        <Button variant="outline" size="sm" onClick={onRegenerate}>
          <RotateCcw className="h-4 w-4 mr-1.5" />
          다시 생성
        </Button>
      </div>

      <div className="border rounded-lg overflow-hidden bg-white">
        {previewHtml ? (
          <iframe
            srcDoc={previewHtml}
            className="w-full h-[500px]"
            title="질의서 미리보기"
          />
        ) : (
          <div className="flex items-center justify-center h-[500px] text-muted-foreground text-sm">
            미리보기 로딩 중...
          </div>
        )}
      </div>
    </div>
  );
}
