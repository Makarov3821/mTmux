#!/usr/bin/env python3
"""Verify APK inventory and offline model ZIP against the exact catalog shipped in the APK."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile
ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--apk', type=Path, required=True)
p.add_argument('--pack', type=Path)
a = p.parse_args()
manifest_bytes = (ROOT/'app/src/main/assets/models.json').read_bytes()
manifest = json.loads(manifest_bytes)
assert len(manifest['schemes']) == 11
assert {s['id'] for s in manifest['schemes'] if s.get('focused')} == {'x-asr', 'paraformer-ct', 'paraformer-srf'}
expected = {w['path']:w for s in manifest['sources'] for w in s['files']}
assert len(expected) == sum(len(s['files']) for s in manifest['sources'])
with zipfile.ZipFile(a.apk) as z:
    assert z.read('assets/models.json') == manifest_bytes, 'APK has a stale model catalog'
    names = z.namelist()
    assert not any(n.endswith('.onnx') or (n.startswith('assets/') and n.endswith('.bin')) for n in names), 'Model weight accidentally bundled'
    abis = sorted({n.split('/')[1] for n in names if n.startswith('lib/')})
    assert abis == ['arm64-v8a'], abis
    for lib in ['libaudiolab_whisper.so','libsherpa-onnx-jni.so','libonnxruntime.so','libonnxruntime4j_jni.so']:
        assert 'lib/arm64-v8a/'+lib in names, lib
    assert 'assets/licenses/LICENSE-sherpa-onnx.txt' in names
    libs = {Path(n).name:z.getinfo(n).file_size for n in names if n.startswith('lib/') and n.endswith('.so')}
    apk = dict(bytes=a.apk.stat().st_size, sha256=hashlib.file_digest(a.apk.open('rb'),'sha256').hexdigest(), abis=abis, uncompressedNativeLibraries=libs)
if a.pack:
    with zipfile.ZipFile(a.pack) as z:
        assert set(z.namelist()) == set(expected) | {'models.json'}, 'Unexpected/missing deployment files'
        # 0.1.0's ZIP remains usable: the app imports verified weights, not catalog settings.
        packed_catalog = json.loads(z.read('models.json'))
        packed_weights = {w['path']: (w['bytes'], w['sha256']) for s in packed_catalog['sources'] for w in s['files']}
        assert packed_weights == {n: (w['bytes'], w['sha256']) for n,w in expected.items()}, 'ZIP weights differ from APK requirements'
        for name,w in expected.items():
            assert z.getinfo(name).file_size == w['bytes'], name
            with z.open(name) as f: assert hashlib.file_digest(f,'sha256').hexdigest()==w['sha256'], name
            print('OK',name,flush=True)
result = dict(apk=apk, schemeCount=len(manifest['schemes']), focusedSchemeCount=3, uniqueWeightFiles=len(expected), deploymentBytes=sum(w['bytes'] for w in expected.values()), offlinePackVerified=bool(a.pack))
(ROOT/'evidence').mkdir(exist_ok=True)
(ROOT/'evidence/artifact-verification.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,indent=2))
