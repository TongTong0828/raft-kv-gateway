"""Validate the containers actually receiving benchmark traffic."""
SERVICES={'cp1','cp2','cp3','gateway','backend-a','backend-b'}
def validate_actual(containers,image_id,commit):
    result=[];seen=set()
    for container in containers:
        labels=container['Config'].get('Labels',{});service=labels.get('com.docker.compose.service')
        if service not in SERVICES:continue
        if not container.get('State',{}).get('Running'):raise ValueError('container is not running: '+service)
        if container['Image']!=image_id or labels.get('org.opencontainers.image.revision')!=commit:raise ValueError('running image/revision mismatch for '+service+'; recreate the Compose stack')
        seen.add(service);result.append({'service':service,'containerId':container['Id'],'name':container['Name'].lstrip('/'),'imageId':container['Image'],'revision':labels.get('org.opencontainers.image.revision'),'memoryLimitBytes':container.get('HostConfig',{}).get('Memory'),'nanoCpus':container.get('HostConfig',{}).get('NanoCpus')})
    if seen!=SERVICES:raise ValueError('missing running services: '+','.join(sorted(SERVICES-seen)))
    return result

def validate_workload_profile(observed,expected):
    """Do not credit a requested capacity profile unless it was committed and read back."""
    if not isinstance(observed,dict) or observed.get('service')!=expected:
        raise ValueError('applied service differs from the requested workload profile')
    revision=observed.get('revision')
    if type(revision) is not str or not revision.isascii() or not revision.isdecimal() or int(revision)<1:
        raise ValueError('workload profile has no committed revision')
    return observed
