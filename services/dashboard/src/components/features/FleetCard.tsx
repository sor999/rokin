"use client";

import Link from "next/link";
import type { RobotState } from "@/types";
import { cn, batteryColor, stateBadgeVariant } from "@/lib/utils";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";

interface FleetCardProps {
  robot: RobotState;
}

export function FleetCard({ robot }: FleetCardProps) {
  const { robotId, online, state, pose, battery } = robot;

  return (
    <Link href={`/robots/${robotId}`}>
      <Card className="transition-colors hover:border-primary/40">
        <CardHeader className="flex flex-row items-center justify-between pb-2">
          <CardTitle className="text-sm font-medium">{robotId}</CardTitle>
          <Badge variant={stateBadgeVariant(state, online)}>
            {online ? state : "offline"}
          </Badge>
        </CardHeader>

        <CardContent className="space-y-3">
          {/* 위치 */}
          <div className="flex items-center justify-between text-xs text-muted-foreground">
            <span>Position</span>
            {pose ? (
              <span className="font-mono text-foreground">
                ({pose.x.toFixed(1)}, {pose.y.toFixed(1)})
              </span>
            ) : (
              <span>--</span>
            )}
          </div>

          {/* 배터리 */}
          <div className="space-y-1">
            <div className="flex items-center justify-between text-xs text-muted-foreground">
              <span>Battery</span>
              {battery ? (
                <span className={cn("font-mono", batteryColor(battery.level))}>
                  {battery.level.toFixed(0)}%
                </span>
              ) : (
                <span>--</span>
              )}
            </div>
            {battery && (
              <div className="h-1.5 w-full rounded-full bg-muted">
                <div
                  className={cn(
                    "h-full rounded-full transition-all",
                    battery.level > 60
                      ? "bg-emerald-500"
                      : battery.level > 30
                        ? "bg-amber-500"
                        : "bg-red-500"
                  )}
                  style={{ width: `${Math.min(battery.level, 100)}%` }}
                />
              </div>
            )}
          </div>

          {/* 온라인 표시 */}
          <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <span
              className={cn(
                "h-1.5 w-1.5 rounded-full",
                online ? "bg-emerald-400" : "bg-zinc-500"
              )}
            />
            {online ? "Online" : "Offline"}
          </div>
        </CardContent>
      </Card>
    </Link>
  );
}
