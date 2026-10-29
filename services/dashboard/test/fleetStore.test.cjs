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
