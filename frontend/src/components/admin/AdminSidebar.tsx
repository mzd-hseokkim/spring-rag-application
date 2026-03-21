import { NavLink, Link } from 'react-router-dom';
import { ArrowLeft, Users, FileText, MessageSquare, Settings2 } from 'lucide-react';
import { useAuth } from '@/auth/AuthContext';
import { Button } from '@/components/ui/button';
import { LogOut } from 'lucide-react';

const navItems = [
  { to: '/admin/users', label: '사용자 관리', icon: Users },
  { to: '/admin/documents', label: '문서 관리', icon: FileText },
  { to: '/admin/conversations', label: '대화 관리', icon: MessageSquare },
  { to: '/admin/models', label: '모델 관리', icon: Settings2 },
];

export function AdminSidebar() {
  const { user, logout } = useAuth();

  return (
    <aside className="w-80 bg-sidebar text-sidebar-foreground border-r border-sidebar-border flex flex-col">
      <div className="p-3">
        <Link
          to="/"
          className="flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground transition-colors px-2 py-1.5 rounded-md hover:bg-sidebar-accent/50"
        >
          <ArrowLeft className="h-4 w-4" />
          채팅으로 돌아가기
        </Link>
      </div>

      <div className="px-5 py-2">
        <h2 className="text-sm font-semibold text-sidebar-foreground">관리</h2>
      </div>

      <nav className="flex-1 px-3 space-y-0.5">
        {navItems.map(item => (
          <NavLink
            key={item.to}
            to={item.to}
            className={({ isActive }) =>
              `flex items-center gap-2.5 px-2 py-2 rounded-md text-sm transition-colors ${
                isActive
                  ? 'bg-sidebar-accent text-sidebar-accent-foreground font-medium'
                  : 'text-muted-foreground hover:bg-sidebar-accent/50 hover:text-sidebar-accent-foreground'
              }`
            }
          >
            <item.icon className="h-4 w-4" />
            {item.label}
          </NavLink>
        ))}
      </nav>

      <div className="border-t border-sidebar-border px-3 h-16 flex items-center gap-2">
        <div className="flex-1 min-w-0">
          <p className="text-sm font-medium truncate">{user?.name}</p>
          <p className="text-xs text-muted-foreground truncate">{user?.email}</p>
        </div>
        <Button variant="ghost" size="icon" onClick={logout} className="shrink-0">
          <LogOut className="h-4 w-4" />
        </Button>
      </div>
    </aside>
  );
}
