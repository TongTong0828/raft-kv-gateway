#!/usr/bin/env python3
import os,time,urllib.request,json
base='http://127.0.0.1:'+os.getenv('KV_HTTP_PORT','8080')
end=time.monotonic()+20
seen=set()
while time.monotonic()<end:
    try:
        with urllib.request.urlopen(base+'/orders/item',timeout=2) as response:
            assert response.status==200 and len(response.read())==1024
            seen.add(response.headers['X-Backend-Id'])
        if seen=={'backend-a','backend-b'}: break
    except (OSError,AssertionError): time.sleep(.1)
assert seen=={'backend-a','backend-b'},seen
with urllib.request.urlopen('http://127.0.0.1:'+os.getenv('KV_GATEWAY_ADMIN','9090')+'/metrics') as response:
    metrics=response.read().decode();assert 'gateway_inflight 0\n' in metrics and 'gateway_body_bytes 0\n' in metrics
print(json.dumps({'smoke':'PASS','backends':sorted(seen),'responseBytes':1024}))
