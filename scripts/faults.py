#!/usr/bin/env python3
"""Owns an isolated Compose project. Assertions use live RPC/HTTP/status evidence."""
import argparse,concurrent.futures,json,os,pathlib,select,socket,subprocess,time,urllib.request
from fault_ownership import claim_project,release_lease
parser=argparse.ArgumentParser();parser.add_argument('--project',default='kv-fault-'+str(os.getpid()));parser.add_argument('--output',default='work/fault-results.json');args=parser.parse_args()
ROOT=pathlib.Path(__file__).resolve().parent.parent;os.chdir(ROOT)
def free_port():
    with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
env=os.environ.copy();env['COMPOSE_PROJECT_NAME']=args.project
for key in ['KV_HTTP_PORT','KV_GATEWAY_ADMIN','KV_CP1_PORT','KV_CP2_PORT','KV_CP3_PORT','KV_CP1_ADMIN','KV_CP2_ADMIN','KV_CP3_ADMIN']:env[key]=str(free_port())
dc=['docker','compose','-p',args.project];events=[];disconnected=None;owned=False;lease=None
def run(cmd,timeout=120):
    r=subprocess.run(cmd,env=env,text=True,capture_output=True,timeout=timeout)
    if r.returncode:raise RuntimeError(f'{cmd}: {r.stderr[-4000:]} {r.stdout[-1000:]}')
    return r.stdout
def compose(*cmd,timeout=120):return run(dc+list(cmd),timeout)
def scenario(mode,*options,timeout=120):return json.loads(compose('run','--rm','-T','cli','-jar','/app/integration-tests.jar',mode,*options,timeout=timeout).strip())
def cli(*cmd):return json.loads(compose('run','--rm','-T','cli','-jar','/app/cli.jar',*cmd).strip())
def http(port,path,method='GET'):
    with urllib.request.urlopen(urllib.request.Request(f'http://127.0.0.1:{port}{path}',method=method),timeout=35) as response:return response.read()
def status(node):return json.loads(http(env['KV_'+node.upper()+'_ADMIN'],'/status'))
def metrics():return dict(line.split(' ',1) for line in http(env['KV_GATEWAY_ADMIN'],'/metrics').decode().splitlines() if '{' not in line)
def until(check,timeout=30):
    end=time.monotonic()+timeout;last=None
    while time.monotonic()<end:
        try:
            last=check()
            if last:return last
        except (OSError,RuntimeError,KeyError):pass
        time.sleep(.1)
    raise AssertionError(f'condition timed out: {last}')
