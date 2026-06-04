"use client";

import { use, useEffect, useState } from "react";
import { useFleetStore } from "@/store/fleetStore";
import { useCommandWS } from "@/hooks/useCommandWS";
import { fetchPoseHistory, fetchBatteryHistory } from "@/services/api";
import { RobotMap } from "@/components/features/RobotMap";
import { BatteryGauge } from "@/components/features/BatteryGauge";
import { CommandPanel } from "@/components/features/CommandPanel";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { cn, stateBadgeVariant } from "@/lib/utils";
import type { BatteryDto } from "@/types";

/** Robot Detail: 개별 로봇 지도, 상태, 배터리 이력, 커맨드 패널 */
export default function RobotDetailPage({
  params,
}: {
  params: Promise<{ robotId: string }>;
}) {
  const { robotId } = use(params);
  const robots = useFleetStore((s) => s.robots);
  const robot = robots[robotId];
  const { isConnected, lastAck, lastResult, sendCommand } = useCommandWS(robotId);
  const [batteryHistory, setBatteryHistory] = useState<BatteryDto[]>([]);

  // 배터리 이력 로딩
  useEffect(() => {
    fetchBatteryHistory(robotId, 50)
      .then(setBatteryHistory)
      .catch(() => {});
  }, [robotId]);

  // 포즈 히스토리 로딩 → 궤적 초기화 (과거 → 최신 순으로 정렬)
  const initTrail = useFleetStore((s) => s.initTrail);
  useEffect(() => {
    fetchPoseHistory(robotId, 200)
      .then((history) => {
        const points = [...history]
          .sort(
            (a, b) =>
              new Date(a.timestamp).getTime() -
              new Date(b.timestamp).getTime()
          )
          .map((p) => ({ x: p.x, y: p.y }));
        initTrail(robotId, points);
      })
      .catch(() => {});
  }, [robotId, initTrail]);

  if (!robot) {
    return (
      <div className="flex h-full items-center justify-center">
        <p className="text-sm text-muted-foreground">
          Robot &quot;{robotId}&quot; not found. Waiting for data...
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* 헤더 */}
      <div className="flex items-center gap-3">
        <span
          className={cn(
            "h-3 w-3 rounded-full",
            robot.online ? "bg-emerald-400" : "bg-zinc-500"
          )}
        />
        <h1 className="text-lg font-semibold tracking-tight">{robotId}</h1>
        <Badge variant={stateBadgeVariant(robot.state, robot.online)}>
          {robot.online ? robot.state : "offline"}
        </Badge>
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        {/* 왼쪽: 지도 (2/3) */}
        <div className="lg:col-span-2 space-y-4">
          <RobotMap
            robots={Object.values(robots)}
            focusRobotId={robotId}
            includeTrailInView
            className="h-96"
          />

          {/* 위치 정보 */}
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm font-medium">
                Position Data
              </CardTitle>
            </CardHeader>
            <CardContent>
              <div className="grid grid-cols-3 gap-4 text-sm">
                <div>
                  <p className="text-xs text-muted-foreground">X</p>
                  <p className="font-mono font-medium">
                    {robot.pose ? robot.pose.x.toFixed(2) : "--"}
                  </p>
                </div>
                <div>
                  <p className="text-xs text-muted-foreground">Y</p>
                  <p className="font-mono font-medium">
                    {robot.pose ? robot.pose.y.toFixed(2) : "--"}
                  </p>
                </div>
                <div>
                  <p className="text-xs text-muted-foreground">Trail Points</p>
                  <p className="font-mono font-medium">{robot.trail.length}</p>
                </div>
              </div>
            </CardContent>
          </Card>
        </div>

        {/* 오른쪽: 제어 패널 (1/3) */}
        <div className="space-y-4">
          {/* 배터리 */}
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm font-medium">
                Battery Status
              </CardTitle>
            </CardHeader>
            <CardContent>
              <BatteryGauge level={robot.battery?.level ?? null} />

              {batteryHistory.length > 0 && (
                <>
                  <Separator className="my-3" />
                  <div className="space-y-1">
                    <p className="text-xs text-muted-foreground">
                      Recent History
                    </p>
                    <div className="flex h-12 items-end gap-px">
                      {batteryHistory.slice(-30).map((b, i) => (
                        <div
                          key={i}
                          className={cn(
                            "flex-1 rounded-t-sm transition-all",
                            b.level > 60
                              ? "bg-emerald-500/60"
                              : b.level > 30
                                ? "bg-amber-500/60"
                                : "bg-red-500/60"
                          )}
                          style={{ height: `${Math.max(b.level, 2)}%` }}
                          title={`${b.level.toFixed(1)}%`}
                        />
                      ))}
                    </div>
                  </div>
                </>
              )}
            </CardContent>
          </Card>

          {/* 커맨드 패널 */}
          <CommandPanel
            robotId={robotId}
            isConnected={isConnected}
            lastAck={lastAck}
            lastResult={lastResult}
            onSendCommand={sendCommand}
          />

          {/* 최근 업데이트 시각 */}
          <Card>
            <CardContent className="py-3">
              <div className="flex items-center justify-between text-xs">
                <span className="text-muted-foreground">Last Seen</span>
                <span className="font-mono text-foreground">
                  {new Date(robot.lastSeen).toLocaleTimeString("ko-KR")}
                </span>
              </div>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}
