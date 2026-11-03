"use client";

import { useEffect, useRef } from "react";
import { fetchRobots } from "@/services/api";
import { useFleetStore } from "@/store/fleetStore";
import type { TelemetryEvent } from "@/types";

/** 실시간 관측을 구독하고 단절 후 연결과 REST 스냅샷을 복구한다. */
export function useFleetSSE() {
  const upsertFromSSE = useFleetStore((s) => s.upsertFromSSE);
  const setRealtimeState = useFleetStore((s) => s.setRealtimeState);
  const esRef = useRef<EventSource | null>(null);

  useEffect(() => {
    let active = true;
    let recovery: AbortController | null = null;
    let retry: ReturnType<typeof setTimeout> | undefined;
    let snapshotRetry: ReturnType<typeof setTimeout> | undefined;
    const apiBase = (process.env.NEXT_PUBLIC_API_URL ?? "").replace(/\/$/, "");

    const connect = () => {
      if (!active) return;
      const es = new EventSource(`${apiBase}/api/stream/telemetry`);
      esRef.current = es;
      setRealtimeState("connecting");
      let observedSinceRequest = new Set<string>();
      const currentSource = () => active && esRef.current === es;

      const recoverSnapshot = () => {
        if (!currentSource()) return;
        clearTimeout(snapshotRetry);
        recovery?.abort();
        recovery = new AbortController();
        const current = recovery;
        observedSinceRequest = new Set<string>();
        const observed = observedSinceRequest;
        fetchRobots(current.signal).then((robots) => {
          if (currentSource() && !current.signal.aborted) {
            useFleetStore.getState().initFromRest(robots, observed);
          }
        }).catch(() => {
          if (currentSource() && !current.signal.aborted) {
            snapshotRetry = setTimeout(recoverSnapshot, 2000);
          }
        });
      };

      es.onopen = () => {
        if (!currentSource()) return;
        setRealtimeState("live");
        recoverSnapshot();
      };

      es.addEventListener("robot_update", (e: MessageEvent) => {
        if (!currentSource()) return;
        try {
          const event: TelemetryEvent = JSON.parse(e.data);
          if (upsertFromSSE(event)) observedSinceRequest.add(event.robotId);
        } catch (error) {
          const message = error instanceof Error ? error.message : "unknown parse error";
          console.warn("[SSE] robot_update parse failed", message);
          setRealtimeState("error", message);
        }
      });

      es.onerror = () => {
        if (!currentSource()) return;
        recovery?.abort();
        clearTimeout(snapshotRetry);
        es.close();
        esRef.current = null;
        setRealtimeState("error", "SSE connection error");
        retry = setTimeout(connect, 2000);
      };
    };

    connect();
    return () => {
      active = false;
      clearTimeout(retry);
      clearTimeout(snapshotRetry);
      recovery?.abort();
      esRef.current?.close();
      esRef.current = null;
    };
  }, [setRealtimeState, upsertFromSSE]);
}
