// API 응답 타입 (Spring Boot DTO 매핑)

export interface PoseDto {
  x: number;
  y: number;
  timestamp: string;
}

export interface BatteryDto {
  level: number;
  timestamp: string;
}

export interface RobotStatusDto {
  robotId: string;
  online: boolean;
  lastSeen: string;
  pose: PoseDto | null;
  battery: BatteryDto | null;
  state: string;
}

// SSE 이벤트 페이로드
export interface TelemetryEvent {
  type: "pose" | "battery" | "status" | "offline";
  robotId: string;
  timestamp: string;
  data: Record<string, unknown>;
}

// WebSocket Command/Ack
export interface CommandRequest {
  robotId: string;
  command: string;
  data: Record<string, unknown>;
  issuedBy: string;
}

export interface CommandResult {
  cmdId: string;
  robotId: string;
  status: string;
}

export type AckStatus = "accepted" | "running" | "done" | "failed";

export interface AckDto {
  cmdId: string;
  robotId: string;
  status: AckStatus;
  message: string | null;
  timestamp: string;
}

// 클라이언트 전역 상태
export interface RobotState {
  robotId: string;
  online: boolean;
  lastSeen: string;
  pose: PoseDto | null;
  battery: BatteryDto | null;
  state: string;
  trail: { x: number; y: number }[];
}
