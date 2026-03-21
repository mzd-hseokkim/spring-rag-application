import { Toaster } from '@/components/ui/sonner';
import { TooltipProvider } from '@/components/ui/tooltip';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
import { ChatView } from '@/components/chat/ChatView';
import { DocumentUpload } from '@/components/document/DocumentUpload';
import { DocumentList } from '@/components/document/DocumentList';
import { ModelManagement } from '@/components/model/ModelManagement';
import { useDocuments } from '@/hooks/useDocuments';
import { useModels } from '@/hooks/useModels';

function App() {
  const { documents, uploading, upload } = useDocuments();
  const modelState = useModels();

  return (
    <TooltipProvider>
      <div className="flex h-screen bg-background">
        <aside className="w-80 bg-sidebar text-sidebar-foreground border-r border-sidebar-border flex flex-col">
          <Tabs defaultValue="documents" className="flex flex-col h-full">
            <TabsList className="mx-3 mt-3 bg-muted">
              <TabsTrigger value="documents" className="flex-1 text-xs text-muted-foreground data-[state=active]:bg-secondary data-[state=active]:text-secondary-foreground">
                문서
              </TabsTrigger>
              <TabsTrigger value="models" className="flex-1 text-xs text-muted-foreground data-[state=active]:bg-secondary data-[state=active]:text-secondary-foreground">
                모델
              </TabsTrigger>
            </TabsList>
            <TabsContent value="documents" className="flex-1 overflow-y-auto p-3 space-y-3 mt-0">
              <DocumentUpload onUpload={upload} uploading={uploading} />
              <DocumentList documents={documents} />
            </TabsContent>
            <TabsContent value="models" className="flex-1 overflow-y-auto p-3 mt-0">
              <ModelManagement modelState={modelState} />
            </TabsContent>
          </Tabs>
        </aside>

        <main className="flex-1 flex flex-col">
          <ChatView models={modelState.models} />
        </main>
      </div>
      <Toaster richColors position="top-right" />
    </TooltipProvider>
  );
}

export default App;
