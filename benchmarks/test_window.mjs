import test from 'node:test';
import assert from 'node:assert/strict';
const api = await import('./window.mjs').catch(e => e.code === 'ERR_MODULE_NOT_FOUND' ? {} : Promise.reject(e));
function classify(start, end) {
  assert.equal(typeof api.classifyCompletion, 'function', 'completion classifier must exist');
  return api.classifyCompletion(start, end, 1000, 2000, 1000);
}
for (const [name, start, end, want] of [
  ['warmup straddle is conservatively excluded', 999, 1001, {phase:'warmup'}],
  ['first boundary belongs to first bucket', 1000, 1001, {phase:'window',bucket:0}],
  ['completion chooses its bucket', 1999, 2000, {phase:'window',bucket:1}],
  ['last millisecond still belongs to window', 2998, 2999, {phase:'window',bucket:1}],
  ['exact end is late and cannot inflate successes', 2999, 3000, {phase:'late'}],
  ['start after end is outside', 3000, 3001, {phase:'outside'}],
]) test(name, () => assert.deepEqual(classify(start,end),want));
test('reversed clocks invalidate experiment',()=> {
  assert.equal(typeof api.classifyCompletion,'function');
  assert.throws(()=>api.classifyCompletion(1200,1199,1000,2000,1000),RangeError);
});
test('nonfinite clocks and invalid bucket/window settings are rejected',()=> {
  assert.equal(typeof api.classifyCompletion,'function');
  for(const args of [[NaN,1,0,2,1],[0,Infinity,0,2,1],[0,1,0,0,1],[0,1,-1,2,1],[0,1,0,2,0],[0,1,0,3,2]]) assert.throws(()=>api.classifyCompletion(...args),RangeError);
});
