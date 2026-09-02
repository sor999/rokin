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
  ) => void;
}

type CmdState = "idle" | "pending" | AckStatus | "timeout";

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
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // cmd-result 도착 시 서버가 발급한 cmdId 캡처 (Ack 매칭 키)
  useEffect(() => {
    if (!lastResult || lastResult.robotId !== robotId) return;
    if (cmdState === "pending" && !activeCmdId) {
      setActiveCmdId(lastResult.cmdId);
    }
  }, [lastResult, robotId, cmdState, activeCmdId]);

  // Ack 수신 시 상태 갱신
  useEffect(() => {
    if (!lastAck || lastAck.robotId !== robotId) return;
    if (activeCmdId && lastAck.cmdId === activeCmdId) {
      setCmdState(lastAck.status);
      if (timerRef.current) {
        clearTimeout(timerRef.current);
        timerRef.current = null;
      }
    }
  }, [lastAck, robotId, activeCmdId]);

  const clearTimer = useCallback(() => {
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  const handleMoveTo = useCallback(() => {
    const x = parseFloat(targetX);
    const y = parseFloat(targetY);
    if (isNaN(x) || isNaN(y)) return;

    clearTimer();
    setCmdState("pending");
    setActiveCmdId(null);
    onSendCommand(robotId, "move_to", { x, y });

    // 3초 timeout
    timerRef.current = setTimeout(() => {
      setCmdState("timeout");
    }, ACK_TIMEOUT_MS);
  }, [targetX, targetY, robotId, onSendCommand, clearTimer]);

  const handleStop = useCallback(() => {
    clearTimer();
    setCmdState("pending");
    setActiveCmdId(null);
    onSendCommand(robotId, "stop");

    timerRef.current = setTimeout(() => {
      setCmdState("timeout");
    }, ACK_TIMEOUT_MS);
  }, [robotId, onSendCommand, clearTimer]);

  const handleRetry = useCallback(() => {
    handleMoveTo();
  }, [handleMoveTo]);

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
            disabled={!isConnected}
          >
            Stop
          </Button>
        </div>

        {/* 명령 상태 */}
        {cmdState !== "idle" && (
          <div className="flex items-center justify-between rounded-md border border-border px-3 py-2">
            <span className="text-xs text-muted-foreground">Status</span>
            <Badge variant={cmdBadgeVariant(cmdState)}>{cmdState}</Badge>
          </div>
        )}

        {/* Timeout 재시도 */}
        {cmdState === "timeout" && (
          <Button
            size="sm"
            variant="outline"
            onClick={handleRetry}
            className="w-full"
          >
            Retry
          </Button>
        )}
      </CardContent>
    </Card>
  );
}
