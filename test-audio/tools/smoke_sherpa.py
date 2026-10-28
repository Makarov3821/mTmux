#!/usr/bin/env python3
"""Desktop API/model compatibility check. These timings are NOT Android measurements."""
import argparse
import hashlib
import json
from pathlib import Path
import time
import wave
import numpy as np
import sherpa_onnx
ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--audio', type=Path, required=True)
parser.add_argument('--output', type=Path, default=ROOT/'evidence/desktop-sherpa-smoke.json')
a = parser.parse_args()
with wave.open(str(a.audio)) as w:
    assert w.getnchannels() == 1 and w.getsampwidth() == 2 and w.getframerate() == 16000
    pcm = np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(np.float32) / 32768
manifest = json.loads((ROOT/'app/src/main/assets/models.json').read_text())
records=[]
for scheme in manifest['schemes']:
    if scheme['engine']=='whisper': continue
    source=next(x for x in manifest['sources'] if x['id']==scheme['group'])
    paths={Path(x['path']).name:ROOT/'.cache/models'/x['path'] for x in source['files']}
    for f in source['files']:
        p=ROOT/'.cache/models'/f['path']
        assert p.stat().st_size==f['bytes'] and hashlib.file_digest(p.open('rb'),'sha256').hexdigest()==f['sha256']
    def path(role):
        if role=='model': return str(next(p for n,p in paths.items() if n=='model.int8.onnx'))
        if role=='tokens':return str(paths['tokens.txt'])
        return str(next(p for n,p in paths.items() if n.startswith(role+'-') or n.startswith(role+'.')))
    started=time.monotonic()
    engine=scheme['engine']
    if engine=='online':
        r=sherpa_onnx.OnlineRecognizer.from_transducer(tokens=path('tokens'), encoder=path('encoder'), decoder=path('decoder'), joiner=path('joiner'), num_threads=4,model_type='zipformer' if scheme['group']=='zip-small' else 'zipformer2')
    elif engine=='paraformer':r=sherpa_onnx.OfflineRecognizer.from_paraformer(paraformer=path('model'),tokens=path('tokens'),num_threads=4)
    elif engine=='sensevoice':r=sherpa_onnx.OfflineRecognizer.from_sense_voice(model=path('model'),tokens=path('tokens'),num_threads=4,language='zh',use_itn=True)
    else:r=sherpa_onnx.OfflineRecognizer.from_transducer(encoder=path('encoder'),decoder=path('decoder'),joiner=path('joiner'),tokens=path('tokens'),num_threads=4)
    loaded=time.monotonic();s=r.create_stream()
    if engine=='online':
        for start in range(0,len(pcm),1600):
            s.accept_waveform(16000,pcm[start:start+1600])
            while r.is_ready(s): r.decode_stream(s)
        s.accept_waveform(16000,np.zeros(16000,dtype=np.float32));s.input_finished()
        while r.is_ready(s):r.decode_stream(s)
        text=r.get_result(s)
    else:
        s.accept_waveform(16000,pcm);r.decode_stream(s);text=s.result.text
    end=time.monotonic()
    assert text.strip(),scheme['id']+' returned empty text'
    record=dict(id=scheme['id'],raw=text,desktopLoadMs=round((loaded-started)*1000),desktopDecodeMs=round((end-loaded)*1000))
    records.append(record);print(json.dumps(record,ensure_ascii=False),flush=True)
    del s,r
out=dict(runtime='sherpa-onnx 1.13.8 desktop Linux x86_64',purpose='Model/API compatibility only; not Android performance or accuracy evaluation',audioSha256=hashlib.file_digest(a.audio.open('rb'),'sha256').hexdigest(),samples=len(pcm),sampleRate=16000,records=records)
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')
