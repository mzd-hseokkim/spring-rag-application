import { useState } from 'react';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
import { OverviewTab } from './dashboard/OverviewTab';
import { TokenUsageTab } from './dashboard/TokenUsageTab';
import { PipelineTab } from './dashboard/PipelineTab';

export function AdminDashboardPage() {
  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold">대시보드</h1>
      <Tabs defaultValue="overview">
        <TabsList>
          <TabsTrigger value="overview">개요</TabsTrigger>
          <TabsTrigger value="tokens">토큰 사용량</TabsTrigger>
          <TabsTrigger value="pipeline">파이프라인</TabsTrigger>
        </TabsList>
        <TabsContent value="overview" className="mt-4">
          <OverviewTab />
        </TabsContent>
        <TabsContent value="tokens" className="mt-4">
          <TokenUsageTab />
        </TabsContent>
        <TabsContent value="pipeline" className="mt-4">
          <PipelineTab />
        </TabsContent>
      </Tabs>
    </div>
  );
}
