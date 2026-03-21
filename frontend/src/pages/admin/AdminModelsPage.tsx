import { useModels } from '@/hooks/useModels';
import { ModelManagement } from '@/components/model/ModelManagement';

export function AdminModelsPage() {
  const modelState = useModels();

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">모델 관리</h1>
      <ModelManagement modelState={modelState} />
    </div>
  );
}
