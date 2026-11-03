const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const ts = require('typescript');

function store() {
  const filename = path.resolve(__dirname, '../src/store/fleetStore.ts');
  const compiled = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
  }).outputText;
  const module = new Module(filename);
  module.paths = Module._nodeModulePaths(path.dirname(filename));
  module._compile(compiled, filename);
  return module.exports.useFleetStore;
}
const simulation = (overrides = {}) => ({ robotId: 'robot_4', fleetId: 'default', startX: 0, startY: 0,
  speed: 1, version: 1, desiredState: 'RUNNING', state: 'STARTING', error: null,
  runtimeId: null, reportedAt: null, ...overrides });
const pose = (robotId = 'robot_4') => ({ robotId, type: 'pose', timestamp: '2026-10-29T00:00:00Z', data: { x: 2, y: 3 } });

test('등록 즉시 위치를 조작하지 않고 준비 중 카드를 만든다', () => {
  const state = store();
  state.getState().syncSimulations([simulation()]);
  assert.equal(state.getState().robots.robot_4.lastSeen, null);
  assert.equal(state.getState().robots.robot_4.pose, null);
  assert.equal(state.getState().robots.robot_4.online, false);
});
test('삭제 후 SSE·REST·이력·오래된 등록 응답이 로봇을 되살리지 않는다', () => {
  const state = store();
  state.getState().syncSimulations([simulation()]);
  state.getState().upsertFromSSE(pose());
  state.getState().syncSimulations([simulation({ desiredState: 'DELETED', version: 2, state: 'DELETING' })]);
  state.getState().upsertFromSSE(pose());
  state.getState().initTrail('robot_4', [{ x: 3, y: 4 }]);
  state.getState().initFromRest([{ robotId: 'robot_4', online: true, lastSeen: pose().timestamp,
    pose: { x: 1, y: 1, timestamp: pose().timestamp }, battery: null, state: 'idle' }]);
  state.getState().syncSimulations([simulation()]);
  assert.deepEqual(state.getState().robots, {});
});
test('다른 로봇 추가·목록 조회가 기존 위치와 궤적을 초기화하지 않는다', () => {
  const state = store();
  state.getState().syncSimulations([simulation({ state: 'RUNNING' })]);
  state.getState().upsertFromSSE(pose());
  state.getState().syncSimulations([simulation({ robotId: 'robot_5' }), simulation({ state: 'RUNNING' })]);
  assert.equal(state.getState().robots.robot_4.pose.x, 2);
  assert.equal(state.getState().robots.robot_4.trail.length, 1);
});
test('재시작으로 실행 인스턴스가 바뀌면 과거 온라인·위치를 초기화한다', () => {
  const state = store();
  state.getState().syncSimulations([simulation({ runtimeId: 'old', state: 'RUNNING' })]);
  state.getState().upsertFromSSE({ ...pose(), runtimeId: 'old' });
  state.getState().syncSimulations([simulation({ runtimeId: 'new' })]);
  assert.equal(state.getState().robots.robot_4.pose, null);
  assert.equal(state.getState().robots.robot_4.online, false);
});

test('새 runtime 이후 도착한 이전 실행의 SSE와 REST를 거부한다', () => {
  const state = store();
  state.getState().syncSimulations([simulation({ runtimeId: 'new' })]);
  state.getState().upsertFromSSE({ ...pose(), runtimeId: 'old' });
  state.getState().initFromRest([{ robotId: 'robot_4', runtimeId: 'old', online: true,
    lastSeen: pose().timestamp, pose: {x: 9, y: 9, timestamp: pose().timestamp}, battery: null, state: 'idle' }]);
  state.getState().initTrail('robot_4', [{x: 10, y: 10}], 'old');
  assert.equal(state.getState().robots.robot_4.pose, null);
  assert.deepEqual(state.getState().robots.robot_4.trail, []);
  state.getState().upsertFromSSE({ ...pose(), runtimeId: 'new' });
  assert.equal(state.getState().robots.robot_4.pose.x, 2);
});

test('첫 등록 스냅샷은 이전 REST 실행의 위치·배터리·궤적을 지운다', () => {
  const state = store();
  state.getState().initFromRest([{ robotId: 'robot_4', runtimeId: 'old', online: true,
    lastSeen: pose().timestamp, pose: {x: 99, y: 99, timestamp: pose().timestamp}, battery: {level: 1}, state: 'moving' }]);
  state.getState().syncSimulations([simulation({ runtimeId: 'new', state: 'RUNNING' })]);
  assert.equal(state.getState().robots.robot_4.pose, null);
  assert.equal(state.getState().robots.robot_4.battery, null);
  assert.deepEqual(state.getState().robots.robot_4.trail, []);
});
test('첫 스냅샷과 일치하는 데이터는 보존하고 늦은 생성 응답의 null runtime은 거부한다', () => {
  const state = store();
  state.getState().upsertFromSSE({ ...pose(), runtimeId: 'new' });
  state.getState().syncSimulations([simulation({ runtimeId: 'new', state: 'RUNNING' })]);
  assert.equal(state.getState().robots.robot_4.pose.x, 2);
  state.getState().syncSimulations([simulation()]);
  assert.equal(state.getState().simulations.robot_4.runtimeId, 'new');
  assert.equal(state.getState().robots.robot_4.pose.x, 2);
});


