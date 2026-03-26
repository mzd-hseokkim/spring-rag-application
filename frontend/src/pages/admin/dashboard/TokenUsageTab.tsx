import { useState, useEffect } from 'react';
import { Card } from '@/components/ui/card';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, PieChart, Pie, Cell, Legend } from 'recharts';
import { dashboardApi } from '@/api/dashboard';

const COLORS = ['#011936', '#465362', '#ED254E', '#4A90A4', '#7B8794', '#D64045'];

export function TokenUsageTab() {
  const [days, setDays] = useState(30);
  const [trend, setTrend] = useState<{ date: string; inputTokens: number; outputTokens: number }[]>([]);
  const [byUser, setByUser] = useState<{ email: string; name: string; inputTokens: number; outputTokens: number; requestCount: number }[]>([]);
  const [byModel, setByModel] = useState<{ modelName: string; purpose: string; inputTokens: number; outputTokens: number; requestCount: number }[]>([]);

  useEffect(() => {
    dashboardApi.tokenTrend(days).then(setTrend).catch(() => {});
    dashboardApi.tokenByUser(days).then(setByUser).catch(() => {});
    dashboardApi.tokenByModel(days).then(setByModel).catch(() => {});
  }, [days]);

  const modelPieData = byModel.map(m => ({
    name: `${m.modelName} (${m.purpose})`,
    value: m.inputTokens + m.outputTokens,
  }));

  return (
    <div className="space-y-6">
      <div className="flex gap-2">
        {[7, 30, 90].map(d => (
          <button key={d} onClick={() => setDays(d)}
            className={`px-3 py-1 text-xs rounded-md border ${days === d ? 'bg-primary text-primary-foreground' : 'hover:bg-muted'}`}>
            {d}일
          </button>
        ))}
      </div>

      <div className="grid grid-cols-3 gap-4">
        <Card className="col-span-2 p-4">
          <h3 className="text-sm font-medium mb-3">일별 토큰 사용량</h3>
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
          <h3 className="text-sm font-medium mb-3">모델별 토큰 비율</h3>
          {modelPieData.length > 0 ? (
            <ResponsiveContainer width="100%" height={250}>
              <PieChart>
                <Pie data={modelPieData} dataKey="value" nameKey="name" cx="50%" cy="50%" outerRadius={80}
                     label={({ percent }) => `${((percent ?? 0) * 100).toFixed(0)}%`}>
                  {modelPieData.map((_, i) => <Cell key={i} fill={COLORS[i % COLORS.length]} />)}
                </Pie>
                <Tooltip />
              </PieChart>
            </ResponsiveContainer>
          ) : (
            <p className="text-sm text-muted-foreground text-center py-16">데이터 없음</p>
          )}
        </Card>
      </div>

      <Card className="p-4">
        <h3 className="text-sm font-medium mb-3">사용자별 토큰 사용량</h3>
        <table className="w-full text-sm">
          <thead className="bg-muted/50">
            <tr>
              <th className="text-left px-4 py-2 font-medium">사용자</th>
              <th className="text-right px-4 py-2 font-medium">입력 토큰</th>
              <th className="text-right px-4 py-2 font-medium">출력 토큰</th>
              <th className="text-right px-4 py-2 font-medium">합계</th>
              <th className="text-right px-4 py-2 font-medium">요청 수</th>
            </tr>
          </thead>
          <tbody>
            {byUser.map(u => (
              <tr key={u.email} className="border-t hover:bg-muted/30">
                <td className="px-4 py-2">{u.name} <span className="text-muted-foreground text-xs">({u.email})</span></td>
                <td className="px-4 py-2 text-right text-muted-foreground">{u.inputTokens.toLocaleString()}</td>
                <td className="px-4 py-2 text-right text-muted-foreground">{u.outputTokens.toLocaleString()}</td>
                <td className="px-4 py-2 text-right font-medium">{(u.inputTokens + u.outputTokens).toLocaleString()}</td>
                <td className="px-4 py-2 text-right text-muted-foreground">{u.requestCount}</td>
              </tr>
            ))}
            {byUser.length === 0 && (
              <tr><td colSpan={5} className="text-center py-8 text-muted-foreground">데이터 없음</td></tr>
            )}
          </tbody>
        </table>
      </Card>

      <Card className="p-4">
        <h3 className="text-sm font-medium mb-3">모델별 토큰 사용량</h3>
        <table className="w-full text-sm">
          <thead className="bg-muted/50">
            <tr>
              <th className="text-left px-4 py-2 font-medium">모델</th>
              <th className="text-left px-4 py-2 font-medium">용도</th>
              <th className="text-right px-4 py-2 font-medium">입력 토큰</th>
              <th className="text-right px-4 py-2 font-medium">출력 토큰</th>
              <th className="text-right px-4 py-2 font-medium">요청 수</th>
            </tr>
          </thead>
          <tbody>
            {byModel.map((m, i) => (
              <tr key={i} className="border-t hover:bg-muted/30">
                <td className="px-4 py-2">{m.modelName}</td>
                <td className="px-4 py-2 text-muted-foreground">{m.purpose}</td>
                <td className="px-4 py-2 text-right text-muted-foreground">{m.inputTokens.toLocaleString()}</td>
                <td className="px-4 py-2 text-right text-muted-foreground">{m.outputTokens.toLocaleString()}</td>
                <td className="px-4 py-2 text-right text-muted-foreground">{m.requestCount}</td>
              </tr>
            ))}
            {byModel.length === 0 && (
              <tr><td colSpan={5} className="text-center py-8 text-muted-foreground">데이터 없음</td></tr>
            )}
          </tbody>
        </table>
      </Card>
    </div>
  );
}
