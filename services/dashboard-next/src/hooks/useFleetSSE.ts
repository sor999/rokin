"use client";

import { useEffect, useRef } from "react";
import { useFleetStore } from "@/store/fleetStore";
import type { TelemetryEvent } from "@/types";

/**
 * SSE를 통한 실시간 텔레메트리 구독.
 * /api/stream/telemetry의 robot_update 이벤트를 수신하여 전역 스토어에 반영한다.
 */
export function useFleetSSE() {
  const upsertFromSSE = useFleetStore((s) => s.upsertFromSSE);
  const esRef = useRef<EventSource | null>(null);

  useEffect(() => {
    const base = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
    const es = new EventSource(`${base}/api/stream/telemetry`);
    esRef.current = es;

    es.addEventListener("robot_update", (e: MessageEvent) => {
      try {
        const event: TelemetryEvent = JSON.parse(e.data);
        upsertFromSSE(event);
      } catch {
        // 파싱 실패 시 무시
      }
    });

    es.onerror = () => {
      // 브라우저가 자동 재연결을 처리함
    };

    return () => {
      es.close();
      esRef.current = null;
    };
  }, [upsertFromSSE]);
}
