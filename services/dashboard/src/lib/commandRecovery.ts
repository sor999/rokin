import type { AckDto, CommandResult } from "@/types";

export class CommandRecovery {
  private requestId: string | null = null;
  private cmdId: string | null = null;
  private lastStatus: string | null = null;
  private completed = false;

  sent(requestId: string) { this.requestId = requestId; this.cmdId = null; this.completed = false; this.lastStatus = null; }
  accepted(result: CommandResult) {
    if (!this.requestId || result.requestId !== this.requestId) return false;
    this.cmdId = result.cmdId;
    return true;
  }
  pending(): string | null { return this.completed ? null : this.cmdId; }
  current(): string | null { return this.cmdId; }
  apply(ack: AckDto): boolean {
    if (!this.cmdId || ack.cmdId !== this.cmdId || this.completed || ack.status === this.lastStatus) return false;
    this.lastStatus = ack.status;
    if (ack.status === "done" || ack.status === "failed") this.completed = true;
    return true;
  }
}
