"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import { DurableRequests } from "@/lib/durableRequests";
import { fetchCommandByKey, fetchCommandAck } from "@/services/api";
import { CommandRecovery } from "@/lib/commandRecovery";
import { pollCommandAcks } from "@/lib/pollCommandAcks";
import type { AckDto, CommandResult } from "@/types";

interface UseCommandWSReturn {
  isConnected: boolean;
  lastAck: AckDto | null;
  lastResult: CommandResult | null;
  sendCommand: (
    robotId: string,
    command: string,
    data?: Record<string, unknown>
  ) => string | null;
}

/**
 * STOMP WebSocket 커맨드 전송 및 Ack 수신 훅.
 * @param robotId 구독 대상 로봇 ID (변경 시 재구독)
 */
export function useCommandWS(robotId: string): UseCommandWSReturn {
  const [isConnected, setIsConnected] = useState(false);
  const [lastAck, setLastAck] = useState<AckDto | null>(null);
  const [lastResult, setLastResult] = useState<CommandResult | null>(null);
  const recoveryRef = useRef(new CommandRecovery());
  const clientRef = useRef<Client | null>(null);
  const durableRef = useRef<DurableRequests | null>(null);

  useEffect(() => {
    recoveryRef.current = new CommandRecovery();
    durableRef.current = new DurableRequests(sessionStorage, `robot-commands:${robotId}`);
    for (const saved of durableRef.current.all()) {
      recoveryRef.current.sent(saved.requestId);
      if (saved.cmdId) recoveryRef.current.accepted({ requestId: saved.requestId, cmdId: saved.cmdId, robotId, status: "accepted" });
    }
    let recoveryCursor = 0;
    const terminalKnown = new Set<string>();
    setIsConnected(false);
    setLastAck(null);
    setLastResult(null);
    let active = true;
    let request: AbortController | null = null;
    const apiBase = (process.env.NEXT_PUBLIC_API_URL ?? "").replace(/\/$/, "");
    const client = new Client({
      webSocketFactory: () => new SockJS(`${apiBase}/ws`),
      reconnectDelay: 3000,
      onConnect: () => {
        if (!active) return;
        setIsConnected(true);

        // 커맨드 전송 결과 구독 (해당 로봇 것만 반영)
        client.subscribe("/topic/cmd-result", (msg) => {
          if (!active) return;
          try {
            const res = JSON.parse(msg.body) as CommandResult;
            if (res.robotId === robotId && recoveryRef.current.accepted(res)) {
              if (res.requestId) durableRef.current?.accepted(res.requestId, res.cmdId);
              setLastResult(res);
            }
          } catch { /* 무시 */ }
        });

        // 해당 로봇의 Ack 구독
        client.subscribe(`/topic/ack/${robotId}`, (msg) => {
          if (!active) return;
          try {
            const ack = JSON.parse(msg.body) as AckDto;
            if (recoveryRef.current.apply(ack)) {
              setLastAck(ack);
              if (ack.status === "done" || ack.status === "failed") terminalKnown.add(ack.cmdId);
            }
          } catch { /* 무시 */ }
        });
      },
      onDisconnect: () => { if (active) setIsConnected(false); },
      onWebSocketClose: () => { if (active) setIsConnected(false); },
      onStompError: () => { if (active) setIsConnected(false); },
    });

    client.activate();
    clientRef.current = client;

    const recover = async () => {
      if (!active || request) return;
      const current = new AbortController();
      request = current;
      try {
        // Bounded batches also recover a command whose initial STOMP response was lost.
        const all = durableRef.current?.all() ?? [];
        const saved = all.slice(recoveryCursor, recoveryCursor + 4);
        recoveryCursor = recoveryCursor + 4 >= all.length ? 0 : recoveryCursor + 4;
        for (const entry of saved) {
          if (!active || current.signal.aborted) return;
          const status = await fetchCommandByKey(entry.idempotencyKey, AbortSignal.any([current.signal, AbortSignal.timeout(3000)]));
          if (!active) return;
          if (!status) {
            // Legacy commands have no outbox entry; retain their key until a real terminal Ack.
            if (entry.cmdId && terminalKnown.has(entry.cmdId)) durableRef.current?.finish(entry.requestId);
            continue;
          }
          if (status.robotId !== robotId) continue;
          const result: CommandResult = { requestId: entry.requestId, cmdId: status.cmdId, robotId,
            status: "accepted", deliveryStatus: status.deliveryStatus };
          recoveryRef.current.accepted(result);
          durableRef.current?.accepted(entry.requestId, status.cmdId);
          setLastResult(result);
          if (status.ack) setLastAck(previous => previous?.cmdId === status.cmdId &&
            (previous.status === "done" || previous.status === "failed") ? previous : status.ack);
          if (status.deliveryStatus === "COMPLETED" || status.deliveryStatus === "EXPIRED") durableRef.current?.finish(entry.requestId);
        }
        await pollCommandAcks(recoveryRef.current,
          (id, signal) => fetchCommandAck(robotId, id, signal),
          (ack) => { if (active) {
            setLastAck(ack);
            if (ack.status === "done" || ack.status === "failed") terminalKnown.add(ack.cmdId);
          } }, current.signal);
      } catch { /* DB 적재 지연과 일시 장애는 다음 조회에서 재시도한다. */ }
      finally { if (request === current) request = null; }
    };
    const polling = setInterval(recover, 1000);
    return () => {
      active = false;
      clearInterval(polling);
      request?.abort();
      client.deactivate();
      clientRef.current = null;
    };
  }, [robotId]);

  const sendCommand = useCallback(
    (rid: string, command: string, data: Record<string, unknown> = {}) => {
      const client = clientRef.current;
      if (!client?.connected || !recoveryRef.current.canSend()) return null;

      const requestId = crypto.randomUUID();
      const idempotencyKey = crypto.randomUUID();
      if (!durableRef.current?.add(requestId, idempotencyKey)) return null;
      recoveryRef.current.sent(requestId);
      try {
        client.publish({
          destination: "/app/command",
          body: JSON.stringify({
            requestId,
            idempotencyKey,
            robotId: rid,
            command,
            data,
            issuedBy: "dashboard",
          }),
        });
        return requestId;
      } catch {
        setIsConnected(false);
        return null;
      }
    },
    []
  );

  return { isConnected, lastAck, lastResult, sendCommand };
}
