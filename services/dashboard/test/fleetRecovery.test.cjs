const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const ts = require('typescript');

test('SSE 재연결은 REST 상태를 복구하고 이전 연결의 응답과 종료 후 이벤트는 버린다', async () => {
  const previous = global.EventSource;
  const pending = [], snapshots = [], updates = [], states = [];
  let cleanup, source;
  class FakeSource {
    listeners = {};
    constructor() { source = this; }
    addEventListener(name, callback) { this.listeners[name] = callback; }
    close() { this.closed = true; }
  }
  global.EventSource = FakeSource;
  const store = {
    upsertFromSSE: (value) => updates.push(value),
    setRealtimeState: (value) => states.push(value),
    initFromRest: (value) => snapshots.push(value),
  };
  const useStore = (selector) => selector(store);
  useStore.getState = () => store;
  const filename = path.resolve(__dirname, '../src/hooks/useFleetSSE.ts');
  const module = new Module(filename);
  module.require = (name) => {
    if (name === 'react') return { useEffect: (effect) => { cleanup = effect(); }, useRef: () => ({ current: null }) };
    if (name === '@/store/fleetStore') return { useFleetStore: useStore };
    if (name === '@/services/api') return { fetchRobots: (signal) => new Promise(resolve => pending.push({ signal, resolve })) };
    throw new Error(name);
  };
  try {
    module._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
    }).outputText, filename);
    module.exports.useFleetSSE();
    source.onopen(); source.onopen();
    assert.equal(pending[0].signal.aborted, true);
    pending[0].resolve(['old']); pending[1].resolve(['current']);
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(snapshots, [['current']]);
    source.onopen(); source.onerror();
    assert.equal(pending[2].signal.aborted, true);
    pending[2].resolve(['disconnected']);
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(snapshots, [['current']]);
    cleanup();
    const count = states.length;
    source.onopen(); source.onerror(); source.listeners.robot_update({ data: '{}' });
    assert.equal(states.length, count);
    assert.deepEqual(updates, []);
    assert.equal(source.closed, true);
  } finally { cleanup?.(); global.EventSource = previous; }
});

test('503으로 닫힌 SSE를 새 연결로 재시도하고 이전 연결의 콜백은 무시한다', async () => {
  const previous = { source: global.EventSource, setTimeout: global.setTimeout, clearTimeout: global.clearTimeout };
  const sources = [], timers = [], updates = [], snapshots = [];
  let cleanup;
  class FakeSource {
    listeners = {};
    constructor() { sources.push(this); }
    addEventListener(name, callback) { this.listeners[name] = callback; }
    close() { this.closed = true; }
  }
  global.EventSource = FakeSource;
  global.setTimeout = (callback, delay) => { const timer = { callback, delay }; timers.push(timer); return timer; };
  global.clearTimeout = (timer) => { if (timer) timer.cancelled = true; };
  const store = { upsertFromSSE: value => updates.push(value), setRealtimeState: () => {},
    initFromRest: value => snapshots.push(value) };
  const useStore = selector => selector(store);
  useStore.getState = () => store;
  const filename = path.resolve(__dirname, '../src/hooks/useFleetSSE.ts');
  const module = new Module(filename);
  module.require = name => {
    if (name === 'react') return { useEffect: effect => { cleanup = effect(); }, useRef: () => ({ current: null }) };
    if (name === '@/store/fleetStore') return { useFleetStore: useStore };
    if (name === '@/services/api') return { fetchRobots: async () => ['shared'] };
    throw new Error(name);
  };
  try {
    module._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
    }).outputText, filename);
    module.exports.useFleetSSE();
    sources[0].onerror(); sources[0].onerror();
    assert.equal(sources[0].closed, true);
    assert.equal(timers.length, 1);
    assert.equal(timers[0].delay, 2000);
    timers[0].callback();
    assert.equal(sources.length, 2);
    sources[0].onopen(); sources[0].listeners.robot_update({ data: '{"robotId":"old"}' });
    sources[1].onopen();
    sources[1].listeners.robot_update({ data: '{"robotId":"current"}' });
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(snapshots, [['shared']]);
    assert.deepEqual(updates, [{ robotId: 'current' }]);
    sources[1].onerror();
    cleanup();
    assert.equal(timers[1].cancelled, true);
    timers[1].callback();
    assert.equal(sources.length, 2);
  } finally {
    cleanup?.(); global.EventSource = previous.source;
    global.setTimeout = previous.setTimeout; global.clearTimeout = previous.clearTimeout;
  }
});
