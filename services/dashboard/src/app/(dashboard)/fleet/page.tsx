"use client";

import { useFleetStore } from "@/store/fleetStore";
import { FleetCard } from "@/components/features/FleetCard";
import { AddSimulation, SimulationStatus } from "@/components/features/SimulationControls";
import { RobotMap } from "@/components/features/RobotMap";

/** Fleet Overview: 전체 로봇 그리드 카드 + 전체 지도 */
export default function FleetPage() {
  const robots = useFleetStore((s) => s.robots);
  const simulations = useFleetStore((s) => s.simulations);
  const error = useFleetStore((s) => s.simulationError);
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

      <AddSimulation />
      {error && <p role="alert" className="text-sm text-destructive">등록 서버 연결 실패: {error}. 연결 후 자동으로 다시 조회합니다.</p>}
      {simulations && Object.values(simulations)
        .filter((s) => s.desiredState === "DELETED" && s.state !== "DELETED")
        .map((s) => <p key={s.robotId} role="status" className="text-sm text-muted-foreground">
          {s.robotId}: {s.state === "FAILED" ? `종료 실패 · ${s.error ?? "자동으로 재시도합니다."}` : "삭제 중 · 실행 종료를 확인하고 있습니다."}
        </p>)}

      {/* 전체 지도 */}
      <RobotMap robots={robotList} className="h-80" />

      {/* 로봇 카드 그리드 */}
      {robotList.length > 0 ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {robotList.map((robot) => (
            <div key={robot.robotId}>
              <FleetCard robot={robot} />
              {simulations?.[robot.robotId] && <SimulationStatus simulation={simulations[robot.robotId]} />}
            </div>
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
