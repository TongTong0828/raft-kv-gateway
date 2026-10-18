#!/usr/bin/env python3
import hashlib,io,json,pathlib,zipfile
root=pathlib.Path(__file__).resolve().parent.parent
supplement=root/'distribution/resources/META-INF/third-party'
index=json.loads((supplement/'index.json').read_text())
for entry in index['files']:
    content=(supplement/entry['file']).read_bytes();assert len(content)==entry['bytes'] and hashlib.sha256(content).hexdigest()==entry['sha256']
for module in ['control-plane','gateway','cli','demo-backend','integration-tests']:
    with zipfile.ZipFile(root/module/'target'/f'{module}.jar') as jar:
        names=jar.namelist();assert f'META-INF/dependencies/{module}.txt' in names
        assert (root/'LICENSE').read_bytes() in jar.read('META-INF/LICENSE')
        assert b'Raft KV & Gateway' in jar.read('META-INF/NOTICE')
        assert 'META-INF/third-party/originals/index.tsv' in names
        for text in supplement.glob('*.txt'):assert jar.read('META-INF/third-party/'+text.name)==text.read_bytes()
        assert any('boringssl' in name.lower() for name in names)
        archives=[name for name in names if name.startswith('META-INF/third-party/originals/') and name.endswith('.licenses.zip')]
        assert archives
        preserved=0
        for name in archives:
            with zipfile.ZipFile(io.BytesIO(jar.read(name))) as original:preserved+=len(original.namelist())
        assert preserved>0
        print(f'{module}: inventory and supplemental licenses present; {preserved} original licensing resources archived')
