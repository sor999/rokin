"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import type { AckDto, CommandResult } from "@/types";

interface UseCommandWSReturn {
  isConnected: boolean;
  lastAck: AckDto | null;
  lastResult: CommandResult | null;
  sendCommand: (
    robotId: string,
    command: string,
    data?: Record<string, unknown>
  ) => void;
}

/**
 * STOMP WebSocket 커맨드 전송 및 Ack 수신 훅.
 * @param robotId 구독 대상 로봇 ID (변경 시 재구독)
 */
export function useCommandWS(robotId: string): UseCommandWSReturn {
  const [isConnected, setIsConnected] = useState(false);
  const [lastAck, setLastAck] = useState<AckDto | null>(null);
  const [lastResult, setLastResult] = useState<CommandResult | null>(null);
  const clientRef = useRef<Client | null>(null);

  useEffect(() => {
    const apiBase = (process.env.NEXT_PUBLIC_API_URL ?? "").replace(/\/$/, "");
    const client = new Client({
      webSocketFactory: () => new SockJS(`${apiBase}/ws`),
      reconnectDelay: 3000,
      onConnect: () => {
        setIsConnected(true);

        // 커맨드 전송 결과 구독 (해당 로봇 것만 반영)
        client.subscribe("/topic/cmd-result", (msg) => {
          try {
            const res = JSON.parse(msg.body) as CommandResult;
            if (res.robotId === robotId) setLastResult(res);
          } catch { /* 무시 */ }
        });

        // 해당 로봇의 Ack 구독
        client.subscribe(`/topic/ack/${robotId}`, (msg) => {
          try {
            setLastAck(JSON.parse(msg.body) as AckDto);
          } catch { /* 무시 */ }
        });
      },
      onDisconnect: () => setIsConnected(false),
      onStompError: () => setIsConnected(false),
    });

    client.activate();
    clientRef.current = client;

    return () => {
      client.deactivate();
      clientRef.current = null;
    };
  }, [robotId]);

  const sendCommand = useCallback(
    (rid: string, command: string, data: Record<string, unknown> = {}) => {
      const client = clientRef.current;
      if (!client?.connected) return;

      client.publish({
        destination: "/app/command",
        body: JSON.stringify({
          robotId: rid,
          command,
          data,
          issuedBy: "dashboard",
        }),
      });
    },
    []
  );

  return { isConnected, lastAck, lastResult, sendCommand };
}
