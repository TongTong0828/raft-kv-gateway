#!/usr/bin/env python3
"""Bounded k6 runs; every raw summary is retained and tied to the built image."""
import argparse,base64,json,os,pathlib,platform,subprocess,threading,time
from summary import summarize
p=argparse.ArgumentParser();p.add_argument('--suite',choices=['gateway','kv','isolation','all'],default='gateway');p.add_argument('--rates',default='100,500,1000,2000,4000,8000');p.add_argument('--repeats',type=int,default=3);p.add_argument('--warmup',type=int,default=10);p.add_argument('--seconds',type=int,default=30);p.add_argument('--output',default='work/benchmark-results');args=p.parse_args()
root=pathlib.Path(__file__).resolve().parent.parent;os.chdir(root);out=pathlib.Path(args.output).resolve();out.mkdir(parents=True,exist_ok=True)
project=os.getenv('COMPOSE_PROJECT_NAME','raft-kv-gateway');dc=['docker','compose'];image='grafana/k6:2.3.0@sha256:9c2dee7f8ed74d317e4027c06a10f169b625638189de8d4555d0b3486a5aeb34'
def run(cmd,timeout=180):
    r=subprocess.run(cmd,text=True,capture_output=True,timeout=timeout)
    if r.returncode:raise RuntimeError(f'{cmd}: {r.stderr[-3000:]} {r.stdout[-1000:]}')
    return r.stdout.strip()
def cli(*cmd):return json.loads(run(dc+['run','--rm','-T','cli','-jar','/app/cli.jar',*cmd]))
def seed(service,file):
    try:revision=cli('service','get',service)['revision']
    except RuntimeError:revision='0'
    return cli('service','put','/workspace/'+file,'--expected-revision',revision)
image_info=json.loads(run(['docker','image','inspect','raft-kv-gateway:local']))[0]
metadata={'commit':run(['git','rev-parse','HEAD']),'dirty':bool(run(['git','status','--porcelain'])),'platform':platform.platform(),'machine':platform.machine(),'processor':run(['sysctl','-n','machdep.cpu.brand_string']) if platform.system()=='Darwin' else platform.processor(),'docker':json.loads(run(['docker','info','--format','{{json .}}'])),'image':image_info['Id'],'imageRevision':image_info.get('Config',{}).get('Labels',{}).get('org.opencontainers.image.revision','unknown'),'k6Image':image,'generatorLimits':{'cpus':4,'memoryMiB':768,'preallocatedVUsPerScenario':64,'maxVUsPerScenario':256},'warmupSeconds':args.warmup,'measurementSeconds':args.seconds,'payloadBytes':1024,'compose':json.loads(run(dc+['config','--format','json']))}
if metadata['imageRevision'] not in ['unknown',metadata['commit']]:raise ValueError('Image revision differs from HEAD; rebuild with scripts/dev.sh build')
# Keep the public metadata focused on hardware, versions and limits; omit daemon/user configuration.
metadata['docker']={k:metadata['docker'].get(k) for k in ['ServerVersion','NCPU','MemTotal','Architecture','OperatingSystem']}
for service in metadata['compose']['services'].values():
    if 'build' in service:service['build']['context']='<repository>'
    for volume in service.get('volumes',[]):
        if volume.get('type')=='bind':volume['source']='<repository>'
(out/'environment.json').write_text(json.dumps(metadata,indent=2)+'\n');results=[]
def one(name,rate,repeat,extra=None):
    filename=f'{name}-{rate}-r{repeat}.json';env={'RATE':str(rate),'WARMUP_SECONDS':str(args.warmup),'MEASURE_SECONDS':str(args.seconds),'OUTPUT':filename,'RUN_ID':str(time.time_ns()),**(extra or {})}
    generator='kv-bench-'+env['RUN_ID'];containers=run(dc+['ps','-q']).splitlines()
    command=['docker','run','--rm','--name',generator,'--user','0','--memory','768m','--cpus','4','--network',project+'_app','-v',str(root)+':/workspace:ro','-v',str(out)+':/results']
    for key,value in env.items():command+=['-e',key+'='+value]
    command+=[image,'run','--quiet','/workspace/benchmarks/'+('kv.js' if name.startswith('kv-') else name+'.js')]
    stop=threading.Event();stats=[]
    def sample():
        while not stop.is_set():
            try:stats.append({'time':time.time(),'containers':[json.loads(line) for line in run(['docker','stats','--no-stream','--format','{{json .}}',*containers,generator],timeout=10).splitlines()]})
            except Exception as e:stats.append({'error':str(e)})
            stop.wait(1)
    thread=threading.Thread(target=sample,daemon=True);thread.start();print(f'Running {name}: {rate}/s, repeat {repeat}',flush=True)
    try:
        process=subprocess.run(command,text=True,capture_output=True,timeout=args.warmup+args.seconds+60);log=process.stdout+"\n"+process.stderr
        (out/(filename+".log")).write_text(log)
        if process.returncode:raise RuntimeError(f"k6 exited {process.returncode}: {log[-3000:]}")
    finally:stop.set();thread.join(12)
    (out/(filename+'.log')).write_text(log);(out/(filename+'.stats.json')).write_text(json.dumps(stats,indent=2)+'\n')
    raw=json.loads((out/filename).read_text());result={'scenario':name,'repeat':repeat,'raw':filename,**summarize(raw,rate,args.seconds)};results.append(result);(out/'summary.json').write_text(json.dumps(results,indent=2)+'\n');print(json.dumps(result),flush=True)
if args.suite in ['gateway','all']:
    seed('orders','examples/orders.json')
    for rate in map(int,args.rates.split(',')):
        for repeat in range(1,args.repeats+1):one('gateway',rate,repeat)
if args.suite in ['kv','all']:
    value='x'*1024
    try:cli('kv','put','/bench/read',value)
    except RuntimeError:
        current=cli('kv','get','/bench/read');assert current['entry']['value']==base64.b64encode(value.encode()).decode()
    leader=next(s for s in cli('status') if s['role']=='LEADER')['nodeId']+':50051'
    for mode,rate in [('read',100),('write',25)]:
        for repeat in range(1,args.repeats+1):one('kv-'+mode,rate,repeat,{'KV_TARGET':leader,'KV_MODE':mode,'VALUE_BASE64':base64.b64encode(value.encode()).decode()})
if args.suite in ['isolation','all']:
    try:
        run(dc+['-f','compose.yaml','-f','benchmarks/compose.slow.yaml','up','-d','backend-a']);seed('slow','examples/slow.json');seed('fast','examples/fast.json');time.sleep(2)
        for name in ['slow-backend','isolation']:
            for repeat in range(1,args.repeats+1):one(name,100,repeat)
    finally:run(dc+['up','-d','backend-a'])
