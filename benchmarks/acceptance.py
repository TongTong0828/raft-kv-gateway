"""Fail-closed validation of fixed-window completion counters, not offered rate."""
import math

def summarize_acceptance(data,rate,seconds,min_rps,p99_ms,bucket_seconds):
    def number(value,name):
        if type(value) not in (int,float) or not math.isfinite(value) or value<0:
            raise ValueError('invalid '+name)
        return value
    for name,value in [('rate',rate),('seconds',seconds),('min_rps',min_rps),('p99_ms',p99_ms),('bucket_seconds',bucket_seconds)]:
        if number(value,name)<=0:raise ValueError('nonpositive '+name)
    if seconds%bucket_seconds or type(seconds) is not int or type(bucket_seconds) is not int:
        raise ValueError('measurement seconds must be divisible by integer bucket seconds')
    if not isinstance(data,dict) or not isinstance(data.get('metrics'),dict):raise ValueError('missing metrics')
    metrics=data['metrics']
    def metric(name,key='count',optional=False):
        item=metrics.get(name)
        if item is None and optional:return 0
        if not isinstance(item,dict) or not isinstance(item.get('values'),dict) or key not in item['values']:
            raise ValueError('missing '+name+'.'+key)
        value=number(item['values'][key],name+'.'+key)
        if key=='count' and value!=int(value):raise ValueError('fractional '+name)
        return int(value) if key=='count' else value
    started=metric('window_started');success=metric('window_success');failed_window=metric('window_failed')
    late_success=metric('late_success');late_failed=metric('late_failed');invalid=metric('invalid_completions')
    samples=metric('window_latency_samples');skew=metric('clock_skew_ms','max')
    buckets=[metric('window_success_b'+str(i).zfill(2)) for i in range(seconds//bucket_seconds)]
    dropped=metric('dropped_iterations',optional=True)
    latency=metric('window_success_latency_ms','p(99)') if success else None
    unaccounted=started-success-failed_window-late_success-late_failed-invalid
    rps=success/seconds;bucket_rates=[n/bucket_seconds for n in buckets];reasons=[]
    if rps<min_rps:reasons.append('window successful throughput is below target')
    if any(r<min_rps for r in bucket_rates):reasons.append('a completion bucket is below target')
    if sum(buckets)!=success:reasons.append('bucket counts do not reconcile with window successes')
    if samples!=success:reasons.append('latency sample count does not match window successes')
    if latency is None or latency>p99_ms:reasons.append('successful full-call P99 is missing or above target')
    if failed_window+late_failed:reasons.append('eligible requests failed, including drain')
    if unaccounted:reasons.append('eligible started requests do not reconcile with outcomes')
    if invalid or skew>1000:reasons.append('measurement clock is invalid')
    return {'passed':not reasons,'reasons':reasons,'offeredRate':rate,'seconds':seconds,'bucketSeconds':bucket_seconds,
            'minimumSuccessfulRps':min_rps,'maximumSuccessP99Ms':p99_ms,'windowStarted':started,'successful':success,
            'successfulRps':rps,'successP99Ms':latency,'windowFailed':failed_window,'lateSuccess':late_success,
            'lateFailed':late_failed,'invalidCompletions':invalid,'failed':failed_window+late_failed,
            'unaccounted':unaccounted,'bucketSuccessful':buckets,'bucketSuccessfulRps':bucket_rates,
            'totalExperimentDropped':dropped,'clockSkewMaxMs':skew,
            'successFullCallLatencyMs':metrics.get('window_success_latency_ms',{}).get('values',{}) if success else {},
            'successTransportLatencyMs':metrics.get('window_transport_ms',{}).get('values',{}),
            'cohortStatuses':{k.removeprefix('cohort_status_'):metric(k) for k in metrics if k.startswith('cohort_status_')}}
