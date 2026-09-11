"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { AckDto, AckStatus, CommandResult } from "@/types";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

const ACK_TIMEOUT_MS = 5000;

interface CommandPanelProps {
  robotId: string;
  lastResult: CommandResult | null;
  isConnected: boolean;
  lastAck: AckDto | null;
  onSendCommand: (
    robotId: string,
    command: string,
    data?: Record<string, unknown>,
  ) => string | null;
}

type CmdState = "idle" | "pending" | AckStatus | "timeout";

interface SentCommand {
  command: "move_to" | "stop";
  data: Record<string, unknown>;
}

/** 명령 처리 결과에 맞는 배지 색상을 선택한다. */
function cmdBadgeVariant(
  state: CmdState,
): "default" | "secondary" | "destructive" | "outline" {
  switch (state) {
    case "done":
      return "default";
    case "accepted":
    case "running":
      return "secondary";
    case "failed":
    case "timeout":
      return "destructive";
    default:
      return "outline";
  }
}

/** 로봇 제어 패널: move_to(x, y) 전송, stop, Ack 상태 표시 및 timeout 재시도 */
export function CommandPanel({
  robotId,
  isConnected,
  lastAck,
  lastResult,
  onSendCommand,
}: CommandPanelProps) {
  const [targetX, setTargetX] = useState("");
  const [targetY, setTargetY] = useState("");
  const [cmdState, setCmdState] = useState<CmdState>("idle");
  const [activeCmdId, setActiveCmdId] = useState<string | null>(null);
  const [activeRequestId, setActiveRequestId] = useState<string | null>(null);
  const [lastCommand, setLastCommand] = useState<SentCommand | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // cmd-result 도착 시 서버가 발급한 cmdId 캡처 (Ack 매칭 키)
  useEffect(() => {
    if (!lastResult || lastResult.robotId !== robotId || !activeRequestId) return;
    if (lastResult.requestId !== activeRequestId) return;
    setActiveCmdId(lastResult.cmdId);
  }, [lastResult, robotId, activeRequestId]);

  // Ack 수신 시 상태 갱신
  useEffect(() => {
    if (!lastAck || lastAck.robotId !== robotId) return;
    if (activeCmdId && lastAck.cmdId === activeCmdId) {
      setCmdState((current) =>
        current === "done" || current === "failed" ? current : lastAck.status,
      );
      if (timerRef.current && (lastCommand?.command !== "stop" ||
          lastAck.status === "done" || lastAck.status === "failed")) {
        clearTimeout(timerRef.current);
        timerRef.current = null;
      }
    }
  }, [lastAck, robotId, activeCmdId, lastCommand]);

  const clearTimer = useCallback(() => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  const send = useCallback((command: SentCommand) => {
    clearTimer();
    setActiveCmdId(null);
    setLastCommand(command);
    const requestId = onSendCommand(robotId, command.command, command.data);
    setActiveRequestId(requestId);
    if (!requestId) {
      setCmdState("failed");
      return;
    }
    setCmdState("pending");

    timerRef.current = setTimeout(() => {
      setCmdState("timeout");
    }, ACK_TIMEOUT_MS);
  }, [robotId, onSendCommand, clearTimer]);

  const handleMoveTo = useCallback(() => {
    const x = parseFloat(targetX);
    const y = parseFloat(targetY);
    if (!Number.isFinite(x) || !Number.isFinite(y)) return;
    send({ command: "move_to", data: { x, y } });
  }, [targetX, targetY, send]);

  const handleStop = useCallback(() => {
    send({ command: "stop", data: {} });
  }, [send]);

  const handleRetry = useCallback(() => {
    if (lastCommand) send(lastCommand);
  }, [lastCommand, send]);

  // 언마운트 시 타이머 정리
  useEffect(() => clearTimer, [clearTimer]);

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <CardTitle className="text-sm font-medium">Command Panel</CardTitle>
          <div className="flex items-center gap-2">
            <span
              className={cn(
                "h-2 w-2 rounded-full",
                isConnected ? "bg-emerald-400" : "bg-zinc-500",
              )}
            />
            <span className="text-xs text-muted-foreground">
              {isConnected ? "WS Connected" : "Disconnected"}
            </span>
          </div>
        </div>
      </CardHeader>

      <CardContent className="space-y-4">
        {/* move_to 폼 */}
        <div className="grid grid-cols-2 gap-3">
          <div className="space-y-1">
            <Label htmlFor="target-x" className="text-xs">
              Target X
            </Label>
            <Input
              id="target-x"
              type="number"
              step="0.1"
              placeholder="0.0"
              value={targetX}
              onChange={(e) => setTargetX(e.target.value)}
              className="h-8 font-mono text-sm"
            />
          </div>
          <div className="space-y-1">
            <Label htmlFor="target-y" className="text-xs">
              Target Y
            </Label>
            <Input
              id="target-y"
              type="number"
              step="0.1"
              placeholder="0.0"
              value={targetY}
              onChange={(e) => setTargetY(e.target.value)}
              className="h-8 font-mono text-sm"
            />
          </div>
        </div>

        {/* 버튼 영역 */}
        <div className="flex gap-2">
          <Button
            size="sm"
            onClick={handleMoveTo}
            disabled={!isConnected || cmdState === "pending"}
            className="flex-1"
          >
            Move To
          </Button>
          <Button
            size="sm"
            variant="destructive"
            onClick={handleStop}
            disabled={!isConnected || (lastCommand?.command === "stop" && cmdState === "pending")}
          >
            Stop
          </Button>
        </div>

        {/* 명령 상태 */}
        {cmdState !== "idle" && (
          <div className="flex items-center justify-between rounded-md border border-border px-3 py-2">
            <span className="text-xs text-muted-foreground">
              {lastCommand?.command === "stop" ? "Stop" : "Move To"} Status
            </span>
            <Badge variant={cmdBadgeVariant(cmdState)}>
              {lastCommand?.command === "stop" && cmdState === "done" ? "Stopped" : cmdState}
            </Badge>
          </div>
        )}

        {/* Timeout 재시도 */}
        {cmdState === "timeout" && lastCommand?.command === "stop" && (
          <p role="status" className="text-xs text-muted-foreground">
            Stop confirmation was not received. The robot may still be moving.
          </p>
        )}
        {(cmdState === "timeout" || cmdState === "failed") && (
          <Button
            size="sm"
            variant="outline"
            onClick={handleRetry}
            disabled={!isConnected}
            className="w-full"
          >
            {lastCommand?.command === "stop" ? "Retry Stop" : "Retry Move To"}
          </Button>
        )}
      </CardContent>
    </Card>
  );
}
