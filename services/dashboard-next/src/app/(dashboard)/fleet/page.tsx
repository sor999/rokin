"use client";

import { useFleetStore } from "@/store/fleetStore";
import { FleetCard } from "@/components/features/FleetCard";
import { RobotMap } from "@/components/features/RobotMap";

/** Fleet Overview: 전체 로봇 그리드 카드 + 전체 지도 */
export default function FleetPage() {
  const robots = useFleetStore((s) => s.robots);
  const robotList = Object.values(robots).sort((a, b) =>
    a.robotId.localeCompare(b.robotId)
  );

  const onlineCount = robotList.filter((r) => r.online).length;

  return (
    <div className="space-y-6">
      {/* 헤더 */}
      <div>
        <h1 className="text-lg font-semibold tracking-tight">Fleet Overview</h1>
        <p className="text-sm text-muted-foreground">
          {robotList.length} robots registered · {onlineCount} online
        </p>
      </div>

      {/* 전체 지도 */}
      <RobotMap robots={robotList} className="h-80" />

      {/* 로봇 카드 그리드 */}
      {robotList.length > 0 ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {robotList.map((robot) => (
            <FleetCard key={robot.robotId} robot={robot} />
          ))}
        </div>
      ) : (
        <div className="flex h-40 items-center justify-center rounded-lg border border-dashed border-border">
          <p className="text-sm text-muted-foreground">
            Waiting for robot data...
          </p>
        </div>
      )}
    </div>
  );
}
