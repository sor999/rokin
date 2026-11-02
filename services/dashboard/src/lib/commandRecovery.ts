import type { AckDto, CommandResult } from "@/types";

type TrackedRequest = {
  cmdId: string | null;
  lastStatus: string | null;
  deadline: number;
};

export class CommandRecovery {
  private readonly requests = new Map<string, TrackedRequest>();

  constructor(private readonly now = Date.now, private readonly pollingMillis = 300000,
              private readonly maximum = 100) {}

  canSend(): boolean {
    for (const [id, request] of this.requests) {
      if (!request.cmdId && this.now() >= request.deadline) this.requests.delete(id);
    }
    return this.requests.size < this.maximum;
  }
  sent(requestId: string) {
    if (!this.canSend()) return false;
    this.requests.set(requestId, { cmdId: null, lastStatus: null, deadline: this.now() + this.pollingMillis });
    return true;
  }
  accepted(result: CommandResult) {
    if (!result.requestId) return false;
    const request = this.requests.get(result.requestId);
    if (!request || (request.cmdId && request.cmdId !== result.cmdId)) return false;
    request.cmdId = result.cmdId;
    return true;
  }
  pending(): string[] {
    return [...this.requests.values()].filter((request) => request.cmdId && this.now() < request.deadline)
      .map((request) => request.cmdId!);
  }
  apply(ack: AckDto): boolean {
    for (const [id, request] of this.requests) {
      if (!request.cmdId || request.cmdId !== ack.cmdId) continue;
      if (ack.status === request.lastStatus) return false;
      request.lastStatus = ack.status;
      if (ack.status === "done" || ack.status === "failed") this.requests.delete(id);
      return true;
    }
    return false;
  }
}
