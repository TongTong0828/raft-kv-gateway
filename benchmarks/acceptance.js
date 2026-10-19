import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { classifyCompletion } from './window.mjs';

const warm = Number(__ENV.WARMUP_SECONDS || 30), seconds = Number(__ENV.MEASURE_SECONDS || 300);
const bucketSeconds = Number(__ENV.BUCKET_SECONDS || 10), minimum = Number(__ENV.MIN_SUCCESS_RPS || 8000);
const p99 = Number(__ENV.MAX_P99_MS || 15), vus = Number(__ENV.FIXED_VUS || 256);
const rate = Number(__ENV.RATE || 8400);
if (![warm,seconds,bucketSeconds,minimum,p99,vus,rate].every(Number.isFinite) || warm < 0 || seconds <= 0 ||
    bucketSeconds <= 0 || seconds % bucketSeconds || minimum <= 0 || p99 <= 0 || !Number.isInteger(vus) || vus <= 0 ||
    !Number.isInteger(rate) || rate <= 0) throw new Error('invalid acceptance settings');
const counts = {};
for (const name of ['window_started','window_success','window_failed','late_success','late_failed','invalid_completions','window_latency_samples']) counts[name] = new Counter(name);
const buckets = Array.from({length:seconds/bucketSeconds}, (_, i) => new Counter('window_success_b'+String(i).padStart(2,'0')));
const successLatency = new Trend('window_success_latency_ms',true), transportLatency = new Trend('window_transport_ms',true);
const allLatency = new Trend('window_all_full_call_ms',true);
const statuses = {};
for (const code of [200,400,404,413,417,500,501,502,503,504,0]) statuses[code] = new Counter('cohort_status_'+code);
const otherStatus = new Counter('cohort_status_other'), badPayload = new Counter('cohort_invalid_payload');
const thresholds = {window_started:['count>=0'],window_success:['count>='+minimum*seconds],window_failed:['count==0'],
  late_success:['count>=0'],late_failed:['count==0'],invalid_completions:['count==0'],window_latency_samples:['count>=0'],
  window_success_latency_ms:['p(99)<='+p99],dropped_iterations:['count>=0']};
for (let i=0;i<buckets.length;i++) thresholds['window_success_b'+String(i).padStart(2,'0')] = ['count>='+minimum*bucketSeconds];
export const options = {scenarios:{sustained:{executor:'constant-arrival-rate',rate,timeUnit:'1s',duration:(warm+seconds)+'s',
  preAllocatedVUs:vus,maxVUs:vus,gracefulStop:'5s'}},thresholds,
  summaryTrendStats:['avg','min','med','max','p(90)','p(95)','p(99)'],
  systemTags:['status','method','name','scenario','expected_response']};
const payload = 'x'.repeat(1024);
export default function () {
  // Pinned constant-arrival executor computes progress with Go time.Since.
  // It is monotonic and clamps at 1, so drain completions land at the deadline.
  const start = exec.scenario.progress*(warm+seconds)*1000;
  let initial;
  try { initial = classifyCompletion(start,start,warm*1000,seconds*1000,bucketSeconds*1000); }
  catch (_) { counts.invalid_completions.add(1); return; }
  if (initial.phase === 'outside') return;
  const eligible = initial.phase === 'window';
  if (eligible) counts.window_started.add(1);
  let response;
  try {
    response = http.get((__ENV.TARGET || 'http://gateway:8080')+'/orders/item', {
      timeout:'3s',redirects:0,responseType:'text',headers:{'Accept-Encoding':'identity'},tags:{name:'orders-acceptance'}});
  } catch (_) { response = {status:0,body:null,headers:{},timings:{duration:0}}; }
  const end = exec.scenario.progress*(warm+seconds)*1000;
  let completed;
  try { completed = classifyCompletion(start,end,warm*1000,seconds*1000,bucketSeconds*1000); }
  catch (_) { counts.invalid_completions.add(1); return; }
  if (!eligible) return;
  const good = response.status === 200 && response.body === payload &&
    ['backend-a','backend-b'].includes(response.headers['X-Backend-Id']);
  (statuses[response.status] || otherStatus).add(1);
  if (response.status === 200 && !good) badPayload.add(1);
  if (completed.phase === 'late') {
    (good ? counts.late_success : counts.late_failed).add(1);
    return;
  }
  allLatency.add(end-start);
  if (!good) { counts.window_failed.add(1); return; }
  counts.window_success.add(1);
  buckets[completed.bucket].add(1);
  successLatency.add(end-start);
  transportLatency.add(response.timings.duration);
  counts.window_latency_samples.add(1);
}
export function handleSummary(data) {
  return {['/results/'+(__ENV.OUTPUT || 'acceptance.json')]:JSON.stringify(data,null,2)};
}
