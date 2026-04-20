"use client";

import { useEffect } from "react";
import { Sidebar } from "@/components/layouts/Sidebar";
import { useFleetStore } from "@/store/fleetStore";
import { useFleetSSE } from "@/hooks/useFleetSSE";
import { fetchRobots } from "@/services/api";

/** 대시보드 공통 레이아웃: Sidebar + 실시간 SSE 구독 + REST 초기 로딩 */
export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const initFromRest = useFleetStore((s) => s.initFromRest);

  // REST 초기 로딩
  useEffect(() => {
    fetchRobots()
      .then(initFromRest)
      .catch(() => {
        // API 미기동 시 빈 상태로 시작, SSE 수신 시 자동 채워짐
      });
  }, [initFromRest]);

  // SSE 실시간 구독
  useFleetSSE();

  return (
    <div className="flex h-full">
      <Sidebar />
      <main className="flex-1 overflow-y-auto p-6">{children}</main>
    </div>
  );
}
