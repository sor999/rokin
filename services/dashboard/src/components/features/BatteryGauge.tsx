"use client";

import { cn, batteryColor } from "@/lib/utils";

interface BatteryGaugeProps {
  level: number | null;
  className?: string;
}

/** 배터리 레벨 시각화 (수평 프로그레스 바 + 퍼센트) */
export function BatteryGauge({ level, className }: BatteryGaugeProps) {
  if (level === null) {
    return (
      <div className={cn("text-xs text-muted-foreground", className)}>
        Battery: --
      </div>
    );
  }

  const clamped = Math.max(0, Math.min(100, level));

  return (
    <div className={cn("space-y-1", className)}>
      <div className="flex items-center justify-between text-xs">
        <span className="text-muted-foreground">Battery</span>
        <span className={cn("font-mono font-medium", batteryColor(clamped))}>
          {clamped.toFixed(0)}%
        </span>
      </div>
      <div className="h-2 w-full rounded-full bg-muted">
        <div
          className={cn(
            "h-full rounded-full transition-all duration-500",
            clamped > 60
              ? "bg-emerald-500"
              : clamped > 30
                ? "bg-amber-500"
                : "bg-red-500"
          )}
          style={{ width: `${clamped}%` }}
        />
      </div>
    </div>
  );
}
