const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const Module = require('node:module');
const path = require('node:path');
const ts = require('typescript');
const filename = path.resolve(__dirname, '../src/lib/simulationValidation.ts');
const mod = new Module(filename);
mod._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS },
}).outputText, filename);
const validate = mod.exports.validateSimulation;
const input = { robotId: 'robot_4', startX: 0, startY: 0, speed: 1 };
test('0·음수·무한대·상한 초과 속도를 제출 전에 거부한다', () => {
  for (const speed of [0, -1, Infinity, NaN, 10.1]) assert.ok(validate({ ...input, speed }));
  for (const speed of [0.0001, 1, 10]) assert.equal(validate({ ...input, speed }), null);
});
test('시작 좌표의 유한성과 양쪽 경계를 서버와 동일하게 검증한다', () => {
  assert.equal(validate({ ...input, startX: -1000000, startY: 1000000 }), null);
  for (const startX of [1000001, -1000001, 1e16, NaN]) assert.ok(validate({ ...input, startX }));
});
