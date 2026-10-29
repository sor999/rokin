import type { CreateSimulation } from "@/types";

/** 서버와 같은 좌표·속도 경계를 등록 요청 전에 검증한다. */
export function validateSimulation(input: CreateSimulation): string | null {
  if (!Number.isFinite(input.speed) || input.speed <= 0 || input.speed > 10) {
    return "속도는 0 초과 10 이하로 입력하세요.";
  }
  if (![input.startX, input.startY].every((value) => Number.isFinite(value) && Math.abs(value) <= 1000000)) {
    return "시작 좌표는 -1,000,000~1,000,000 범위로 입력하세요.";
  }
  return null;
}
