import { useNavigate } from 'react-router-dom';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
import { ChatView } from '@/components/chat/ChatView';
import { DocumentUpload } from '@/components/document/DocumentUpload';
import { DocumentList } from '@/components/document/DocumentList';
import { TagManager } from '@/components/document/TagManager';
import { CollectionManager } from '@/components/document/CollectionManager';
import { ConversationList } from '@/components/conversation/ConversationList';
import { useDocuments } from '@/hooks/useDocuments';
import { useModels } from '@/hooks/useModels';
import { useChat } from '@/hooks/useChat';
import { useConversations } from '@/hooks/useConversations';
import { useAuth } from '@/auth/AuthContext';
import { Button } from '@/components/ui/button';
import { LogOut, Settings2 } from 'lucide-react';
import type { Conversation } from '@/types';

export function MainPage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const { documents, uploading, upload, refresh: refreshDocs } = useDocuments();
  const modelState = useModels();
  const chat = useChat();
  const { conversations, refresh: refreshConversations, remove, rename } = useConversations();

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
    setTimeout(() => refreshConversations(), 2000);
  };

  return (
    <div className="flex h-screen bg-background">
      <aside className="w-80 bg-sidebar text-sidebar-foreground border-r border-sidebar-border flex flex-col">
        <Tabs defaultValue="conversations" className="flex flex-col flex-1 min-h-0">
          <TabsList className="mx-3 mt-3 bg-muted">
            <TabsTrigger value="conversations" className="flex-1 text-xs text-muted-foreground data-[state=active]:bg-secondary data-[state=active]:text-secondary-foreground">
              대화
            </TabsTrigger>
            <TabsTrigger value="documents" className="flex-1 text-xs text-muted-foreground data-[state=active]:bg-secondary data-[state=active]:text-secondary-foreground">
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
            <DocumentList documents={documents} onRefresh={refreshDocs} />
            <div className="border-t border-border pt-3 space-y-3">
              <TagManager />
              <CollectionManager />
            </div>
          </TabsContent>
        </Tabs>

        <div className="border-t border-sidebar-border px-3 h-16 flex items-center gap-2">
          <div className="flex-1 min-w-0">
            <p className="text-sm font-medium truncate">{user?.name}</p>
            <p className="text-xs text-muted-foreground truncate">{user?.email}</p>
          </div>
          {isAdmin && (
            <Button variant="ghost" size="icon" onClick={() => navigate('/admin')} className="shrink-0" title="관리">
              <Settings2 className="h-4 w-4" />
            </Button>
          )}
          <Button variant="ghost" size="icon" onClick={logout} className="shrink-0" title="로그아웃">
            <LogOut className="h-4 w-4" />
          </Button>
        </div>
      </aside>

      <main className="flex-1 flex flex-col">
        <ChatView
          models={modelState.models}
          chat={chat}
          onNewSession={handleNewSession}
          onSendComplete={handleSendComplete}
        />
      </main>
    </div>
  );
}
