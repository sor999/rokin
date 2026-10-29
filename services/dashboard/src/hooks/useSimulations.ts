"use client";

import { useEffect } from "react";
import { fetchSimulations } from "@/services/api";
import { useFleetStore } from "@/store/fleetStore";

/** 등록 목록을 순차 조회하며 실패 시 마지막 정상 목록을 유지한다. */
export function useSimulations() {
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      try {
        const list = await fetchSimulations(AbortSignal.any([controller.signal, AbortSignal.timeout(5000)]));
        if (controller.signal.aborted) return;
        useFleetStore.getState().syncSimulations(list);
        useFleetStore.getState().setSimulationError(null);
      } catch (error) {
        if (controller.signal.aborted) return;
        useFleetStore.getState().setSimulationError(error instanceof Error ? error.message : "등록 목록 조회 실패");
      }
      timer = setTimeout(poll, 2000);
    }
    void poll();
    return () => { controller.abort(); clearTimeout(timer); };
  }, []);
}
