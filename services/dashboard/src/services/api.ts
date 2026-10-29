import type { RobotStatusDto, PoseDto, BatteryDto, Simulation, CreateSimulation } from "@/types";

const BASE = "/api";

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`);
  if (!res.ok) throw new Error(`API ${res.status}: ${path}`);
  return res.json() as Promise<T>;
}

/** 전체 로봇 최신 상태 조회 */
export function fetchRobots(): Promise<RobotStatusDto[]> {
  return get<RobotStatusDto[]>("/robots");
}

/** 특정 로봇의 위치 이력 조회 */
export function fetchPoseHistory(
  robotId: string,
  limit = 200
): Promise<PoseDto[]> {
  return get<PoseDto[]>(`/robots/${robotId}/pose/history?limit=${limit}`);
}

/** 특정 로봇의 배터리 이력 조회 */
export function fetchBatteryHistory(
  robotId: string,
  limit = 100
): Promise<BatteryDto[]> {
  return get<BatteryDto[]>(`/robots/${robotId}/battery/history?limit=${limit}`);
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
