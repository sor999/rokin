import { create } from "zustand";
import type { RobotState, RobotStatusDto, TelemetryEvent } from "@/types";

const MAX_TRAIL = 200;

interface FleetStore {
  robots: Record<string, RobotState>;

  /** REST /api/robots 초기 로딩 결과 반영 */
  initFromRest: (list: RobotStatusDto[]) => void;

  /** REST pose history 결과를 궤적 초기값으로 반영 (SSE append 와 공존) */
  initTrail: (robotId: string, points: { x: number; y: number }[]) => void;

  /** SSE robot_update 이벤트 반영 */
  upsertFromSSE: (event: TelemetryEvent) => void;
}

function emptyRobot(robotId: string): RobotState {
  return {
    robotId,
    online: false,
    lastSeen: new Date().toISOString(),
    pose: null,
    battery: null,
    state: "unknown",
    trail: [],
  };
}

export const useFleetStore = create<FleetStore>((set) => ({
  robots: {},

  initFromRest: (list) => {
    const robots: Record<string, RobotState> = {};
    for (const dto of list) {
      robots[dto.robotId] = {
        robotId: dto.robotId,
        online: dto.online,
        lastSeen: dto.lastSeen,
        pose: dto.pose,
        battery: dto.battery,
        state: dto.state ?? "unknown",
        trail: dto.pose ? [{ x: dto.pose.x, y: dto.pose.y }] : [],
      };
    }
    set({ robots });
  },

  initTrail: (robotId, points) =>
    set((prev) => {
      const existing = prev.robots[robotId] ?? emptyRobot(robotId);
      // REST 이력 + SSE로 누적된 최근 trail 병합 (최대 MAX_TRAIL)
      const merged = [...points, ...existing.trail];
      const trail =
        merged.length > MAX_TRAIL ? merged.slice(-MAX_TRAIL) : merged;
      return {
        robots: { ...prev.robots, [robotId]: { ...existing, trail } },
      };
    }),

  upsertFromSSE: (event) =>
    set((prev) => {
      const existing = prev.robots[event.robotId] ?? emptyRobot(event.robotId);
      const updated = { ...existing, lastSeen: event.timestamp };

      switch (event.type) {
        case "pose": {
          const x = event.data.x as number;
          const y = event.data.y as number;
          updated.pose = { x, y, timestamp: event.timestamp };
          updated.online = true;
          const trail = [...existing.trail, { x, y }];
          updated.trail = trail.length > MAX_TRAIL ? trail.slice(-MAX_TRAIL) : trail;
          break;
        }
        case "battery": {
          updated.battery = {
            level: event.data.level as number,
            timestamp: event.timestamp,
          };
          updated.online = true;
          break;
        }
        case "status": {
          updated.state = event.data.state as string;
          updated.online = true;
          break;
        }
        case "offline": {
          updated.online = false;
          break;
        }
      }

      return { robots: { ...prev.robots, [event.robotId]: updated } };
    }),
}));
