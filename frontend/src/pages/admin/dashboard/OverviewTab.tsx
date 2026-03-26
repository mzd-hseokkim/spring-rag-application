import { useState, useEffect } from 'react';
import { Card } from '@/components/ui/card';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, PieChart, Pie, Cell } from 'recharts';
import { dashboardApi } from '@/api/dashboard';

const COLORS = ['#011936', '#465362', '#ED254E', '#4A90A4'];

export function OverviewTab() {
  const [overview, setOverview] = useState<Record<string, number>>({});
  const [chatTrend, setChatTrend] = useState<{ date: string; count: number }[]>([]);
  const [agentDist, setAgentDist] = useState<{ action: string; count: number }[]>([]);

  useEffect(() => {
    dashboardApi.overview().then(setOverview).catch(() => {});
    dashboardApi.chatTrend(30).then(setChatTrend).catch(() => {});
    dashboardApi.agentDistribution().then(setAgentDist).catch(() => {});
  }, []);

  const kpis = [
    { label: '오늘 채팅', value: overview.chatToday ?? 0 },
    { label: '이번 주 채팅', value: overview.chatWeek ?? 0 },
    { label: '총 사용자', value: overview.totalUsers ?? 0 },
    { label: '평균 응답시간', value: `${((overview.avgLatencyMs ?? 0) / 1000).toFixed(1)}초` },
  ];

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-4 gap-4">
        {kpis.map(kpi => (
          <Card key={kpi.label} className="p-4">
            <p className="text-xs text-muted-foreground">{kpi.label}</p>
            <p className="text-2xl font-bold mt-1">{kpi.value}</p>
          </Card>
        ))}
      </div>

      <div className="grid grid-cols-3 gap-4">
        <Card className="col-span-2 p-4">
          <h3 className="text-sm font-medium mb-3">일별 채팅 요청 (최근 30일)</h3>
          <ResponsiveContainer width="100%" height={250}>
            <LineChart data={chatTrend}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="date" tick={{ fontSize: 11 }} />
              <YAxis tick={{ fontSize: 11 }} />
              <Tooltip />
              <Line type="monotone" dataKey="count" stroke="#011936" strokeWidth={2} dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </Card>

        <Card className="p-4">
          <h3 className="text-sm font-medium mb-3">에이전트 결정 분포</h3>
          {agentDist.length > 0 ? (
            <ResponsiveContainer width="100%" height={250}>
              <PieChart>
                <Pie data={agentDist} dataKey="count" nameKey="action" cx="50%" cy="50%"
                     outerRadius={80} label={({ name, percent }) => `${name} ${((percent ?? 0) * 100).toFixed(0)}%`}>
                  {agentDist.map((_, i) => <Cell key={i} fill={COLORS[i % COLORS.length]} />)}
                </Pie>
                <Tooltip />
              </PieChart>
            </ResponsiveContainer>
          ) : (
            <p className="text-sm text-muted-foreground text-center py-16">데이터 없음</p>
          )}
        </Card>
      </div>
    </div>
  );
}
