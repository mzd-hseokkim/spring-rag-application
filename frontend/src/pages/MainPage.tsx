import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
import { ChatView, type FileUploadStatus } from '@/components/chat/ChatView';
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
import { useTheme } from 'next-themes';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { LogOut, Settings2, PanelLeftClose, PanelLeft, MoreHorizontal, UserCog, Moon, Sun, FileText, ClipboardList } from 'lucide-react';
import type { Conversation } from '@/types';

export function MainPage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const { documents, uploading, upload, refresh: refreshDocs } = useDocuments();
  const modelState = useModels();
  const chat = useChat();
  const [tagColRefreshKey, setTagColRefreshKey] = useState(0);
  const [sidebarOpen, setSidebarOpen] = useState(() => localStorage.getItem('sidebar:open') !== 'false');
  const [activeTab, setActiveTab] = useState(() => localStorage.getItem('sidebar:tab') || 'conversations');
  const [uploadStatus, setUploadStatus] = useState<FileUploadStatus | null>(null);
  const bumpTagColKey = () => {
    setTagColRefreshKey(k => k + 1);
    refreshDocs();
  };
  const { conversations, refresh: refreshConversations, remove, rename } = useConversations();

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
    setTimeout(() => refreshConversations(), 2000);
  };

  const handleSidebarToggle = (open: boolean) => {
    setSidebarOpen(open);
    localStorage.setItem('sidebar:open', String(open));
  };

  const handleTabChange = (tab: string) => {
    setActiveTab(tab);
    localStorage.setItem('sidebar:tab', tab);
  };

  return (
    <div className="flex h-screen bg-background">
      <aside className={`bg-sidebar text-sidebar-foreground border-r border-sidebar-border flex flex-col shrink-0 transition-all duration-300 ease-in-out overflow-hidden ${sidebarOpen ? 'w-80' : 'w-0 border-r-0'}`}>
        <div className="flex items-center gap-2 px-3 pt-3 pb-1">
          <Button variant="ghost" size="icon" onClick={() => handleSidebarToggle(false)}
            className="shrink-0 h-8 w-8 text-sidebar-foreground/60 hover:text-sidebar-foreground hover:bg-sidebar-accent/50" title="메뉴 닫기">
            <PanelLeftClose className="h-4 w-4" />
          </Button>
        </div>
        <Tabs value={activeTab} onValueChange={handleTabChange} className="flex flex-col flex-1 min-h-0">
          <TabsList className="mx-3 bg-sidebar-accent">
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

        <div className="border-t border-sidebar-border px-3 h-14 flex items-center gap-2">
          <div className="flex-1 min-w-0">
            <p className="text-sm font-medium truncate">{user?.name}</p>
            <p className="text-xs text-sidebar-foreground/40 truncate">{user?.email}</p>
          </div>
          <Popover>
            <PopoverTrigger className="inline-flex items-center justify-center h-8 w-8 rounded-md shrink-0 text-sidebar-foreground/60 hover:text-sidebar-foreground hover:bg-sidebar-accent/50 cursor-pointer">
              <MoreHorizontal className="h-4 w-4" />
            </PopoverTrigger>
            <PopoverContent className="w-44 p-1" align="end" side="top">
              <MenuButton icon={<FileText className="h-4 w-4" />} label="문서 생성" onClick={() => navigate('/generate')} />
              <MenuButton icon={<ClipboardList className="h-4 w-4" />} label="예상 질의서" onClick={() => navigate('/questionnaire')} />
              <MenuButton icon={<UserCog className="h-4 w-4" />} label="개인설정" onClick={() => navigate('/settings')} />
              <ThemeMenuItem />
              {isAdmin && (
                <MenuButton icon={<Settings2 className="h-4 w-4" />} label="관리자" onClick={() => navigate('/admin')} />
              )}
              <div className="my-1 h-px bg-border" />
              <MenuButton icon={<LogOut className="h-4 w-4" />} label="로그아웃" onClick={logout} destructive />
            </PopoverContent>
          </Popover>
        </div>
      </aside>

      {!sidebarOpen && (
        <div className="shrink-0 flex items-start pt-3 pl-3">
          <Button variant="ghost" size="icon" onClick={() => handleSidebarToggle(true)}
            className="h-8 w-8 bg-background/80 backdrop-blur shadow-sm border" title="메뉴 열기">
            <PanelLeft className="h-4 w-4" />
          </Button>
        </div>
      )}
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

function MenuButton({ icon, label, onClick, destructive }: {
  icon: React.ReactNode; label: string; onClick: () => void; destructive?: boolean;
}) {
  return (
    <button
      onClick={onClick}
      className={`flex items-center gap-2.5 w-full px-2.5 py-2 text-sm rounded-md transition-colors cursor-pointer ${
        destructive
          ? 'text-destructive hover:bg-destructive/10'
          : 'text-foreground hover:bg-accent'
      }`}
    >
      {icon}
      {label}
    </button>
  );
}

function ThemeMenuItem() {
  const { theme, setTheme } = useTheme();
  const isDark = theme === 'dark';

  return (
    <button
      onClick={() => setTheme(isDark ? 'light' : 'dark')}
      className="flex items-center gap-2.5 w-full px-2.5 py-2 text-sm rounded-md transition-colors text-foreground hover:bg-accent cursor-pointer"
    >
      {isDark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
      {isDark ? '라이트 모드' : '다크 모드'}
    </button>
  );
}
