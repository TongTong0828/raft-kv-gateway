import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
const started=new Counter('measured_started'),success=new Counter('measured_success'),transport=new Counter('measured_transport_errors');
const latency=new Trend('measured_latency',true),successLatency=new Trend('measured_success_latency',true);
const statuses={};for(const code of [200,400,404,413,417,500,501,502,503,504])statuses[code]=new Counter('measured_'+code);
export function options(){const rate=Number(__ENV.RATE||100),warm=Number(__ENV.WARMUP_SECONDS||10),duration=Number(__ENV.MEASURE_SECONDS||30);const scenarios={measured:{executor:'constant-arrival-rate',rate,timeUnit:'1s',duration:duration+'s',startTime:warm+'s',preAllocatedVUs:64,maxVUs:256,gracefulStop:'3s'}};if(warm>0)scenarios.warmup={executor:'constant-arrival-rate',rate,timeUnit:'1s',duration:warm+'s',preAllocatedVUs:64,maxVUs:256,gracefulStop:'0s'};return {scenarios,summaryTrendStats:['avg','min','med','max','p(90)','p(95)','p(99)'],thresholds:{'dropped_iterations{scenario:measured}':['count>=0']},systemTags:['status','method','name','scenario','expected_response']};}
export function measured(){return exec.scenario.name==='measured';}
export function begin(){if(measured())started.add(1);}
export function record(status,ok,ms){if(!measured())return;latency.add(ms);success.add(ok?1:0);transport.add(status===0?1:0);if(statuses[status])statuses[status].add(1);if(ok)successLatency.add(ms);}
export function request(path='/orders/item'){begin();const response=http.get((__ENV.TARGET||'http://gateway:8080')+path,{timeout:'3s',redirects:0,tags:{name:path},responseType:'text'});record(response.status,response.status===200&&response.body.length===1024&&Boolean(response.headers['X-Backend-Id']),response.timings.duration);}
export function summary(data){return {['/results/'+(__ENV.OUTPUT||'raw.json')]:JSON.stringify(data,null,2)};}
