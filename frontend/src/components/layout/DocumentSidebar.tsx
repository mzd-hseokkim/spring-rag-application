import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { PanelRightClose, PanelRight } from 'lucide-react';

interface DocumentSidebarProps {
  children?: React.ReactNode;
}

export function DocumentSidebar({ children }: DocumentSidebarProps) {
  const [open, setOpen] = useState(() => localStorage.getItem('doc-sidebar:open') !== 'false');

  const handleToggle = (value: boolean) => {
    setOpen(value);
    localStorage.setItem('doc-sidebar:open', String(value));
  };

  return (
    <>
      {!open && (
        <div className="shrink-0 flex items-start pt-3 pr-3">
          <Button variant="ghost" size="icon" onClick={() => handleToggle(true)}
            className="h-8 w-8 bg-background/80 backdrop-blur shadow-sm border" title="문서 패널 열기">
            <PanelRight className="h-4 w-4" />
          </Button>
        </div>
      )}

      <aside className={`bg-sidebar text-sidebar-foreground border-l border-sidebar-border flex flex-col shrink-0 transition-all duration-300 ease-in-out overflow-hidden ${open ? 'w-80' : 'w-0 border-l-0'}`}>
        {/* Header */}
        <div className="flex items-center gap-2 px-3 pt-3 pb-1">
          <span className="text-sm font-medium flex-1">문서</span>
          <Button variant="ghost" size="icon" onClick={() => handleToggle(false)}
            className="shrink-0 h-8 w-8 text-sidebar-foreground/60 hover:text-sidebar-foreground hover:bg-sidebar-accent/50" title="문서 패널 닫기">
            <PanelRightClose className="h-4 w-4" />
          </Button>
        </div>

        <div className="mx-3 border-t border-sidebar-border" />

        {/* Content */}
        <div className="flex-1 overflow-y-auto p-3 space-y-3">
          {children}
        </div>
      </aside>
    </>
  );
}
