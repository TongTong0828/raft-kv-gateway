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
function iteration(start,end,wallStart,wallEnd,response) {
  const samples={},times=[start,end],walls=[wallStart,wallEnd];let index=0;
  class Metric { constructor(name){this.name=name;samples[name]=[];} add(value){samples[this.name].push(value);} }
  const scenario={startTime:0,get progress(){return Math.min(times[index++],3000)/3000;}};
  const instance={get currentTestRunDuration(){return walls[Math.max(0,index-1)] ?? wallEnd;}};
  const context={classifyCompletion,Counter:Metric,Trend:Metric,__ENV:{WARMUP_SECONDS:'1',MEASURE_SECONDS:'2',BUCKET_SECONDS:'1',MIN_SUCCESS_RPS:'1'},exec:{scenario,instance},
    Date:{now:()=>walls.shift()},http:{get:()=>response || ({status:200,body:'x'.repeat(1024),headers:{'X-Backend-Id':'backend-a'},timings:{duration:2}})}};
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
test('eligible503 records a fixed rejection category and unknown headers remain unclassified',()=>{
  for (const [header,want] of [['pool_queue_full','pool_queue_full'],['admission_limit','admission_limit'],['invented','unclassified'],['__proto__','unclassified'],['constructor','unclassified']]) {
    const s=iteration(1500,1502,1500,1502,{status:503,body:'',headers:{'X-Gateway-Rejection':header},timings:{duration:2}});
    assert.deepEqual(s['cohort_rejection_'+want],[1]);assert.deepEqual(s.window_failed,[1]);
  }
});
test('drain503 reason belongs to the same eligible cohort as late failures',()=>{
  const s=iteration(2999,3001,2999,3001,{status:503,body:'',headers:{'X-Gateway-Rejection':'pool_timeout'},timings:{duration:2}});
  assert.deepEqual(s.cohort_rejection_pool_timeout,[1]);assert.deepEqual(s.late_failed,[1]);
  assert.deepEqual(s.window_failed,[]);
});
