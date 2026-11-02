const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const ts = require('typescript');
function tracker(...args) {
  const filename = path.resolve(__dirname, '../src/lib/commandRecovery.ts');
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const module = new Module(filename);
  module._compile(code, filename);
  return new module.exports.CommandRecovery(...args);
}
const result = (requestId, cmdId) => ({ requestId, cmdId, robotId: 'robot_1', status: 'accepted' });
const ack = (cmdId, status) => ({ cmdId, status, robotId: 'robot_1', message: null, timestamp: '2026-11-02T00:00:00Z' });

test('직접 보낸 요청의 접수 응답만 알려진 명령으로 복구한다', () => {
  const state = tracker();
  assert.equal(state.accepted(result(null, 'foreign')), false);
  state.sent('mine');
  assert.equal(state.accepted(result('other', 'foreign')), false);
  assert.deepEqual(state.pending(), []);
  assert.equal(state.accepted(result('mine', 'cmd')), true);
  assert.deepEqual(state.pending(), ['cmd']);
});
test('종료 Ack 후 지연된 accepted와 다른 종료는 상태를 되돌리지 않는다', () => {
  const state = tracker(); state.sent('mine'); state.accepted(result('mine', 'cmd'));
  assert.equal(state.apply(ack('cmd', 'accepted')), true);
  assert.equal(state.apply(ack('cmd', 'accepted')), false);
  assert.equal(state.apply(ack('foreign', 'done')), false);
  assert.equal(state.apply(ack('cmd', 'done')), true);
  assert.equal(state.apply(ack('cmd', 'accepted')), false);
  assert.equal(state.apply(ack('cmd', 'failed')), false);
  assert.deepEqual(state.pending(), []);
});
test('새 명령 전송 후에도 이전 명령의 종료 Ack를 복구한다', () => {
  const state = tracker(); state.sent('old'); state.accepted(result('old', 'old-cmd'));
  state.sent('new');
  assert.equal(state.apply(ack('old-cmd', 'done')), true);
  assert.deepEqual(state.pending(), []);
  state.accepted(result('new', 'new-cmd'));
  assert.deepEqual(state.pending(), ['new-cmd']);
});
test('접수 응답을 못 받은 명령은 UUID를 추측하거나 재전송하지 않는다', () => {
  const state = tracker(); state.sent('unconfirmed');
  assert.deepEqual(state.pending(), []);
});

test('여러 접수 응답과 Ack가 역순으로 와도 각각 종료까지 추적한다', () => {
  const state = tracker(); state.sent('first'); state.sent('second');
  state.accepted(result('second', 'b')); state.accepted(result('first', 'a'));
  assert.deepEqual(new Set(state.pending()), new Set(['a', 'b']));
  assert.equal(state.apply(ack('b', 'done')), true);
  assert.deepEqual(state.pending(), ['a']);
  assert.equal(state.apply(ack('a', 'done')), true);
  assert.deepEqual(state.pending(), []);
});
test('조회 기한 이후 REST 폴링을 중단하고 늦은 STOMP 종료 Ack는 반영한다', () => {
  let now = 0;
  const state = tracker(() => now, 100, 2);
  state.sent('first'); state.accepted(result('first', 'a'));
  now = 100;
  assert.deepEqual(state.pending(), []);
  assert.equal(state.apply(ack('a', 'done')), true);
});
test('미확인 요청과 미종료 명령의 추적 상한을 지키고 종료 후 슬롯을 회수한다', () => {
  let now = 0;
  const state = tracker(() => now, 100, 2);
  state.sent('first'); state.accepted(result('first', 'a')); state.sent('second');
  assert.equal(state.sent('third'), false);
  now = 100;
  assert.equal(state.canSend(), true);
  assert.equal(state.sent('third'), true);
  assert.equal(state.sent('fourth'), true);
  assert.equal(state.canSend(), false);
  state.apply(ack('a', 'done'));
  now = 200;
  assert.equal(state.canSend(), true);
});

test('종료를 놓친 명령이 상한까지 쌓여도 만료 후 전송 슬롯을 회수한다', () => {
  let now = 0;
  const state = tracker(() => now, 100, 2);
  for (let i = 0; i < 2; i++) { state.sent('r' + i); state.accepted(result('r' + i, 'c' + i)); }
  assert.equal(state.canSend(), false);
  now = 100;
  assert.equal(state.canSend(), true);
  assert.equal(state.sent('new'), true);
  assert.equal(state.apply(ack('c0', 'done')), true);
  assert.equal(state.apply(ack('c0', 'accepted')), false);
});
test('만료된 Ack 이력도 상한을 지키고 최근 이력만 지연 수신한다', () => {
  let now = 0;
  const state = tracker(() => now, 100, 2);
  for (let i = 0; i < 5; i++) {
    state.sent('r' + i); state.accepted(result('r' + i, 'c' + i));
    now += 100; state.pending();
  }
  assert.equal(state.apply(ack('c0', 'done')), false);
  assert.equal(state.apply(ack('c4', 'done')), true);
  assert.equal(state.canSend(), true);
});
