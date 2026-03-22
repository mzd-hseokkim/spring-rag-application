import { NavLink, Link } from 'react-router-dom';
import { ArrowLeft, Users, FileText, MessageSquare, Settings2, BarChart3, FlaskConical } from 'lucide-react';
import { useAuth } from '@/auth/AuthContext';
import { Button } from '@/components/ui/button';
import { LogOut } from 'lucide-react';
import { ThemeToggle } from '@/components/ThemeToggle';

const navItems = [
  { to: '/admin/dashboard', label: '대시보드', icon: BarChart3 },
  { to: '/admin/eval', label: '성능 평가', icon: FlaskConical },
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
          className="flex items-center gap-2 text-sm text-sidebar-foreground/60 hover:text-sidebar-foreground transition-colors px-2 py-1.5 rounded-md hover:bg-sidebar-accent/50"
        >
          <ArrowLeft className="h-4 w-4" />
          채팅으로 돌아가기
        </Link>
      </div>

      <div className="px-5 py-2">
        <h2 className="text-xs font-semibold uppercase tracking-wider text-sidebar-foreground/40">관리</h2>
      </div>

      <nav className="flex-1 px-3 space-y-0.5">
        {navItems.map(item => (
          <NavLink
            key={item.to}
            to={item.to}
            className={({ isActive }) =>
              `flex items-center gap-2.5 px-3 py-2 rounded-lg text-sm transition-colors ${
                isActive
                  ? 'bg-sidebar-accent text-sidebar-accent-foreground font-medium'
                  : 'text-sidebar-foreground/60 hover:bg-sidebar-accent/50 hover:text-sidebar-foreground'
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
          <p className="text-xs text-sidebar-foreground/40 truncate">{user?.email}</p>
        </div>
        <ThemeToggle />
        <Button variant="ghost" size="icon" onClick={logout} className="shrink-0 text-sidebar-foreground/60 hover:text-sidebar-foreground hover:bg-sidebar-accent/50">
          <LogOut className="h-4 w-4" />
        </Button>
      </div>
    </aside>
  );
}
