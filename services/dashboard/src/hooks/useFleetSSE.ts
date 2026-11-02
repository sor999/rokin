"use client";

import { useEffect, useRef } from "react";
import { fetchRobots } from "@/services/api";
import { useFleetStore } from "@/store/fleetStore";
import type { TelemetryEvent } from "@/types";

/**
 * SSE를 통한 실시간 텔레메트리 구독.
 * /api/stream/telemetry의 robot_update 이벤트를 수신하여 전역 스토어에 반영한다.
 */
export function useFleetSSE() {
  const upsertFromSSE = useFleetStore((s) => s.upsertFromSSE);
  const setRealtimeState = useFleetStore((s) => s.setRealtimeState);
  const esRef = useRef<EventSource | null>(null);

  useEffect(() => {
    let active = true;
    let recovery: AbortController | null = null;
    const apiBase = (process.env.NEXT_PUBLIC_API_URL ?? "").replace(/\/$/, "");
    const es = new EventSource(`${apiBase}/api/stream/telemetry`);
    esRef.current = es;
    setRealtimeState("connecting");

    es.onopen = () => {
      if (!active) return;
      setRealtimeState("live");
      recovery?.abort();
      recovery = new AbortController();
      const current = recovery;
      fetchRobots(current.signal).then((robots) => {
        if (active && !current.signal.aborted) useFleetStore.getState().initFromRest(robots);
      }).catch(() => { /* 다음 재연결에서 스냅샷을 다시 조회한다. */ });
    };

    es.addEventListener("robot_update", (e: MessageEvent) => {
      if (!active) return;
      try {
        const event: TelemetryEvent = JSON.parse(e.data);
        upsertFromSSE(event);
      } catch (error) {
        const message = error instanceof Error ? error.message : "unknown parse error";
        console.warn("[SSE] robot_update parse failed", message, e.data);
        setRealtimeState("error", message);
      }
    });

    es.onerror = () => {
      if (!active) return;
      recovery?.abort();
      setRealtimeState("error", "SSE connection error");
    };

    return () => {
      active = false;
      recovery?.abort();
      es.close();
      esRef.current = null;
    };
  }, [setRealtimeState, upsertFromSSE]);
}
