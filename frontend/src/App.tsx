import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { ThemeProvider } from 'next-themes';
import { Toaster } from '@/components/ui/sonner';
import { TooltipProvider } from '@/components/ui/tooltip';
import { AuthProvider } from '@/auth/AuthContext';
import { ProtectedRoute } from '@/auth/ProtectedRoute';
import { ProtectedAdminRoute } from '@/auth/ProtectedAdminRoute';
import { LoginPage } from '@/pages/LoginPage';
import { RegisterPage } from '@/pages/RegisterPage';
import { MainPage } from '@/pages/MainPage';
import { AdminLayout } from '@/pages/admin/AdminLayout';
import { AdminUsersPage } from '@/pages/admin/AdminUsersPage';
import { AdminDocumentsPage } from '@/pages/admin/AdminDocumentsPage';
import { AdminConversationsPage } from '@/pages/admin/AdminConversationsPage';
import { AdminModelsPage } from '@/pages/admin/AdminModelsPage';
import { AdminDashboardPage } from '@/pages/admin/AdminDashboardPage';
import { AdminEvalPage } from '@/pages/admin/AdminEvalPage';
import { AdminAuditPage } from '@/pages/admin/AdminAuditPage';

function App() {
  return (
    <ThemeProvider attribute="class" defaultTheme="light" enableSystem={false}>
    <BrowserRouter>
      <AuthProvider>
        <TooltipProvider>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage />} />
            <Route path="/" element={
              <ProtectedRoute>
                <MainPage />
              </ProtectedRoute>
            } />
            <Route path="/admin" element={
              <ProtectedAdminRoute>
                <AdminLayout />
              </ProtectedAdminRoute>
            }>
              <Route index element={<Navigate to="dashboard" replace />} />
              <Route path="dashboard" element={<AdminDashboardPage />} />
              <Route path="eval" element={<AdminEvalPage />} />
              <Route path="users" element={<AdminUsersPage />} />
              <Route path="documents" element={<AdminDocumentsPage />} />
              <Route path="conversations" element={<AdminConversationsPage />} />
              <Route path="models" element={<AdminModelsPage />} />
              <Route path="audit" element={<AdminAuditPage />} />
            </Route>
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </TooltipProvider>
        <Toaster richColors position="top-right" />
      </AuthProvider>
    </BrowserRouter>
    </ThemeProvider>
  );
}

export default App;
