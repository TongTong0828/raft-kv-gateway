#!/usr/bin/env python3
"""Real kind acceptance using a private kubeconfig and an owned cluster."""
import argparse,json,os,pathlib,socket,subprocess,time,urllib.request
p=argparse.ArgumentParser();p.add_argument('--name',default='kv-verify-'+str(os.getpid()));p.add_argument('--output',default='work/kind-results.json');p.add_argument('--keep',action='store_true');args=p.parse_args()
root=pathlib.Path(__file__).resolve().parent.parent;os.chdir(root);events=[];created=False;forward=None
kind=str(root/'work/tools/kind');kubectl=str(root/'work/tools/kubectl');config=str(root/'work'/('kubeconfig-'+args.name));node='kindest/node:v1.37.0@sha256:a1ed56cfb0e7b93589bdf97c8cd566405a265939e3620fc4f5de89adff580ae5'
def run(cmd,timeout=180):
    r=subprocess.run(cmd,text=True,capture_output=True,timeout=timeout)
    if r.returncode:raise RuntimeError(f'{cmd}: {r.stderr[-5000:]} {r.stdout[-2000:]}')
    return r.stdout.strip()
def k(*cmd,timeout=180):return run([kubectl,'--kubeconfig',config,'--context','kind-'+args.name,'-n','kv-gateway',*cmd],timeout)
def cli(*cmd):return json.loads(k('exec','cp-0','--','java','-jar','/app/cli.jar','--seeds','cp-0.control:50051,cp-1.control:50051,cp-2.control:50051','--timeout-ms','10000',*cmd))
def record(test,**data):events.append({'test':test,'time':time.time(),**data});print(json.dumps(events[-1]),flush=True)
def until(check,timeout=30):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        try:
            value=check()
            if value:return value
        except (OSError,RuntimeError):pass
        time.sleep(.2)
    raise AssertionError('condition timed out')
def http_check():
    global forward
    with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
    forward=subprocess.Popen([kubectl,'--kubeconfig',config,'--context','kind-'+args.name,'-n','kv-gateway','port-forward','service/gateway',f'{port}:8080'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    def request():
        with urllib.request.urlopen(f'http://127.0.0.1:{port}/orders/item',timeout=2) as r:body=r.read();assert r.status==200 and len(body)==1024;return r.headers['X-Backend-Id']
    try:return until(request)
    finally:forward.terminate();forward.wait(timeout=5);forward=None
try:
    assert args.name not in run([kind,'get','clusters']).splitlines(),'cluster already exists'
    created=True;run([kind,'create','cluster','--name',args.name,'--image',node,'--kubeconfig',config,'--wait','120s'],timeout=240)
    run([kind,'load','docker-image','raft-kv-gateway:local','--name',args.name],timeout=180)
    k('apply','-k','deploy/kubernetes/overlays/kind');k('rollout','status','statefulset/cp','--timeout=180s',timeout=200)
    controls=cli('status');assert len(controls)==3;record('three-controls-ready',controls=controls)
    seeded=cli('service','put','/config/orders.json');k('rollout','status','deployment/gateway','--timeout=120s',timeout=140)
    for backend in ['backend-a','backend-b']:k('rollout','status','deployment/'+backend,'--timeout=60s')
    record('initial-http',revision=seeded['revision'],backend=http_check())
    saved=cli('kv','put','/kind/persist','retained');before=json.loads(k('get','pod','cp-0','-o','json'))['metadata']['uid'];pvcs=json.loads(k('get','pvc','-o','json'));assert len(pvcs['items'])==3
    k('delete','pod','cp-0','--wait=true');until(lambda:json.loads(k('get','pod','cp-0','-o','json'))['metadata']['uid']!=before,30);k('wait','--for=condition=Ready','pod/cp-0','--timeout=120s',timeout=140)
    restored=until(lambda:cli('kv','get','/kind/persist'));assert restored['entry']==saved['entry'];assert len(json.loads(k('get','pvc','-o','json'))['items'])==3
    record('pod-replacement-pvc',beforeUid=before,afterUid=json.loads(k('get','pod','cp-0','-o','json'))['metadata']['uid'],revision=saved['revision'],pvcCount=3)
    k('rollout','restart','deployment/gateway');k('rollout','status','deployment/gateway','--timeout=120s',timeout=140);record('gateway-rollout',backend=http_check(),service=cli('service','get','orders'))
    k('kustomize','deploy/kubernetes/overlays/eks');record('eks-render-only',deployed=False)
    result='PASS'
except BaseException:
    result='FAIL';raise
finally:
    if forward:forward.terminate()
    output=pathlib.Path(args.output);output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps({'result':locals().get('result','FAIL'),'commit':run(['git','rev-parse','HEAD']),'dirty':bool(run(['git','status','--porcelain'])),'cluster':args.name,'nodeImage':node,'kubectl':json.loads(run([kubectl,'version','--client','-o','json'])),'events':events},indent=2)+'\n')
    if created:
        try:
            output.with_suffix('.cluster.log').write_text(k('get','pods,pvc','-o','wide')+'\n'+k('get','events','--sort-by=.lastTimestamp'))
        finally:
            if not args.keep:run([kind,'delete','cluster','--name',args.name],timeout=60)
