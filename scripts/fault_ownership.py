"""Exclusive ownership checks for an isolated fault experiment."""
import re,subprocess
def claim_project(project):
    ensure_unused(project)
    # Docker container names are reserved atomically, including across checkouts.
    claim=subprocess.run(['docker','create','--name',project+'-fault-owner','--label','dev.kvgateway.fault.project='+project,'raft-kv-gateway:local','-version'],text=True,capture_output=True)
    if claim.returncode:raise RuntimeError('cannot exclusively claim fault project '+project+': '+claim.stderr.strip())
    lease=claim.stdout.strip()
    try:ensure_unused(project)
    except BaseException:
        release_lease(lease);raise
    return lease
def release_lease(lease):
    if lease:subprocess.run(['docker','rm','-f',lease],capture_output=True)
def ensure_unused(project):
    if not re.fullmatch(r'[a-z0-9][a-z0-9_-]*',project):raise ValueError('invalid Compose project name')
    label='label=com.docker.compose.project='+project
    for command in [['docker','ps','-aq','--filter',label],['docker','network','ls','-q','--filter',label],['docker','volume','ls','-q','--filter',label]]:
        found=subprocess.run(command,text=True,capture_output=True,check=True).stdout.strip()
        if found:raise RuntimeError('refusing occupied Compose project: '+project)
    # Also reject unlabelled resources that would collide with configured names.
    services=['cp1','cp2','cp3','gateway','backend-a','backend-b']
    for resource,names in [('volume',[project+'_cp'+str(i)+'-data' for i in range(1,4)]),('network',[project+'_app',project+'_raft']),('container',[project+separator+service+separator+'1' for service in services for separator in ['-','_']])]:
        for name in names:
            if subprocess.run(['docker',resource,'inspect',name],capture_output=True).returncode==0:raise RuntimeError('refusing existing resource: '+name)
