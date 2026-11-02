const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const ts = require('typescript');
function read(name) {
  const filename = path.resolve(__dirname, '../src/lib/' + name + '.ts');
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
  } }).outputText;
  const module = new Module(filename); module._compile(code, filename); return module.exports;
}
const { CommandRecovery } = read('commandRecovery');
const { pollCommandAcks } = read('pollCommandAcks');
function tracker() {
  const state = new CommandRecovery();
  for (let i = 0; i < 100; i++) {
    state.sent('r' + i); state.accepted({ requestId: 'r' + i, cmdId: 'c' + i });
  }
  return state;
}
const ack = (id) => ({ cmdId: id, status: 'done' });

test('앞선 조회가 지연·실패해도 최대 4개 동시 조회로 모든 100개 명령을 복구한다', async () => {
  const state = tracker();
  let running = 0, maximum = 0;
  const received = [], visited = [];
  await pollCommandAcks(state, async (id) => {
    visited.push(id); maximum = Math.max(maximum, ++running);
    await new Promise((resolve) => setImmediate(resolve));
    running--;
    if (id === 'c0') throw new Error('temporary failure');
    return ack(id);
  }, (value) => received.push(value.cmdId), new AbortController().signal);
  assert.equal(maximum, 4);
  assert.equal(visited.length, 100);
  assert.equal(new Set(visited).size, 100);
  assert.equal(received.length, 99);
  assert.deepEqual(state.pending(), ['c0']);
});
test('연결 종료 뒤 늦은 응답을 반영하거나 새 조회를 시작하지 않는다', async () => {
  const state = tracker(), controller = new AbortController(), releases = [], received = [];
  const polling = pollCommandAcks(state, (id) => new Promise((resolve) => releases.push(() => resolve(ack(id)))),
    (value) => received.push(value), controller.signal);
  assert.equal(releases.length, 4);
  controller.abort(); releases.forEach((release) => release()); await polling;
  assert.equal(releases.length, 4);
  assert.deepEqual(received, []);
  assert.equal(state.pending().length, 100);
});
