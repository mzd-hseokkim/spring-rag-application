import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { useAuth } from '@/auth/AuthContext';
import { updateProfile, changePassword } from '@/api/auth';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardHeader, CardTitle, CardContent } from '@/components/ui/card';
import { ArrowLeft, User as UserIcon } from 'lucide-react';

export function SettingsPage() {
  const { user, updateUser } = useAuth();
  const navigate = useNavigate();

  const [name, setName] = useState(user?.name || '');
  const [avatarUrl, setAvatarUrl] = useState(user?.avatarUrl || '');
  const [saving, setSaving] = useState(false);

  const [currentPw, setCurrentPw] = useState('');
  const [newPw, setNewPw] = useState('');
  const [confirmPw, setConfirmPw] = useState('');
  const [changingPw, setChangingPw] = useState(false);

  const handleProfileSave = async () => {
    setSaving(true);
    try {
      const updated = await updateProfile(name, avatarUrl || null);
      updateUser(updated);
      toast.success('프로필이 저장되었습니다.');
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '프로필 저장 실패');
    } finally {
      setSaving(false);
    }
  };

  const handlePasswordChange = async () => {
    if (newPw !== confirmPw) {
      toast.error('새 비밀번호가 일치하지 않습니다.');
      return;
    }
    setChangingPw(true);
    try {
      await changePassword(currentPw, newPw);
      toast.success('비밀번호가 변경되었습니다.');
      setCurrentPw('');
      setNewPw('');
      setConfirmPw('');
    } catch (err) {
      toast.error(err instanceof Error ? err.message : '비밀번호 변경 실패');
    } finally {
      setChangingPw(false);
    }
  };

  return (
    <div className="min-h-screen bg-background">
      <div className="max-w-lg mx-auto p-6 space-y-6 animate-page-in">
        <button
          onClick={() => navigate('/')}
          className="flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground transition-colors"
        >
          <ArrowLeft className="h-4 w-4" />
          돌아가기
        </button>

        <h1 className="text-xl font-semibold">개인설정</h1>

        {/* 프로필 */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base">프로필</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="flex items-center gap-4">
              <div className="w-16 h-16 rounded-full bg-muted flex items-center justify-center overflow-hidden shrink-0">
                {avatarUrl ? (
                  <img src={avatarUrl} alt="avatar" className="w-full h-full object-cover"
                    onError={e => { (e.target as HTMLImageElement).style.display = 'none'; e.target.parentElement?.querySelector('.avatar-fallback')?.classList.remove('hidden'); }} />
                ) : null}
                <UserIcon className={`size-8 text-muted-foreground avatar-fallback ${avatarUrl ? 'hidden' : ''}`} />
              </div>
              <div className="flex-1 space-y-1">
                <p className="text-sm text-muted-foreground">{user?.email}</p>
                <p className="text-xs text-muted-foreground">
                  {user?.role === 'ADMIN' ? '관리자' : '일반 사용자'}
                </p>
              </div>
            </div>

            <div className="space-y-2">
              <label className="text-sm font-medium">이름</label>
              <Input value={name} onChange={e => setName(e.target.value)} />
            </div>

            <div className="space-y-2">
              <label className="text-sm font-medium">프로필 이미지 URL</label>
              <Input
                value={avatarUrl}
                onChange={e => setAvatarUrl(e.target.value)}
                placeholder="https://example.com/avatar.jpg"
              />
              <p className="text-xs text-muted-foreground">외부 이미지 URL을 입력하세요.</p>
            </div>

            <Button onClick={handleProfileSave} disabled={saving || !name.trim()}>
              {saving ? '저장 중...' : '프로필 저장'}
            </Button>
          </CardContent>
        </Card>

        {/* 비밀번호 변경 */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base">비밀번호 변경</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="space-y-2">
              <label className="text-sm font-medium">현재 비밀번호</label>
              <Input type="password" value={currentPw} onChange={e => setCurrentPw(e.target.value)} />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">새 비밀번호</label>
              <Input type="password" value={newPw} onChange={e => setNewPw(e.target.value)}
                placeholder="8자 이상" />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">새 비밀번호 확인</label>
              <Input type="password" value={confirmPw} onChange={e => setConfirmPw(e.target.value)} />
            </div>
            <Button onClick={handlePasswordChange}
              disabled={changingPw || !currentPw || !newPw || !confirmPw}>
              {changingPw ? '변경 중...' : '비밀번호 변경'}
            </Button>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
