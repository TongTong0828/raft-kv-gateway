#!/usr/bin/env python3
"""Build and verify a source-pinned local k6; never install a global binary."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import urllib.request

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
WORK = ROOT / 'work' / 'k6'
PIN = json.loads((HERE / 'pin.json').read_text())


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def patch_info():
    patches = [(name, (HERE / name).read_bytes()) for name in PIN['patches']]
    return hashlib.sha256(b''.join(data for _, data in patches)).hexdigest(), {
        name: hashlib.sha256(data).hexdigest() for name, data in patches}


def command(args, *, cwd=None, log=None, capture=False):
    if log:
        with log.open('w') as stream:
            result = subprocess.run(args, cwd=cwd, text=True, stdout=stream,
                                    stderr=subprocess.STDOUT, timeout=1200)
        if result.returncode:
            raise RuntimeError(f'command failed ({result.returncode}); see {log}')
        return ''
    return subprocess.run(args, cwd=cwd, check=True, text=True,
                          capture_output=capture, timeout=1200).stdout


def native_platform():
    platform = command(['docker', 'version', '--format', '{{.Server.Os}}/{{.Server.Arch}}'],
                       capture=True).strip()
    if platform not in PIN['platforms']:
        raise ValueError('unsupported Docker server platform: ' + platform)
    return platform


def ensure_image(image, platform):
    result = subprocess.run(['docker', 'image', 'inspect', image],
                            capture_output=True, text=True, timeout=30)
    image_info = json.loads(result.stdout)[0] if not result.returncode else {}
    if result.returncode or image_info.get('Os', '') + '/' + image_info.get('Architecture', '') != platform:
        command(['docker', 'pull', '--platform', platform, image])


def prepare(platform):
    WORK.mkdir(parents=True, exist_ok=True)
    archive = WORK / ('k6-' + PIN['upstreamCommit'] + '.tar.gz')
    if not archive.exists():
        partial = archive.with_suffix('.partial')
        try:
            with urllib.request.urlopen(PIN['sourceUrl'], timeout=60) as response, partial.open('wb') as out:
                shutil.copyfileobj(response, out)
            if digest(partial) != PIN['sourceSha256']:
                raise ValueError('upstream source archive checksum mismatch')
            partial.replace(archive)
        finally:
            partial.unlink(missing_ok=True)
    if digest(archive) != PIN['sourceSha256']:
        raise ValueError('cached upstream source archive checksum mismatch')
    destination = WORK / 'prepared'
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir()
    with tarfile.open(archive) as source:
        source.extractall(destination, filter='data')
    source = destination / ('k6-' + PIN['upstreamCommit'])
    for patch in PIN['patches']:
        command(['git', 'apply', '--check', str(HERE / patch)], cwd=source)
        command(['git', 'apply', str(HERE / patch)], cwd=source)
    ensure_image(PIN['builderImage'], platform)
    return source


def container(source, platform, *, offline=True, output=None):
    cache = WORK / 'cache'
    cache.mkdir(exist_ok=True)
    args = ['docker', 'run', '--rm', '--pull=never', '--platform', platform,
            '--cpus', '2', '--memory', '2g']
    if offline:
        args += ['--network', 'none']
    for value in ['GOTOOLCHAIN=local', 'CGO_ENABLED=0', 'GOMAXPROCS=2',
                  'GOMEMLIMIT=1500MiB', 'GOCACHE=/cache', 'GOPROXY=off', 'GOSUMDB=off']:
        args += ['-e', value]
    args += ['-v', str(source) + ':/src:ro', '-v', str(cache) + ':/cache']
    if output:
        args += ['-v', str(output) + ':/out']
    return args + ['-w', '/src', PIN['builderImage']]


def test(source, platform):
    command([sys.executable, '-m', 'unittest', 'discover', '-s', str(HERE), '-p', 'test_build.py', '-v'],
            log=WORK / 'provenance-tests.log')
    # Upstream TestDnsResolve intentionally resolves s.com and requires DNS.
    # Go module fetching remains disabled; compilation uses the bundled vendor tree.
    log = WORK / 'httpext-tests.jsonl'
    command(container(source, platform, offline=False) + ['go', 'test', '-mod=vendor', '-p', '2',
            './lib/netext/httpext', '-count=1', '-json'], log=log)
    events = [json.loads(line) for line in log.read_text().splitlines() if line.startswith('{')]
    return {'package': 'go.k6.io/k6/v2/lib/netext/httpext',
            'passedTestsAndSubtests': sum(e.get('Action') == 'pass' and 'Test' in e for e in events),
            'log': str(log.relative_to(ROOT))}


def validate_image(info, expected_platform=None):
    patch_sha, _ = patch_info()
    labels = info.get('Config', {}).get('Labels') or {}
    expected = {'io.raftkv.k6.upstream.commit': PIN['upstreamCommit'],
                'io.raftkv.k6.source.sha256': PIN['sourceSha256'],
                'io.raftkv.k6.patch.sha256': patch_sha,
                'io.raftkv.k6.clock': PIN['clock'],
                'io.raftkv.k6.build-origin': PIN['buildOrigin']}
    platform = info.get('Os', '') + '/' + info.get('Architecture', '')
    if platform not in PIN['platforms'] or (expected_platform is not None and platform != expected_platform):
        raise ValueError('k6 platform differs from the allowed or selected platform')
    expected['io.raftkv.k6.platform'] = platform
    for key, value in expected.items():
        if labels.get(key) != value:
            raise ValueError('unrecognized k6 image label: ' + key)
    return labels


def validate_provenance(info, provenance, binary_sha, expected_platform=None):
    labels = validate_image(info, expected_platform)
    patch_sha, individual = patch_info()
    if binary_sha != labels.get('io.raftkv.k6.binary.sha256'):
        raise ValueError('k6 binary differs from its image provenance label')
    if provenance.get('binarySha256') != binary_sha or provenance.get('patchSha256') != patch_sha:
        raise ValueError('k6 embedded provenance does not match the binary and patches')
    if provenance.get('patchSha256ByFile') != individual:
        raise ValueError('k6 embedded individual patch checksums do not match')
    if provenance.get('platform') != info['Os'] + '/' + info['Architecture']:
        raise ValueError('k6 embedded platform differs from the actual image')
    if any(provenance.get(key) != value for key, value in PIN.items()):
        raise ValueError('k6 embedded provenance does not match pin.json')


def verify(image, platform):
    info = json.loads(command(['docker', 'image', 'inspect', image], capture=True))[0]
    image_id = info['Id']
    labels = validate_image(info, platform)
    prefix = ['docker', 'run', '--rm', '--pull=never', '--network', 'none']
    sha = command(prefix + ['--entrypoint', 'sha256sum', image_id, '/usr/bin/k6'], capture=True).split()[0]
    provenance = json.loads(command(prefix + ['--entrypoint', 'cat', image_id,
                                            '/usr/share/raft-kv-k6/provenance.json'], capture=True))
    validate_provenance(info, provenance, sha, platform)
    version = command(prefix + [image_id, 'version'], capture=True).strip()
    return {'imageId': image_id, 'imageTag': image, 'version': version, 'platform': platform,
            'binarySha256': sha, 'labels': labels, 'provenance': provenance}


def build(source, platform):
    tests = test(source, platform)
    output = WORK / 'image-context'
    if output.exists():
        shutil.rmtree(output)
    output.mkdir()
    flags = '-buildid= -X go.k6.io/k6/v2/internal/build.BuildOrigin=' + PIN['buildOrigin']
    command(container(source, platform, output=output) + ['go', 'build', '-mod=vendor', '-p', '2',
            '-trimpath', '-buildvcs=false', '-ldflags', flags, '-o', '/out/k6'],
            log=WORK / 'build.log')
    binary_sha = digest(output / 'k6')
    patch_sha, individual = patch_info()
    provenance = {**PIN, 'platform': platform, 'patchSha256': patch_sha, 'patchSha256ByFile': individual,
                  'binarySha256': binary_sha, 'goVersion': 'go1.27.1',
                  'compile': {'CGO_ENABLED': '0', 'GOTOOLCHAIN': 'local',
                              'GOPROXY': 'off', 'GOSUMDB': 'off',
                              'flags': ['-mod=vendor', '-p=2', '-trimpath', '-buildvcs=false',
                                        '-ldflags=' + flags]}, 'tests': tests}
    (output / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    (output / 'k6.sha256').write_text(binary_sha + '  /usr/bin/k6\n')
    shutil.copyfile(HERE / 'LICENSE.md', output / 'LICENSE.md')
    (output / 'SOURCE-REBUILD.txt').write_text(
        'Locally modified Grafana k6 under GNU AGPLv3.\n'
        'Upstream source: ' + PIN['sourceUrl'] + '\n'
        'Source SHA256: ' + PIN['sourceSha256'] + '\n'
        'Patches and rebuild: https://github.com/TongTong0828/raft-kv-gateway/tree/main/benchmarks/k6\n'
        'From the source checkout, run: scripts/k6.sh build\n'
        'The full pin and patch checksums are in provenance.json beside this file.\n')
    ensure_image(PIN['runtimeImage'], platform)
    command(['docker', 'build', '--platform', platform, '--network', 'none', '--pull=false',
             '-f', str(HERE / 'Dockerfile'), '-t', PIN['imageTag'],
             '--build-arg', 'UPSTREAM_COMMIT=' + PIN['upstreamCommit'],
             '--build-arg', 'SOURCE_SHA256=' + PIN['sourceSha256'],
             '--build-arg', 'PATCH_SHA256=' + patch_sha,
             '--build-arg', 'BINARY_SHA256=' + binary_sha,
             '--build-arg', 'K6_PLATFORM=' + platform, str(output)], log=WORK / 'image-build.log')
    result = verify(PIN['imageTag'], platform)
    (WORK / 'image.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'test', 'build', 'provenance'])
    parser.add_argument('--image', default=PIN['imageTag'])
    parser.add_argument('--platform', choices=PIN['platforms'], help='override native Docker server platform')
    args = parser.parse_args()
    WORK.mkdir(parents=True, exist_ok=True)
    platform = args.platform or native_platform()
    if args.action == 'provenance':
        result = verify(args.image, platform)
    else:
        source = prepare(platform)
        result = {'source': str(source)} if args.action == 'prepare' else test(source, platform) if args.action == 'test' else build(source, platform)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
