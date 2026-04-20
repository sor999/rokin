"use client";

import { useMemo } from "react";
import type { RobotState } from "@/types";
import { cn } from "@/lib/utils";

interface RobotMapProps {
  /** 전체 fleet 또는 단일 로봇 표시 */
  robots: RobotState[];
  /** 현재 선택된 로봇 (강조 표시) */
  focusRobotId?: string;
  className?: string;
}

// 마커 색상 매핑
const COLORS = [
  "#4589ff", // IBM Blue 50
  "#08bdba", // IBM Teal 40
  "#ee5396", // IBM Magenta 40
  "#a56eff", // IBM Purple 40
  "#ff832b", // IBM Orange 40
];

/** SVG 기반 2D 로봇 위치 지도 및 궤적 시각화 */
export function RobotMap({ robots, focusRobotId, className }: RobotMapProps) {
  // viewBox를 현재 위치 기준으로만 계산 (trail 제외)
  // trail까지 포함하면 로봇이 이동할수록 뷰가 계속 확대되어 마커가 점점 작아짐
  const { viewBox, scale } = useMemo(() => {
    const posed = robots.filter((r) => r.pose !== null);

    if (posed.length === 0) {
      return { viewBox: "-10 -10 20 20", scale: 20 };
    }

    let minX = Infinity,
      maxX = -Infinity,
      minY = Infinity,
      maxY = -Infinity;

    for (const r of posed) {
      if (!r.pose) continue;
      if (r.pose.x < minX) minX = r.pose.x;
      if (r.pose.x > maxX) maxX = r.pose.x;
      if (r.pose.y < minY) minY = r.pose.y;
      if (r.pose.y > maxY) maxY = r.pose.y;
    }

    // 로봇 분산 범위의 30% or 최소 5 단위를 여백으로 확보
    const spread = Math.max(maxX - minX, maxY - minY);
    const pad = Math.max(spread * 0.3, 5);
    const s = spread + pad * 2;

    return {
      viewBox: `${minX - pad} ${minY - pad} ${s} ${s}`,
      scale: s,
    };
  }, [robots]);

  const markerSize = scale * 0.02;

  return (
    <div
      className={cn(
        "relative overflow-hidden rounded-lg border border-border bg-card",
        className
      )}
    >
      <svg
        viewBox={viewBox}
        className="h-full w-full"
        preserveAspectRatio="xMidYMid meet"
      >
        {/* 그리드 패턴 */}
        <defs>
          <pattern
            id="grid"
            width={1}
            height={1}
            patternUnits="userSpaceOnUse"
          >
            <path
              d="M 1 0 L 0 0 0 1"
              fill="none"
              stroke="currentColor"
              strokeWidth={0.02}
              className="text-border"
              opacity={0.3}
            />
          </pattern>
        </defs>
        <rect width="100%" height="100%" fill="url(#grid)" />

        {/* 로봇별 궤적 + 마커 */}
        {robots.map((robot, idx) => {
          const color = COLORS[idx % COLORS.length];
          const isFocused = !focusRobotId || robot.robotId === focusRobotId;
          const opacity = isFocused ? 1 : 0.3;

          return (
            <g key={robot.robotId} opacity={opacity}>
              {/* 궤적 Polyline */}
              {robot.trail.length > 1 && (
                <polyline
                  points={robot.trail.map((p) => `${p.x},${p.y}`).join(" ")}
                  fill="none"
                  stroke={color}
                  strokeWidth={markerSize * 0.5}
                  strokeLinejoin="round"
                  strokeLinecap="round"
                  opacity={0.5}
                />
              )}

              {/* 현재 위치 마커 */}
              {robot.pose && (
                <>
                  {/* 외부 링 (펄스 효과용) */}
                  {robot.online && (
                    <circle
                      cx={robot.pose.x}
                      cy={robot.pose.y}
                      r={markerSize * 1.8}
                      fill={color}
                      opacity={0.15}
                    >
                      <animate
                        attributeName="r"
                        values={`${markerSize * 1.5};${markerSize * 2.5};${markerSize * 1.5}`}
                        dur="2s"
                        repeatCount="indefinite"
                      />
                      <animate
                        attributeName="opacity"
                        values="0.2;0.05;0.2"
                        dur="2s"
                        repeatCount="indefinite"
                      />
                    </circle>
                  )}

                  {/* 마커 원 */}
                  <circle
                    cx={robot.pose.x}
                    cy={robot.pose.y}
                    r={markerSize}
                    fill={robot.online ? color : "#6b7280"}
                    stroke="#fff"
                    strokeWidth={markerSize * 0.25}
                  />

                  {/* 로봇 ID 라벨 */}
                  <text
                    x={robot.pose.x}
                    y={robot.pose.y - markerSize * 2}
                    textAnchor="middle"
                    fontSize={markerSize * 2}
                    fill="currentColor"
                    className="text-foreground"
                  >
                    {robot.robotId}
                  </text>
                </>
              )}
            </g>
          );
        })}
      </svg>

      {/* 범례 */}
      <div className="absolute bottom-2 left-2 flex flex-col gap-1 rounded-md bg-background/80 p-2 text-xs backdrop-blur-sm">
        {robots.map((r, idx) => (
          <div key={r.robotId} className="flex items-center gap-1.5">
            <span
              className="h-2 w-2 rounded-full"
              style={{ backgroundColor: COLORS[idx % COLORS.length] }}
            />
            <span className="text-muted-foreground">{r.robotId}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
