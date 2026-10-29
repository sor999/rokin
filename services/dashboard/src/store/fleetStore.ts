import { create } from "zustand";
import type {
  RealtimeConnectionState,
  RealtimeStatus,
  RobotState,
  RobotStatusDto,
  TelemetryEvent,
  Simulation,
} from "@/types";

const MAX_TRAIL = 200;

interface FleetStore {
  robots: Record<string, RobotState>;
  realtime: RealtimeStatus;
  simulations: Record<string, Simulation> | null;
  registryConnected: boolean;
  simulationError: string | null;
  syncSimulations: (list: Simulation[]) => void;
  setSimulationError: (error: string | null) => void;

  /** REST /api/robots 초기 로딩 결과 반영 */
  initFromRest: (list: RobotStatusDto[]) => void;

  /** REST pose history 결과를 궤적 초기값으로 반영 (SSE append 와 공존) */
  initTrail: (robotId: string, points: { x: number; y: number }[]) => void;

  /** SSE robot_update 이벤트 반영 */
  upsertFromSSE: (event: TelemetryEvent) => void;

  setRealtimeState: (
    state: RealtimeConnectionState,
    error?: string | null
  ) => void;
}

function emptyRobot(robotId: string): RobotState {
  return {
    robotId,
    online: false,
    lastSeen: null,
    pose: null,
    battery: null,
    state: "unknown",
    trail: [],
  };
}

export const useFleetStore = create<FleetStore>((set) => ({
  robots: {},
  simulations: null,
  registryConnected: false,
  simulationError: null,
  setSimulationError: (error) => set({ simulationError: error, registryConnected: error === null }),
  syncSimulations: (list) => set((prev) => {
    const simulations = { ...prev.simulations };
    for (const item of list) {
      const current = simulations[item.robotId];
      if (!current || current.version <= item.version) simulations[item.robotId] = item;
    }
    const robots: Record<string, RobotState> = {};
    for (const item of Object.values(simulations)) {
      if (item.desiredState === "DELETED") continue;
      const previous = prev.simulations?.[item.robotId];
      const restarted = previous?.runtimeId && item.runtimeId && previous.runtimeId !== item.runtimeId;
      const existing = restarted ? emptyRobot(item.robotId) : prev.robots[item.robotId] ?? emptyRobot(item.robotId);
      robots[item.robotId] = item.state === "RUNNING" ? existing : { ...existing, online: false };
    }
    return { simulations, robots };
  }),
  realtime: {
    state: "connecting",
    lastEventAt: null,
    lastEventLabel: null,
    error: null,
  },

  initFromRest: (list) => set((prev) => {
    const robots = { ...prev.robots };
    for (const dto of list) {
      if (prev.simulations && prev.simulations[dto.robotId]?.desiredState !== "RUNNING") continue;
      const existing = robots[dto.robotId];
      if (existing?.lastSeen && (!dto.lastSeen || existing.lastSeen >= dto.lastSeen)) continue;
      robots[dto.robotId] = {
        ...dto, state: dto.state ?? "unknown",
        trail: existing?.trail.length ? existing.trail : dto.pose ? [{ x: dto.pose.x, y: dto.pose.y }] : [],
      };
    }
    return { robots };
  }),

  initTrail: (robotId, points) =>
    set((prev) => {
      if (prev.simulations && prev.simulations[robotId]?.desiredState !== "RUNNING") return {};
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
      if (prev.simulations && prev.simulations[event.robotId]?.desiredState !== "RUNNING") return {};
      const existing = prev.robots[event.robotId] ?? emptyRobot(event.robotId);
      const updated = { ...existing, lastSeen: event.timestamp };

      switch (event.type) {
        case "pose": {
          const x = Number(event.data.x);
          const y = Number(event.data.y);
          if (!Number.isFinite(x) || !Number.isFinite(y)) {
            return {
              realtime: {
                ...prev.realtime,
                state: "error",
                error: `Invalid pose payload: ${event.robotId}`,
              },
            };
          }
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

      return {
        robots: { ...prev.robots, [event.robotId]: updated },
        realtime: {
          state: "live",
          lastEventAt: new Date().toISOString(),
          lastEventLabel: `${event.robotId} ${event.type}`,
          error: null,
        },
      };
    }),

  setRealtimeState: (state, error = null) =>
    set((prev) => ({
      realtime: {
        ...prev.realtime,
        state,
        error,
      },
    })),
}));
