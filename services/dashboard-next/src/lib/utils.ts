import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/** 배터리 레벨(0~100)에 따른 색상 반환 */
export function batteryColor(level: number): string {
  if (level > 60) return "text-emerald-400";
  if (level > 30) return "text-amber-400";
  return "text-red-400";
}

/** 로봇 상태에 따른 badge variant */
export function stateBadgeVariant(
  state: string,
  online: boolean
): "default" | "secondary" | "destructive" | "outline" {
  if (!online) return "outline";
  switch (state) {
    case "moving":
      return "default";
    case "idle":
      return "secondary";
    case "charging":
      return "secondary";
    default:
      return "outline";
  }
}
