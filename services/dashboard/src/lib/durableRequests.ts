export type DurableRequest = { requestId: string; idempotencyKey: string; cmdId?: string };

/** Store request identities before sending; never retain or automatically resend command payloads. */
export class DurableRequests {
  private readonly entries = new Map<string, DurableRequest>();
  constructor(private readonly storage: Pick<Storage, "getItem" | "setItem">, private readonly key: string) {
    try {
      const saved: unknown = JSON.parse(storage.getItem(key) ?? "[]");
      if (Array.isArray(saved)) for (const item of saved.slice(0, 100)) {
        if (item && typeof item.requestId === "string" && typeof item.idempotencyKey === "string") {
          this.entries.set(item.requestId, item);
        }
      }
    } catch { /* A damaged browser record cannot authorize a new command. */ }
  }
  all(): DurableRequest[] { return [...this.entries.values()]; }
  add(requestId: string, idempotencyKey: string): boolean {
    if (this.entries.size >= 100) return false;
    this.entries.set(requestId, { requestId, idempotencyKey });
    try { this.save(); return true; }
    catch { this.entries.delete(requestId); return false; }
  }
  accepted(requestId: string, cmdId: string) {
    const entry = this.entries.get(requestId);
    if (entry && (!entry.cmdId || entry.cmdId === cmdId)) { entry.cmdId = cmdId; this.save(); }
  }
  completed(cmdId: string) {
    for (const [id, entry] of this.entries) if (entry.cmdId === cmdId) this.entries.delete(id);
    this.save();
  }
  rejected(requestId: string, errorCode?: string | null): boolean {
    if (errorCode === "STORAGE_UNAVAILABLE" || errorCode === "DELIVERY_UNCERTAIN") return false;
    this.finish(requestId);
    return true;
  }
  finish(requestId: string) { this.entries.delete(requestId); this.save(); }
  private save() { this.storage.setItem(this.key, JSON.stringify(this.all())); }
}