test('연결 장애 시 온라인을 해제하고 같은 timestamp의 공유 스냅샷으로 복구한다', () => {
  const state = store();
  const event = pose();
  state.getState().upsertFromSSE(event);
  state.getState().setRealtimeState('error', 'SSE connection error');
  assert.equal(state.getState().robots.robot_4.online, false);
  assert.equal(state.getState().robots.robot_4.pose.x, 2);
  state.getState().setRealtimeState('live');
  assert.equal(state.getState().robots.robot_4.online, false);
  state.getState().initFromRest([{ robotId: 'robot_4', online: true, lastSeen: event.timestamp,
    pose: { x: 2, y: 3, timestamp: event.timestamp }, battery: null, state: 'idle' }]);
  assert.equal(state.getState().robots.robot_4.online, true);
});


test('복구 조회 중 받은 SSE의 온라인 판정을 같은 timestamp의 늦은 REST가 덮어쓰지 않는다', () => {
  const state = store();
  const event = pose();
  state.getState().upsertFromSSE(event);
  state.getState().initFromRest([{ robotId: 'robot_4', online: false, lastSeen: event.timestamp,
    pose: { x: 2, y: 3, timestamp: event.timestamp }, battery: null, state: 'idle' }], new Set(['robot_4']));
  assert.equal(state.getState().robots.robot_4.online, true);
});

test('이전 pose와 중복 pose는 위치, 궤적, 마지막 관측 시각을 되돌리지 않는다', () => {
  const state = store();
  const current = { ...pose(), timestamp: '2026-11-03T01:00:02.000Z' };
  assert.equal(state.getState().upsertFromSSE(current), true);
  assert.equal(state.getState().upsertFromSSE(current), false);
  assert.equal(state.getState().upsertFromSSE({ ...pose(), timestamp: '2026-11-03T01:00:01Z', data: {x: 99, y: 99} }), false);
  assert.equal(state.getState().robots.robot_4.pose.x, 2);
  assert.equal(state.getState().robots.robot_4.trail.length, 1);
  assert.equal(state.getState().robots.robot_4.lastSeen, current.timestamp);
});

test('늦은 오프라인은 더 최신 관측을 덮지 않고 같은 관측의 만료만 적용한다', () => {
  const state = store();
  const current = { ...pose(), timestamp: '2026-11-03T01:00:02Z' };
  state.getState().upsertFromSSE(current);
  assert.equal(state.getState().upsertFromSSE({ ...current, type: 'offline', timestamp: '2026-11-03T01:00:01Z' }), false);
  assert.equal(state.getState().robots.robot_4.online, true);
  assert.equal(state.getState().upsertFromSSE({ ...current, type: 'offline' }), true);
  assert.equal(state.getState().robots.robot_4.online, false);
});

test('필터된 runtime의 이벤트는 복구 보호 대상이 되지 않는다', () => {
  const state = store();
  state.getState().syncSimulations([simulation({ runtimeId: 'current', state: 'RUNNING' })]);
  assert.equal(state.getState().upsertFromSSE({ ...pose(), runtimeId: 'retired' }), false);
  state.getState().upsertFromSSE({ ...pose(), runtimeId: 'current' });
  state.getState().setRealtimeState('error');
  state.getState().initFromRest([{ robotId: 'robot_4', runtimeId: 'current', online: true,
    lastSeen: pose().timestamp, pose: {x:2,y:3,timestamp:pose().timestamp}, battery: null, state: 'idle' }], new Set());
  assert.equal(state.getState().robots.robot_4.online, true);
});

test('복구 중 수신한 상태와 배터리는 더 최신 pose의 REST 응답으로 교체하지 않는다', () => {
  const state = store();
  const timestamp = '2026-11-03T01:00:01Z';
  state.getState().upsertFromSSE({ ...pose(), type: 'battery', timestamp, data: {level: 80} });
  state.getState().upsertFromSSE({ ...pose(), type: 'status', timestamp, data: {state: 'moving'} });
  state.getState().initFromRest([{ robotId:'robot_4', online:true, lastSeen:'2026-11-03T01:00:02Z',
    pose:{x:5,y:6,timestamp:'2026-11-03T01:00:02Z'}, battery:{level:20,timestamp}, state:'idle' }], new Set(['robot_4']));
  assert.equal(state.getState().robots.robot_4.state, 'moving');
  assert.equal(state.getState().robots.robot_4.battery.level, 80);
  assert.equal(state.getState().robots.robot_4.pose.x, 5);
});
