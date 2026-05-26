"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";
import { useFleetStore } from "@/store/fleetStore";
import { Separator } from "@/components/ui/separator";

export function Sidebar() {
  const pathname = usePathname();
  const robots = useFleetStore((s) => s.robots);
  const realtime = useFleetStore((s) => s.realtime);
  const robotIds = Object.keys(robots).sort();
  const isLive = realtime.state === "live";

  return (
    <aside className="flex h-full w-56 shrink-0 flex-col border-r border-border bg-sidebar text-sidebar-foreground">
      {/* 로고 */}
      <div className="flex h-14 items-center gap-2 px-4">
        <div className="h-7 w-7 rounded-md bg-primary" />
        <span className="text-sm font-semibold tracking-tight">
          Fleet Control
        </span>
      </div>

      <Separator />

      <nav className="flex-1 space-y-1 overflow-y-auto px-2 py-3">
        {/* Fleet Overview */}
        <Link
          href="/fleet"
          className={cn(
            "flex items-center gap-2 rounded-md px-3 py-2 text-sm transition-colors",
            pathname === "/fleet"
              ? "bg-sidebar-accent text-sidebar-accent-foreground"
              : "text-sidebar-foreground/70 hover:bg-sidebar-accent/50"
          )}
        >
          <svg
            className="h-4 w-4"
            fill="none"
            stroke="currentColor"
            strokeWidth={1.5}
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="M3.75 6A2.25 2.25 0 0 1 6 3.75h2.25A2.25 2.25 0 0 1 10.5 6v2.25a2.25 2.25 0 0 1-2.25 2.25H6a2.25 2.25 0 0 1-2.25-2.25V6ZM3.75 15.75A2.25 2.25 0 0 1 6 13.5h2.25a2.25 2.25 0 0 1 2.25 2.25V18a2.25 2.25 0 0 1-2.25 2.25H6A2.25 2.25 0 0 1 3.75 18v-2.25ZM13.5 6a2.25 2.25 0 0 1 2.25-2.25H18A2.25 2.25 0 0 1 20.25 6v2.25A2.25 2.25 0 0 1 18 10.5h-2.25a2.25 2.25 0 0 1-2.25-2.25V6ZM13.5 15.75a2.25 2.25 0 0 1 2.25-2.25H18a2.25 2.25 0 0 1 2.25 2.25V18A2.25 2.25 0 0 1 18 20.25h-2.25a2.25 2.25 0 0 1-2.25-2.25v-2.25Z"
            />
          </svg>
          Fleet Overview
        </Link>

        {/* 로봇 목록 */}
        {robotIds.length > 0 && (
          <>
            <p className="px-3 pt-4 pb-1 text-xs font-medium uppercase tracking-wider text-muted-foreground">
              Robots
            </p>
            {robotIds.map((id) => {
              const r = robots[id];
              const active = pathname === `/robots/${id}`;
              return (
                <Link
                  key={id}
                  href={`/robots/${id}`}
                  className={cn(
                    "flex items-center gap-2 rounded-md px-3 py-2 text-sm transition-colors",
                    active
                      ? "bg-sidebar-accent text-sidebar-accent-foreground"
                      : "text-sidebar-foreground/70 hover:bg-sidebar-accent/50"
                  )}
                >
                  <span
                    className={cn(
                      "h-2 w-2 rounded-full",
                      r.online ? "bg-emerald-400" : "bg-zinc-500"
                    )}
                  />
                  {id}
                </Link>
              );
            })}
          </>
        )}
      </nav>

      {/* 하단 버전 정보 */}
      <div className="space-y-2 border-t border-border px-4 py-3">
        <div className="flex items-center justify-between text-xs">
          <span className="text-muted-foreground">Telemetry</span>
          <span className="flex items-center gap-1.5 font-medium">
            <span
              className={cn(
                "h-2 w-2 rounded-full",
                isLive ? "bg-emerald-400" : "bg-red-400"
              )}
            />
            {isLive ? "Live" : realtime.state}
          </span>
        </div>
        {realtime.lastEventLabel && (
          <p className="truncate text-[11px] text-muted-foreground">
            {realtime.lastEventLabel}
          </p>
        )}
        <p className="text-xs text-muted-foreground">Robot Telemetry v0.1</p>
      </div>
    </aside>
  );
}
