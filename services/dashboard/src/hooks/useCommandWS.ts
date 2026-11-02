"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import { fetchCommandAck } from "@/services/api";
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

  useEffect(() => {
    recoveryRef.current = new CommandRecovery();
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
            if (res.robotId === robotId && recoveryRef.current.accepted(res)) setLastResult(res);
          } catch { /* 무시 */ }
        });

        // 해당 로봇의 Ack 구독
        client.subscribe(`/topic/ack/${robotId}`, (msg) => {
          if (!active) return;
          try {
            const ack = JSON.parse(msg.body) as AckDto;
            if (recoveryRef.current.apply(ack)) setLastAck(ack);
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
      const ids = recoveryRef.current.pending();
      if (!active || !ids.length || request) return;
      const current = new AbortController();
      request = current;
      try {
        await pollCommandAcks(recoveryRef.current,
          (id, signal) => fetchCommandAck(robotId, id, signal),
          (ack) => { if (active) setLastAck(ack); }, current.signal);
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
      try {
        client.publish({
          destination: "/app/command",
          body: JSON.stringify({
            requestId,
            robotId: rid,
            command,
            data,
            issuedBy: "dashboard",
          }),
        });
        recoveryRef.current.sent(requestId);
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
