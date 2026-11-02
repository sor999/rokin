const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const ts = require('typescript');
function tracker() {
  const filename = path.resolve(__dirname, '../src/lib/commandRecovery.ts');
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText;
  const module = new Module(filename);
  module._compile(code, filename);
  return new module.exports.CommandRecovery();
}
const result = (requestId, cmdId) => ({ requestId, cmdId, robotId: 'robot_1', status: 'accepted' });
const ack = (cmdId, status) => ({ cmdId, status, robotId: 'robot_1', message: null, timestamp: '2026-11-02T00:00:00Z' });

test('직접 보낸 요청의 접수 응답만 알려진 명령으로 복구한다', () => {
  const state = tracker();
  assert.equal(state.accepted(result(null, 'foreign')), false);
  state.sent('mine');
  assert.equal(state.accepted(result('other', 'foreign')), false);
  assert.equal(state.pending(), null);
  assert.equal(state.accepted(result('mine', 'cmd')), true);
  assert.equal(state.pending(), 'cmd');
});
test('종료 Ack 후 지연된 accepted와 다른 종료는 상태를 되돌리지 않는다', () => {
  const state = tracker(); state.sent('mine'); state.accepted(result('mine', 'cmd'));
  assert.equal(state.apply(ack('cmd', 'accepted')), true);
  assert.equal(state.apply(ack('cmd', 'accepted')), false);
  assert.equal(state.apply(ack('foreign', 'done')), false);
  assert.equal(state.apply(ack('cmd', 'done')), true);
  assert.equal(state.apply(ack('cmd', 'accepted')), false);
  assert.equal(state.apply(ack('cmd', 'failed')), false);
  assert.equal(state.pending(), null);
});
test('새 명령 전송 후 이전 REST 조회 응답을 적용하지 않는다', () => {
  const state = tracker(); state.sent('old'); state.accepted(result('old', 'old-cmd'));
  state.sent('new');
  assert.equal(state.apply(ack('old-cmd', 'done')), false);
  assert.equal(state.pending(), null);
  state.accepted(result('new', 'new-cmd'));
  assert.equal(state.pending(), 'new-cmd');
});
test('접수 응답을 못 받은 명령은 UUID를 추측하거나 재전송하지 않는다', () => {
  const state = tracker(); state.sent('unconfirmed');
  assert.equal(state.pending(), null);
});
