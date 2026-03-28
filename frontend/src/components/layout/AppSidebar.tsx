import { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '@/auth/AuthContext';
import { useTheme } from 'next-themes';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import {
  PanelLeftClose, PanelLeft, MoreHorizontal, LogOut, UserCog, Settings2,
  Moon, Sun, MessageSquare, FileText, ClipboardList,
} from 'lucide-react';

const SERVICES = [
  { path: '/', icon: MessageSquare, label: '채팅' },
  { path: '/generate', icon: FileText, label: '문서 생성' },
  { path: '/questionnaire', icon: ClipboardList, label: '예상 질의서' },
];

interface AppSidebarProps {
  children?: React.ReactNode;
}

export function AppSidebar({ children }: AppSidebarProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout } = useAuth();
  const [open, setOpen] = useState(() => localStorage.getItem('sidebar:open') !== 'false');

  const handleToggle = (value: boolean) => {
    setOpen(value);
    localStorage.setItem('sidebar:open', String(value));
  };

  const isAdmin = user?.role === 'ADMIN';

  return (
    <>
      <aside className={`bg-sidebar text-sidebar-foreground border-r border-sidebar-border flex flex-col shrink-0 transition-all duration-300 ease-in-out overflow-hidden ${open ? 'w-80' : 'w-0 border-r-0'}`}>
        {/* Toggle */}
        <div className="flex items-center gap-2 px-3 pt-3 pb-1">
          <Button variant="ghost" size="icon" onClick={() => handleToggle(false)}
            className="shrink-0 h-8 w-8 text-sidebar-foreground/60 hover:text-sidebar-foreground hover:bg-sidebar-accent/50" title="메뉴 닫기">
            <PanelLeftClose className="h-4 w-4" />
          </Button>
        </div>

        {/* Service nav */}
        <nav className="px-3 pb-2 space-y-0.5">
          {SERVICES.map((s) => {
            const active = s.path === '/' ? location.pathname === '/' : location.pathname.startsWith(s.path);
            return (
              <button
                key={s.path}
                onClick={() => navigate(s.path)}
                className={`flex items-center gap-2.5 w-full px-2.5 py-2 rounded-md text-sm transition-colors cursor-pointer ${
                  active
                    ? 'bg-sidebar-accent text-sidebar-foreground font-medium'
                    : 'text-sidebar-foreground/60 hover:text-sidebar-foreground hover:bg-sidebar-accent/50'
                }`}
              >
                <s.icon className="h-4 w-4 shrink-0" />
                {s.label}
              </button>
            );
          })}
        </nav>

        <div className="mx-3 border-t border-sidebar-border" />

        {/* Page-specific sub-content */}
        {children && (
          <div className="flex-1 flex flex-col min-h-0 overflow-hidden">
            {children}
          </div>
        )}

        {/* User footer */}
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
              <FooterMenuButton icon={<UserCog className="h-4 w-4" />} label="개인설정" onClick={() => navigate('/settings')} />
              <ThemeMenuItem />
              {isAdmin && (
                <FooterMenuButton icon={<Settings2 className="h-4 w-4" />} label="관리자" onClick={() => navigate('/admin')} />
              )}
              <div className="my-1 h-px bg-border" />
              <FooterMenuButton icon={<LogOut className="h-4 w-4" />} label="로그아웃" onClick={logout} destructive />
            </PopoverContent>
          </Popover>
        </div>
      </aside>

      {!open && (
        <div className="shrink-0 flex items-start pt-3 pl-3">
          <Button variant="ghost" size="icon" onClick={() => handleToggle(true)}
            className="h-8 w-8 bg-background/80 backdrop-blur shadow-sm border" title="메뉴 열기">
            <PanelLeft className="h-4 w-4" />
          </Button>
        </div>
      )}
    </>
  );
}

function FooterMenuButton({ icon, label, onClick, destructive }: {
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
