import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import { classifyCompletion } from './window.mjs';

// Execute the real k6 iteration with controlled executor progress and wall time.
const source = fs.readFileSync(new URL('./acceptance.js',import.meta.url),'utf8')
  .replace(/^import .*;\n/gm,'').replace('export const options','const options')
  .replace('export default function ()','globalThis.iteration = function ()')
  .replace('export function handleSummary','function handleSummary');
function iteration(start,end,wallStart,wallEnd) {
  const samples={},times=[start,end],walls=[wallStart,wallEnd];let index=0;
  class Metric { constructor(name){this.name=name;samples[name]=[];} add(value){samples[this.name].push(value);} }
  const scenario={startTime:0,get progress(){return Math.min(times[index++],3000)/3000;}};
  const instance={get currentTestRunDuration(){return walls[Math.max(0,index-1)] ?? wallEnd;}};
  const context={classifyCompletion,Counter:Metric,Trend:Metric,__ENV:{WARMUP_SECONDS:'1',MEASURE_SECONDS:'2',BUCKET_SECONDS:'1',MIN_SUCCESS_RPS:'1'},exec:{scenario,instance},
    Date:{now:()=>walls.shift()},http:{get:()=>({status:200,body:'x'.repeat(1024),headers:{'X-Backend-Id':'backend-a'},timings:{duration:2}})}};
  vm.runInNewContext(source,context);context.iteration();return samples;
}
test('backward wall correction cannot count a drain completion inside the window',()=>{
  const s=iteration(2999,3001,2499,2501);
  assert.deepEqual(s.window_success,[]);assert.deepEqual(s.late_success,[1]);assert.deepEqual(s.invalid_completions,[]);
});
test('full-call latency and completion bucket use elapsed time despite forward wall correction',()=>{
  const s=iteration(1500,1502,1500,1752);
  assert.deepEqual(s.window_success,[1]);assert.ok(Math.abs(s.window_success_latency_ms[0]-2)<1e-9);
  assert.deepEqual(s.window_success_b00,[1]);
});
test('reversed executor progress invalidates the completion',()=>{
  const s=iteration(1500,1499,1500,1501);
  assert.deepEqual(s.window_success,[]);assert.deepEqual(s.invalid_completions,[1]);
});
