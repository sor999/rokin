import type { AckDto } from "@/types";
import type { CommandRecovery } from "./commandRecovery";

export async function pollCommandAcks(
  recovery: CommandRecovery,
  lookup: (id: string, signal: AbortSignal) => Promise<AckDto | null>,
  receive: (ack: AckDto) => void,
  signal: AbortSignal,
): Promise<void> {
  const ids = recovery.pending();
  let cursor = 0;
  await Promise.all(Array.from({ length: Math.min(4, ids.length) }, async () => {
    while (!signal.aborted && cursor < ids.length) {
      const id = ids[cursor++];
      if (!recovery.pending().includes(id)) continue;
      try {
        const ack = await lookup(id, AbortSignal.any([signal, AbortSignal.timeout(3000)]));
        if (!signal.aborted && ack && recovery.apply(ack)) receive(ack);
      } catch { /* 개별 조회 실패는 다른 명령의 복구를 막지 않는다. */ }
    }
  }));
}
