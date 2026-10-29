"use client";

import { useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { createSimulation, deleteSimulation } from "@/services/api";
import { useFleetStore } from "@/store/fleetStore";
import type { Simulation } from "@/types";

const labels: Record<Simulation["state"], string> = {
  STARTING: "준비 중", RUNNING: "실행 중", FAILED: "시작 실패", DELETING: "삭제 중", DELETED: "삭제됨",
};

export function AddSimulation() {
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const connected = useFleetStore((s) => s.registryConnected);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const values = new FormData(form);
    setPending(true); setError(null);
    try {
      const created = await createSimulation({ robotId: String(values.get("robotId")),
        startX: Number(values.get("startX")), startY: Number(values.get("startY")), speed: Number(values.get("speed")) });
      useFleetStore.getState().syncSimulations([created]);
      form.reset(); setOpen(false);
    } catch (error) {
      setError(error instanceof Error ? error.message : "로봇 추가 실패");
    } finally { setPending(false); }
  }

  return <div className="space-y-3">
    <Button onClick={() => { setOpen(!open); setError(null); }} disabled={pending || !connected}>
      {open ? "추가 폼 닫기" : "로봇 추가"}
    </Button>
    {open && <Card>
      <CardHeader><CardTitle>가상 로봇 추가</CardTitle></CardHeader>
      <CardContent><form onSubmit={submit} className="space-y-4">
        <p className="text-sm text-muted-foreground">설정은 저장되며 서버 재시작 시 시작 위치에서 다시 실행됩니다.</p>
        <div className="grid gap-3 sm:grid-cols-4">
          <div className="space-y-2"><Label htmlFor="robotId">로봇 ID</Label>
            <Input id="robotId" name="robotId" required pattern="[A-Za-z][A-Za-z0-9_]{0,63}" maxLength={64} placeholder="robot_4" />
          </div>
          <div className="space-y-2"><Label htmlFor="startX">시작 X (m)</Label>
            <Input id="startX" name="startX" type="number" step="any" defaultValue="0" required />
          </div>
          <div className="space-y-2"><Label htmlFor="startY">시작 Y (m)</Label>
            <Input id="startY" name="startY" type="number" step="any" defaultValue="0" required />
          </div>
          <div className="space-y-2"><Label htmlFor="speed">속도 (m/s)</Label>
            <Input id="speed" name="speed" type="number" step="any" min="0" max="10" defaultValue="1" required />
          </div>
        </div>
        <p className="text-xs text-muted-foreground">ID는 영문자로 시작하는 영문·숫자·밑줄이며 삭제 후 재사용할 수 없습니다.</p>
        {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
        <Button type="submit" disabled={pending || !connected}>{pending ? "추가 중…" : "추가"}</Button>
      </form></CardContent>
    </Card>}
  </div>;
}

export function SimulationStatus({ simulation }: { simulation: Simulation }) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const connected = useFleetStore((s) => s.registryConnected);
  async function remove() {
    setPending(true); setError(null);
    try {
      const deleted = await deleteSimulation(simulation.robotId);
      useFleetStore.getState().syncSimulations([deleted]);
      setConfirming(false);
    } catch (error) {
      setError(error instanceof Error ? error.message : "로봇 삭제 실패");
    } finally { setPending(false); }
  }
  return <div className="space-y-2 rounded-b-lg border border-t-0 border-border p-3">
    <div className="flex items-center justify-between gap-2">
      <Badge variant={simulation.state === "FAILED" ? "destructive" : "secondary"}>{labels[simulation.state]}</Badge>
      <Button size="sm" variant="outline" disabled={pending || !connected} onClick={() => setConfirming(true)}>삭제</Button>
    </div>
    {simulation.error && <p role="status" className="text-xs text-muted-foreground">{simulation.error}</p>}
    {confirming && <div role="alertdialog" aria-label={`${simulation.robotId} 삭제 확인`} className="space-y-2">
      <p className="text-sm">{simulation.robotId}의 실행을 종료하고 삭제할까요? 과거 기록은 보존됩니다.</p>
      <div className="flex gap-2">
        <Button size="sm" variant="destructive" onClick={remove} disabled={pending || !connected}>{pending ? "삭제 중…" : "삭제 확인"}</Button>
        <Button size="sm" variant="outline" onClick={() => setConfirming(false)} disabled={pending}>취소</Button>
      </div>
    </div>}
    {error && <p role="alert" className="text-xs text-destructive">{error}</p>}
  </div>;
}
