import { useState, useEffect, useCallback } from 'react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Badge } from '@/components/ui/badge';
import { Trash2 } from 'lucide-react';
import { fetchAdminDocuments, updateDocumentPublic, deleteAdminDocument } from '@/api/admin';

interface AdminDocument {
  id: string;
  filename: string;
  status: string;
  chunkCount: number;
  isPublic: boolean;
  ownerEmail: string | null;
  createdAt: string;
}

interface PageData {
  content: AdminDocument[];
  totalPages: number;
  number: number;
}

const STATUS_STYLE: Record<string, string> = {
  COMPLETED: 'border-green-500 text-green-600',
  FAILED: 'border-red-500 text-red-600',
  PROCESSING: 'border-yellow-500 text-yellow-600',
  PENDING: 'text-muted-foreground',
};

export function AdminDocumentsPage() {
  const [data, setData] = useState<PageData | null>(null);
  const [page, setPage] = useState(0);

  const load = useCallback(async () => {
    try {
      const res = await fetchAdminDocuments(page, 20);
      setData(res);
    } catch (err) {
      console.error('Failed to load documents:', err);
      toast.error('문서 목록 조회 실패');
    }
  }, [page]);

  useEffect(() => { load(); }, [load]);

  const handlePublicToggle = async (id: string, isPublic: boolean) => {
    try {
      await updateDocumentPublic(id, isPublic);
      await load();
    } catch (err) {
      console.error(err);
      toast.error('공용 설정 변경 실패');
    }
  };

  const handleDelete = async (id: string, filename: string) => {
    if (!confirm(`"${filename}" 문서를 삭제하시겠습니까?`)) return;
    try {
      await deleteAdminDocument(id);
      await load();
      toast.success('문서가 삭제되었습니다.');
    } catch (err) {
      console.error(err);
      toast.error('문서 삭제 실패');
    }
  };

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">문서 관리</h1>

      <div className="border rounded-lg">
        <table className="w-full text-sm">
          <thead className="bg-muted/50">
            <tr>
              <th className="text-left px-4 py-3 font-medium">파일명</th>
              <th className="text-left px-4 py-3 font-medium">소유자</th>
              <th className="text-center px-4 py-3 font-medium">공용</th>
              <th className="text-left px-4 py-3 font-medium">상태</th>
              <th className="text-right px-4 py-3 font-medium">청크</th>
              <th className="text-left px-4 py-3 font-medium">등록일</th>
              <th className="px-4 py-3 w-16"></th>
            </tr>
          </thead>
          <tbody>
            {data?.content.map(doc => (
              <tr key={doc.id} className="border-t hover:bg-muted/30">
                <td className="px-4 py-3 max-w-48 truncate" title={doc.filename}>{doc.filename}</td>
                <td className="px-4 py-3 text-muted-foreground">{doc.ownerEmail || '-'}</td>
                <td className="px-4 py-3 text-center">
                  <Checkbox
                    checked={doc.isPublic}
                    onCheckedChange={(checked) => handlePublicToggle(doc.id, checked === true)}
                  />
                </td>
                <td className="px-4 py-3">
                  <Badge variant="outline" className={`text-[10px] ${STATUS_STYLE[doc.status] || ''}`}>
                    {doc.status}
                  </Badge>
                </td>
                <td className="px-4 py-3 text-right text-muted-foreground">{doc.chunkCount}</td>
                <td className="px-4 py-3 text-muted-foreground">
                  {new Date(doc.createdAt).toLocaleDateString('ko-KR')}
                </td>
                <td className="px-4 py-3">
                  <Button variant="ghost" size="icon" className="h-8 w-8 text-destructive"
                    onClick={() => handleDelete(doc.id, doc.filename)}>
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {data && data.totalPages > 1 && (
        <div className="flex justify-center gap-2">
          <Button variant="outline" size="sm" disabled={page === 0}
            onClick={() => setPage(p => p - 1)}>이전</Button>
          <span className="text-sm text-muted-foreground py-1.5">
            {page + 1} / {data.totalPages}
          </span>
          <Button variant="outline" size="sm" disabled={page >= data.totalPages - 1}
            onClick={() => setPage(p => p + 1)}>다음</Button>
        </div>
      )}
    </div>
  );
}
