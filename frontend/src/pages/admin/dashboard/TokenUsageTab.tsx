import { useState, useEffect } from 'react';
import { Card } from '@/components/ui/card';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, PieChart, Pie, Cell, Legend, BarChart, Bar } from 'recharts';
import { dashboardApi } from '@/api/dashboard';

const COLORS = ['#011936', '#465362', '#ED254E', '#4A90A4', '#7B8794', '#D64045'];

const PURPOSE_LABELS: Record<string, string> = {
  CHAT: '채팅',
  GENERATION: '문서 생성',
  QUESTIONNAIRE: '질문 생성',
  EVALUATION: '평가',
};

interface CostRow {
  modelName: string;
  purpose: string;
  inputTokens: number;
  outputTokens: number;
  requestCount: number;
  estimatedCost: number;
  currency: string;
}

export function TokenUsageTab() {
  const [days, setDays] = useState(30);
  const [purpose, setPurpose] = useState<string>('');
  const [trend, setTrend] = useState<{ date: string; inputTokens: number; outputTokens: number }[]>([]);
  const [byUser, setByUser] = useState<{ email: string; name: string; inputTokens: number; outputTokens: number; requestCount: number }[]>([]);
  const [byPurpose, setByPurpose] = useState<{ purpose: string; inputTokens: number; outputTokens: number; requestCount: number }[]>([]);
  const [costData, setCostData] = useState<CostRow[]>([]);
  const [userCost, setUserCost] = useState<{ email: string; name: string; inputTokens: number; outputTokens: number; estimatedCost: number }[]>([]);

  useEffect(() => {
    const p = purpose || undefined;
    dashboardApi.tokenTrend(days, p).then(setTrend).catch(() => {});
    dashboardApi.tokenByUser(days, p).then(setByUser).catch(() => {});
    dashboardApi.tokenByPurpose(days).then(setByPurpose).catch(() => {});
    dashboardApi.tokenCost(days).then(setCostData).catch(() => {});
    dashboardApi.tokenCostByUser(days).then(setUserCost).catch(() => {});
  }, [days, purpose]);

  const purposePieData = byPurpose.map(p => ({
    name: PURPOSE_LABELS[p.purpose] ?? p.purpose,
    value: p.inputTokens + p.outputTokens,
  }));

  const totalCost = costData.reduce((sum, r) => sum + r.estimatedCost, 0);

  return (
    <div className="space-y-6">
      <div className="flex gap-4 items-center">
        <div className="flex gap-2">
          {[7, 30, 90].map(d => (
            <button key={d} onClick={() => setDays(d)}
              className={`px-3 py-1 text-xs rounded-md border ${days === d ? 'bg-primary text-primary-foreground' : 'hover:bg-muted'}`}>
              {d}일
            </button>
          ))}
        </div>
        <select value={purpose} onChange={e => setPurpose(e.target.value)}
          className="px-3 py-1 text-xs rounded-md border bg-background">
          <option value="">전체 용도</option>
          <option value="CHAT">채팅</option>
          <option value="GENERATION">문서 생성</option>
          <option value="QUESTIONNAIRE">질문 생성</option>
          <option value="EVALUATION">평가</option>
        </select>
        {totalCost > 0 && (
          <div className="ml-auto text-sm">
            예상 비용: <span className="font-semibold">${totalCost.toFixed(4)}</span>
          </div>
        )}
      </div>

      <div className="grid grid-cols-3 gap-4">
        <Card className="col-span-2 p-4">
          <h3 className="text-sm font-medium mb-3">일별 토큰 사용량{purpose ? ` (${PURPOSE_LABELS[purpose] ?? purpose})` : ''}</h3>
          <ResponsiveContainer width="100%" height={250}>
            <LineChart data={trend}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="date" tick={{ fontSize: 11 }} />
              <YAxis tick={{ fontSize: 11 }} />
              <Tooltip />
              <Legend />
              <Line type="monotone" dataKey="inputTokens" name="입력" stroke="#011936" strokeWidth={2} dot={false} />
              <Line type="monotone" dataKey="outputTokens" name="출력" stroke="#ED254E" strokeWidth={2} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </Card>

        <Card className="p-4">
          <h3 className="text-sm font-medium mb-3">용도별 토큰 비율</h3>
          {purposePieData.length > 0 ? (
            <ResponsiveContainer width="100%" height={250}>
              <PieChart>
                <Pie data={purposePieData} dataKey="value" nameKey="name" cx="50%" cy="50%" outerRadius={80}
                     label={({ name, percent }) => `${name} ${((percent ?? 0) * 100).toFixed(0)}%`}>
                  {purposePieData.map((_, i) => <Cell key={i} fill={COLORS[i % COLORS.length]} />)}
                </Pie>
                <Tooltip />
              </PieChart>
            </ResponsiveContainer>
          ) : (
            <p className="text-sm text-muted-foreground text-center py-16">데이터 없음</p>
          )}
        </Card>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <Card className="p-4">
          <h3 className="text-sm font-medium mb-3">사용자별 토큰 사용량</h3>
          {byUser.length > 0 ? (
            <ResponsiveContainer width="100%" height={Math.max(200, byUser.length * 40)}>
              <BarChart data={byUser.map(u => ({ name: u.name, 입력: u.inputTokens, 출력: u.outputTokens }))} layout="vertical">
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis type="number" tick={{ fontSize: 11 }} />
                <YAxis type="category" dataKey="name" tick={{ fontSize: 11 }} width={80} />
                <Tooltip formatter={(v: number) => v.toLocaleString()} />
                <Legend />
                <Bar dataKey="입력" stackId="a" fill="#011936" />
                <Bar dataKey="출력" stackId="a" fill="#ED254E" />
              </BarChart>
            </ResponsiveContainer>
          ) : (
            <p className="text-sm text-muted-foreground text-center py-16">데이터 없음</p>
          )}
        </Card>

        <Card className="p-4">
          <h3 className="text-sm font-medium mb-3">사용자별 예상 비용 (USD)</h3>
          {userCost.length > 0 && userCost.some(u => u.estimatedCost > 0) ? (
            <ResponsiveContainer width="100%" height={Math.max(200, userCost.length * 40)}>
              <BarChart data={userCost.map(u => ({ name: u.name, 비용: u.estimatedCost }))} layout="vertical">
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis type="number" tick={{ fontSize: 11 }} tickFormatter={v => `$${v}`} />
                <YAxis type="category" dataKey="name" tick={{ fontSize: 11 }} width={80} />
                <Tooltip formatter={(v: number) => `$${v.toFixed(4)}`} />
                <Bar dataKey="비용" fill="#4A90A4" />
              </BarChart>
            </ResponsiveContainer>
          ) : (
            <p className="text-sm text-muted-foreground text-center py-16">
              {userCost.length === 0 ? '데이터 없음' : '모델 단가를 설정하면 비용이 표시됩니다'}
            </p>
          )}
        </Card>
      </div>

      <Card className="p-4">
        <h3 className="text-sm font-medium mb-3">사용자별 토큰 사용량 상세</h3>
        <table className="w-full text-sm">
          <thead className="bg-muted/50">
            <tr>
              <th className="text-left px-4 py-2 font-medium">사용자</th>
              <th className="text-right px-4 py-2 font-medium">입력 토큰</th>
              <th className="text-right px-4 py-2 font-medium">출력 토큰</th>
              <th className="text-right px-4 py-2 font-medium">합계</th>
              <th className="text-right px-4 py-2 font-medium">요청 수</th>
              <th className="text-right px-4 py-2 font-medium">예상 비용</th>
            </tr>
          </thead>
          <tbody>
            {byUser.map(u => {
              const uc = userCost.find(c => c.email === u.email);
              return (
              <tr key={u.email} className="border-t hover:bg-muted/30">
                <td className="px-4 py-2">{u.name} <span className="text-muted-foreground text-xs">({u.email})</span></td>
                <td className="px-4 py-2 text-right text-muted-foreground">{u.inputTokens.toLocaleString()}</td>
                <td className="px-4 py-2 text-right text-muted-foreground">{u.outputTokens.toLocaleString()}</td>
                <td className="px-4 py-2 text-right font-medium">{(u.inputTokens + u.outputTokens).toLocaleString()}</td>
                <td className="px-4 py-2 text-right text-muted-foreground">{u.requestCount}</td>
                <td className="px-4 py-2 text-right font-medium">
                  {uc && uc.estimatedCost > 0 ? `$${uc.estimatedCost.toFixed(4)}` : <span className="text-muted-foreground">-</span>}
                </td>
              </tr>
              );
            })}
            {byUser.length === 0 && (
              <tr><td colSpan={6} className="text-center py-8 text-muted-foreground">데이터 없음</td></tr>
            )}
          </tbody>
        </table>
      </Card>

      <Card className="p-4">
        <h3 className="text-sm font-medium mb-3">모델별 토큰 사용량 및 비용</h3>
        <table className="w-full text-sm">
          <thead className="bg-muted/50">
            <tr>
              <th className="text-left px-4 py-2 font-medium">모델</th>
              <th className="text-left px-4 py-2 font-medium">용도</th>
              <th className="text-right px-4 py-2 font-medium">입력 토큰</th>
              <th className="text-right px-4 py-2 font-medium">출력 토큰</th>
              <th className="text-right px-4 py-2 font-medium">요청 수</th>
              <th className="text-right px-4 py-2 font-medium">예상 비용</th>
            </tr>
          </thead>
          <tbody>
            {costData.map((m, i) => (
              <tr key={i} className="border-t hover:bg-muted/30">
                <td className="px-4 py-2">{m.modelName}</td>
                <td className="px-4 py-2 text-muted-foreground">{PURPOSE_LABELS[m.purpose] ?? m.purpose}</td>
                <td className="px-4 py-2 text-right text-muted-foreground">{m.inputTokens.toLocaleString()}</td>
                <td className="px-4 py-2 text-right text-muted-foreground">{m.outputTokens.toLocaleString()}</td>
                <td className="px-4 py-2 text-right text-muted-foreground">{m.requestCount}</td>
                <td className="px-4 py-2 text-right font-medium">
                  {m.estimatedCost > 0 ? `$${m.estimatedCost.toFixed(4)}` : <span className="text-muted-foreground">-</span>}
                </td>
              </tr>
            ))}
            {costData.length === 0 && (
              <tr><td colSpan={6} className="text-center py-8 text-muted-foreground">데이터 없음</td></tr>
            )}
          </tbody>
        </table>
      </Card>
    </div>
  );
}