def record(name,**data):events.append({'test':name,'time':time.time(),**data});print(json.dumps(events[-1]),flush=True)
def gateway():return http(env['KV_HTTP_PORT'],'/orders/item')
try:
    lease=claim_project(args.project);owned=True
    compose('up','-d','--wait','--wait-timeout','120')
    seed=cli('service','put','/workspace/examples/orders.json');revision=int(seed['revision']);until(lambda:len(gateway())==1024);record('initial-routing',revision=revision)
    history=scenario('history');assert history['linearizable'];record('bounded-history',**history)
    # Active traffic continues through a full-snapshot route update.
    def traffic():
        ids=[]
        for _ in range(60):
            with urllib.request.urlopen(f"http://127.0.0.1:{env['KV_HTTP_PORT']}/orders/item",timeout=3) as r:assert len(r.read())==1024;ids.append(r.headers['X-Backend-Id'])
            time.sleep(.02)
        return ids
    with concurrent.futures.ThreadPoolExecutor(1) as executor:
        running=executor.submit(traffic);time.sleep(.2)
        single=ROOT/'work'/'fault-single.json';single.parent.mkdir(exist_ok=True);single.write_text(json.dumps({'serviceId':'orders','pathPrefix':'/orders','endpoints':['backend-b:8081']}))
        changed=cli('service','put','/workspace/work/fault-single.json','--expected-revision',str(revision));revision=int(changed['revision']);ids=running.result()
    until(lambda:int(float(metrics()['gateway_config_revision']))>=revision)
    for _ in range(5):
        with urllib.request.urlopen(f"http://127.0.0.1:{env['KV_HTTP_PORT']}/orders/item") as r:assert r.headers['X-Backend-Id']=='backend-b'
    record('atomic-route-update',revision=revision,successfulRequests=len(ids),observedBackends=sorted(set(ids)))
    compose('stop','cp1','cp2','cp3');time.sleep(3);assert len(gateway())==1024;record('cached-route-outage',revision=int(float(metrics()['gateway_config_revision'])));compose('start','cp1','cp2','cp3');until(lambda:len(cli('status'))==3)
    leader=until(lambda:next((n for n in ['cp1','cp2','cp3'] if status(n)['role']=='LEADER'),None));old=status(leader);container=compose('ps','-q',leader).strip();network=args.project+'_raft'
    watching=subprocess.Popen(dc+['run','--rm','-T','cli','-jar','/app/integration-tests.jar','fence','--target',leader+':50051'],env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    ready=json.loads(watching.stdout.readline());assert ready['watchReady'];cut=time.monotonic();run(['docker','network','disconnect',network,container]);disconnected=(network,container,leader)
    assert status(leader)['nodeId']==leader,'application port must remain reachable'
    out,err=watching.communicate(timeout=12);assert watching.returncode==0,(out,err);proof=[json.loads(line) for line in out.splitlines()];assert any(p.get('applicationRpcReachable') and p.get('nodeId')==leader for p in proof);assert any(p.get('watchFenced') for p in proof);assert any('oldLeaderRead' in p for p in proof)
    new=until(lambda:next((n for n in ['cp1','cp2','cp3'] if n!=leader and status(n)['role']=='LEADER'),None));assert len(gateway())==1024
    majority=cli('kv','put','/fault/majority','committed');record('raft-only-partition',oldLeader=old,newLeader=status(new),majorityMutation=majority,proof=proof,elapsedMs=round((time.monotonic()-cut)*1000))
    run(['docker','network','connect','--alias',leader+'-raft',network,container]);disconnected=None
    until(lambda:int(status(leader)['appliedIndex'])>=int(status(new)['appliedIndex']),60)
    follower=next(n for n in ['cp1','cp2','cp3'] if n!=new);before=status(follower);compose('stop',follower)
    fill=scenario('fill','--count','1200','--bytes','16384',timeout=180)
    survivors=[n for n in ['cp1','cp2','cp3'] if n!=follower]
    snapshots={n:json.loads(http(env['KV_'+n.upper()+'_ADMIN'],'/snapshot','POST')) for n in survivors}
    def purged_leader():
        for n in survivors:
            s=status(n)
            if s['role']=='LEADER' and int(s['logStartIndex'])>int(before['appliedIndex'])+1:return s
    after=until(purged_leader,30)
    compose('start',follower);installed=until(lambda:status(follower) if int(status(follower)['snapshotInstalls'])>int(before['snapshotInstalls']) and int(status(follower)['appliedIndex'])>=int(fill['fillRevision']) else None,60)
    record('snapshot-install',before=before,purgedLeader=after,snapshots=snapshots,installed=installed)
    saved=cli('kv','put','/fault/persist','retained');compose('stop','cp1','cp2','cp3');compose('start','cp1','cp2','cp3');restored=until(lambda:cli('kv','get','/fault/persist'));assert restored['entry']['value']=='cmV0YWluZWQ=';assert restored['entry']['modRevision']==saved['entry']['modRevision'];record('full-restart',revision=saved['revision'],restored=restored)
    # Hold partial request bodies across many routes; budget applies before aggregation.
    scenario('seed-many');until(lambda:int(float(metrics()['gateway_config_revision']))>=int(cli('service','get','bulk79')['revision']))
    sockets=[];send_failures=0
    try:
        for i in range(80):
            s=socket.create_connection(('127.0.0.1',int(env['KV_HTTP_PORT'])),timeout=2);s.settimeout(2);sockets.append(s)
            try:s.sendall(f'POST /bulk{i}/body HTTP/1.1\r\nHost: test\r\nContent-Length: 1048576\r\n\r\n'.encode()+b'x'*900000)
            except OSError:send_failures+=1
        # sendall may finish in the host TCP buffer before Netty consumes the bytes.
        # Observe actual held bytes and actual 503 responses before crediting pressure.
        pending=set(sockets);responses={s:b'' for s in sockets};overloaded=set();held={};peak=0;samples=0;first_size=None;started=time.monotonic();end=started+5
        for s in sockets:s.setblocking(False)
        while time.monotonic()<end:
            sample=metrics();size=int(float(sample['gateway_body_bytes']))
            samples+=1
            if first_size is None:first_size=size
            assert size<=64*1024*1024,sample
            if size>peak:peak=size;held=sample
            readable=select.select(list(pending),[],[],.05)[0] if pending else []
            for s in readable:
                try:data=s.recv(1024-len(responses[s]))
                except OSError:data=b''
                responses[s]+=data
                if b' 503 ' in responses[s].split(b'\r\n',1)[0]:overloaded.add(s)
                if not data or b'\r\n' in responses[s] or len(responses[s])==1024:pending.discard(s)
            if peak>32*1024*1024 and overloaded:break
            if not pending:time.sleep(.05)
        assert 32*1024*1024<peak<=64*1024*1024,held
        assert overloaded,'expected actual HTTP 503 global body budget rejection'
        observed_ms=round((time.monotonic()-started)*1000)
        memory=json.loads(run(['docker','inspect',compose('ps','-q','gateway').strip()]))[0]['State'];assert not memory['OOMKilled'];stats=run(['docker','stats','--no-stream','--format','{{json .}}',compose('ps','-q','gateway').strip()]).strip();record('body-budget',heldBytes=peak,firstObservedBytes=first_size,sampleCount=samples,observationMs=observed_ms,rejected=len(overloaded),sendFailures=send_failures,stats=json.loads(stats),oomKilled=memory['OOMKilled'])
    finally:
        for s in sockets:s.close()
    until(lambda:float(metrics()['gateway_inflight'])==0 and float(metrics()['gateway_body_bytes'])==0)
    scenario('clean-many');record('cleanup',inflight=metrics()['gateway_inflight'],bodyBytes=metrics()['gateway_body_bytes'])
    result='PASS'
except BaseException:
    result='FAIL';raise
finally:
    if disconnected:
        n,c,leader=disconnected
        try:run(['docker','network','connect','--alias',leader+'-raft',n,c])
        except Exception:pass
    try:
        output=pathlib.Path(args.output);output.parent.mkdir(parents=True,exist_ok=True)
        output.write_text(json.dumps({'result':locals().get('result','FAIL'),'project':args.project,'commit':run(['git','rev-parse','HEAD']).strip(),'dirty':bool(run(['git','status','--porcelain']).strip()),'ports':{k:v for k,v in env.items() if k.startswith('KV_')},'events':events},indent=2)+'\n')
        if owned:
            logs=output.with_suffix('.log');logs.write_text(compose('logs','--no-color',timeout=30))
    finally:
        try:
            if owned:compose('down','--volumes',timeout=60)
        finally:release_lease(lease)
