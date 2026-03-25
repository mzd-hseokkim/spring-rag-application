import { useState, useCallback } from 'react';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
import { ChatView, type FileUploadStatus } from '@/components/chat/ChatView';
import { DocumentUpload } from '@/components/document/DocumentUpload';
import { DocumentList } from '@/components/document/DocumentList';
import { TagManager } from '@/components/document/TagManager';
import { CollectionManager } from '@/components/document/CollectionManager';
import { ConversationList } from '@/components/conversation/ConversationList';
import { AppSidebar } from '@/components/layout/AppSidebar';
import { useDocuments } from '@/hooks/useDocuments';
import { useModels } from '@/hooks/useModels';
import { useChat } from '@/hooks/useChat';
import { useConversations } from '@/hooks/useConversations';
import { useAuth } from '@/auth/AuthContext';
import type { Conversation } from '@/types';

export function MainPage() {
  const { user } = useAuth();
  const { documents, uploading, upload, refresh: refreshDocs } = useDocuments();
  const modelState = useModels();
  const { conversations, refresh: refreshConversations, remove, rename } = useConversations();
  const handleTitleGenerated = useCallback((_sessionId: string, _title: string) => {
    refreshConversations();
  }, [refreshConversations]);
  const chat = useChat(handleTitleGenerated);
  const [tagColRefreshKey, setTagColRefreshKey] = useState(0);
  const [activeTab, setActiveTab] = useState(() => localStorage.getItem('sidebar:tab') || 'conversations');
  const [uploadStatus, setUploadStatus] = useState<FileUploadStatus | null>(null);
  const bumpTagColKey = () => {
    setTagColRefreshKey(k => k + 1);
    refreshDocs();
  };

  const handleFileDrop = async (file: File) => {
    setUploadStatus({ filename: file.name, status: 'uploading' });
    try {
      await upload(file, false);
      setUploadStatus({ filename: file.name, status: 'processing' });
      // 문서 상태 폴링 — COMPLETED/FAILED가 될 때까지
      const poll = setInterval(async () => {
        const docs = await refreshDocs();
        const doc = docs?.find((d: { filename: string }) => d.filename === file.name);
        if (doc?.status === 'COMPLETED') {
          setUploadStatus({ filename: file.name, status: 'completed' });
          clearInterval(poll);
          setTimeout(() => setUploadStatus(null), 5000);
        } else if (doc?.status === 'FAILED') {
          setUploadStatus({ filename: file.name, status: 'failed' });
          clearInterval(poll);
        }
      }, 2000);
    } catch {
      setUploadStatus({ filename: file.name, status: 'failed' });
    }
  };

  const isAdmin = user?.role === 'ADMIN';

  const handleNewSession = () => {
    chat.newSession();
  };

  const handleSelectConversation = (conv: Conversation) => {
    chat.loadConversation(conv.id, conv.sessionId);
  };

  const handleDeleteConversation = async (id: string) => {
    const deleted = conversations.find(c => c.id === id);
    await remove(id);
    if (deleted && deleted.sessionId === chat.sessionId) {
      chat.newSession();
    }
  };

  const handleSendComplete = () => {
    // 새 대화가 목록에 나타나도록 갱신 (제목은 WebSocket으로 수신)
    refreshConversations();
  };

  const handleTabChange = (tab: string) => {
    setActiveTab(tab);
    localStorage.setItem('sidebar:tab', tab);
  };

  return (
    <div className="flex h-screen bg-background">
      <AppSidebar>
        <Tabs value={activeTab} onValueChange={handleTabChange} className="flex flex-col flex-1 min-h-0">
          <TabsList className="mx-3 mt-3 bg-sidebar-accent">
            <TabsTrigger value="conversations" className="flex-1 text-xs text-sidebar-foreground/50 hover:text-sidebar-foreground! data-active:bg-sidebar-primary! data-active:text-sidebar-primary-foreground! data-active:hover:text-sidebar-primary-foreground!">
              대화
            </TabsTrigger>
            <TabsTrigger value="documents" className="flex-1 text-xs text-sidebar-foreground/50 hover:text-sidebar-foreground! data-active:bg-sidebar-primary! data-active:text-sidebar-primary-foreground! data-active:hover:text-sidebar-primary-foreground!">
              문서
            </TabsTrigger>
          </TabsList>
          <TabsContent value="conversations" className="flex-1 overflow-y-auto p-3 mt-0">
            <ConversationList
              conversations={conversations}
              activeSessionId={chat.sessionId}
              onSelect={handleSelectConversation}
              onDelete={handleDeleteConversation}
              onRename={rename}
            />
          </TabsContent>
          <TabsContent value="documents" className="flex-1 overflow-y-auto p-3 space-y-3 mt-0">
            <DocumentUpload onUpload={upload} uploading={uploading} isAdmin={isAdmin} />
            <div className="space-y-3">
              <TagManager onTagsChange={bumpTagColKey} />
              <CollectionManager onCollectionsChange={bumpTagColKey} />
            </div>
            <div className="border-t border-sidebar-border pt-3">
              <DocumentList documents={documents} onRefresh={refreshDocs} refreshKey={tagColRefreshKey} />
            </div>
          </TabsContent>
        </Tabs>
      </AppSidebar>

      <main className="flex-1 flex flex-col min-w-0">
        <ChatView
          models={modelState.models}
          chat={chat}
          onNewSession={handleNewSession}
          onSendComplete={handleSendComplete}
          onFileDrop={handleFileDrop}
          uploadStatus={uploadStatus}
          onDismissUpload={() => setUploadStatus(null)}
          filterRefreshKey={tagColRefreshKey}
        />
      </main>
    </div>
  );
}
