import type { RobotStatusDto, PoseDto, BatteryDto, Simulation, CreateSimulation, AckDto } from "@/types";

const BASE = "/api";

async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { signal, cache: "no-store" });
  if (!res.ok) throw new Error(`API ${res.status}: ${path}`);
  return res.json() as Promise<T>;
}

/** 전체 로봇 최신 상태 조회 */
export function fetchRobots(signal?: AbortSignal): Promise<RobotStatusDto[]> {
  return get<RobotStatusDto[]>("/robots", signal);
}

/** 특정 로봇의 위치 이력 조회 */
export function fetchPoseHistory(
  robotId: string,
  limit = 200,
  runtimeId?: string
): Promise<PoseDto[]> {
  return get<PoseDto[]>(`/robots/${robotId}/pose/history?limit=${limit}${runtimeId ? `&runtimeId=${encodeURIComponent(runtimeId)}` : ""}`);
}

/** 특정 로봇의 배터리 이력 조회 */
export function fetchBatteryHistory(
  robotId: string,
  limit = 100,
  runtimeId?: string
): Promise<BatteryDto[]> {
  return get<BatteryDto[]>(`/robots/${robotId}/battery/history?limit=${limit}${runtimeId ? `&runtimeId=${encodeURIComponent(runtimeId)}` : ""}`);
}

/** 시뮬레이터 등록 상태를 조회한다. */
export function fetchSimulations(signal?: AbortSignal): Promise<Simulation[]> {
  return fetch(`${BASE}/simulations`, { signal, cache: "no-store" }).then(readSimulationResponse<Simulation[]>);
}

async function readSimulationResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new Error(body?.message ?? `요청 실패 (${response.status})`);
  }
  return response.json() as Promise<T>;
}

export function createSimulation(input: CreateSimulation): Promise<Simulation> {
  return fetch(`${BASE}/simulations`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(input),
  }).then(readSimulationResponse<Simulation>);
}

export function deleteSimulation(robotId: string): Promise<Simulation> {
  return fetch(`${BASE}/simulations/${encodeURIComponent(robotId)}`, { method: "DELETE" })
    .then(readSimulationResponse<Simulation>);
}

export async function fetchCommandAck(robotId: string, cmdId: string, signal?: AbortSignal): Promise<AckDto | null> {
  const response = await fetch(`${BASE}/robots/${encodeURIComponent(robotId)}/acks/${encodeURIComponent(cmdId)}`, { signal, cache: "no-store" });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`Ack 조회 실패 (${response.status})`);
  return response.json() as Promise<AckDto>;
}

export async function fetchCommandByKey(key: string, signal?: AbortSignal): Promise<import("@/types").DurableCommandStatus | null> {
  const response = await fetch(`${BASE}/commands?idempotencyKey=${encodeURIComponent(key)}`, { signal, cache: "no-store" });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`명령 조회 실패 (${response.status})`);
  return response.json() as Promise<import("@/types").DurableCommandStatus>;
}
