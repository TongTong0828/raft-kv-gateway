"""Convert unmodified k6 exports into measurement-window results."""
def summarize(data,rate,seconds):
    metrics=data.get('metrics',{})
    if 'measured_started' not in metrics:raise ValueError('missing measurement-window counters')
    def value(name,key='count',default=0):return metrics.get(name,{}).get('values',{}).get(key,default)
    started=value('measured_started');success=value('measured_success');dropped=value('dropped_iterations{scenario:measured}')
    return {'offeredRate':rate,'seconds':seconds,'offered':rate*seconds,'started':started,'successful':success,'successfulRps':success/seconds,'failed':started-success,'dropped':dropped,'unaccounted':rate*seconds-started-dropped,'allP99Ms':value('measured_latency','p(99)',None),'successP99Ms':value('measured_success_latency','p(99)',None),'allLatencyMs':metrics.get('measured_latency',{}).get('values',{}),'successLatencyMs':metrics.get('measured_success_latency',{}).get('values',{}),'statuses':{name.removeprefix('measured_'):item['values'].get('count',0) for name,item in metrics.items() if name.startswith('measured_') and (name.removeprefix('measured_').isdigit() or name.endswith('transport_errors'))}}
