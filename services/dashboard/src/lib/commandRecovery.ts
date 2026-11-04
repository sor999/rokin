import type { AckDto, CommandResult } from "@/types";

type TrackedRequest = {
  cmdId: string | null;
  lastStatus: string | null;
  deadline: number;
};

export class CommandRecovery {
  private readonly requests = new Map<string, TrackedRequest>();
  private readonly expired = new Map<string, string | null>();

  constructor(private readonly now = Date.now, private readonly pollingMillis = 300000,
              private readonly maximum = 100) {}

  private retireExpired() {
    for (const [id, request] of this.requests) {
      if (this.now() < request.deadline) continue;
      if (request.cmdId) this.expired.set(request.cmdId, request.lastStatus);
      this.requests.delete(id);
    }
    while (this.expired.size > this.maximum) this.expired.delete(this.expired.keys().next().value!);
  }
  canSend(): boolean {
    this.retireExpired();
    return this.requests.size < this.maximum;
  }
  sent(requestId: string) {
    if (!this.canSend()) return false;
    this.requests.set(requestId, { cmdId: null, lastStatus: null, deadline: this.now() + this.pollingMillis });
    return true;
  }
  rejected(requestId: string) { this.requests.delete(requestId); }
  accepted(result: CommandResult) {
    if (!result.requestId || !result.cmdId || result.status === "rejected") return false;
    const request = this.requests.get(result.requestId);
    if (!request || (request.cmdId && request.cmdId !== result.cmdId)) return false;
    request.cmdId = result.cmdId;
    return true;
  }
  pending(): string[] {
    this.retireExpired();
    return [...this.requests.values()].filter((request) => request.cmdId && this.now() < request.deadline)
      .map((request) => request.cmdId!);
  }
  apply(ack: AckDto): boolean {
    this.retireExpired();
    for (const [id, request] of this.requests) {
      if (!request.cmdId || request.cmdId !== ack.cmdId) continue;
      if (ack.status === request.lastStatus) return false;
      request.lastStatus = ack.status;
      if (ack.status === "done" || ack.status === "failed") this.requests.delete(id);
      return true;
    }
    if (!this.expired.has(ack.cmdId) || this.expired.get(ack.cmdId) === ack.status) return false;
    if (ack.status === "done" || ack.status === "failed") this.expired.delete(ack.cmdId);
    else this.expired.set(ack.cmdId, ack.status);
    return true;
  }
}
