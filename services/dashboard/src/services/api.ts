import type { RobotStatusDto, PoseDto, BatteryDto } from "@/types";

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
