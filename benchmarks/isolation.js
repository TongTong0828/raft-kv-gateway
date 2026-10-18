import http from 'k6/http';
import { Counter,Trend } from 'k6/metrics';
import { options as configuration,begin,record,measured,summary } from './common.js';
const slowSuccess=new Counter('slow_success'),slowRejected=new Counter('slow_rejected'),slowLatency=new Trend('slow_latency',true);
export const options=configuration();
export default function(){begin();const base=__ENV.TARGET||'http://gateway:8080';const r=http.batch([{method:'GET',url:base+'/slow/item',params:{timeout:'3s',redirects:0,tags:{name:'slow'}}},{method:'GET',url:base+'/fast/item',params:{timeout:'3s',redirects:0,tags:{name:'fast'}}}]);record(r[1].status,r[1].status===200&&r[1].body.length===1024,r[1].timings.duration);if(measured()){slowSuccess.add(r[0].status===200?1:0);slowRejected.add(r[0].status===503?1:0);slowLatency.add(r[0].timings.duration);}}
export const handleSummary=summary;
