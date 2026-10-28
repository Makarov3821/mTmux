#!/usr/bin/env python3
"""Run pinned punctuation weights on illustrative text; emit predictions for actual Kotlin reconstruction tests.

No phone latency, held-out accuracy or user speech quality claim is made.
Requires numpy and onnxruntime==1.23.2 in the optional evaluation environment.
"""
import base64
import hashlib
import json
from pathlib import Path
import re
import time
import numpy as np
import onnxruntime as ort

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = json.loads((ROOT/'app/src/main/assets/models.json').read_text())
FILES = {s['id']: s['files'] for s in MANIFEST['sources']}
CASES = [
    ('public-paraformer', '欢迎大家来体验达摩院推出的语音识别模型'),
    ('instructions', '先检查连接超时的原因然后修改代码修改完成后运行测试'),
    ('negation', '不要删除现有配置如果连接失败就回到主界面并保留错误日志'),
    ('questions', '这个问题是什么原因造成的可以帮我检查一下吗'),
    ('technical', '我想用 Jetpack Compose 重构这个页面先检查 SSH connection 然后运行 gradlew 确认 README.md 和 /tmp/demo.kt 完整'),
    ('original-marks', '保持配置不变，\n先检查 README.md 然后运行测试。'),
    ('traditional', '請檢查這個連線問題然後保留原有設定不要改動其他檔案'),
    ('long-context', ('现在请先检查连接超时的原因然后修改代码修改完成后运行测试如果测试失败就保留错误日志不要删除现有配置这个问题到底是什么原因造成的可以解释一下吗' * 3)),
    ('multi-window', ('先检查配置然后运行测试如果失败就保留日志不要删除文件请确认这个问题是否已经修复' * 17)),
]

def weight(group):
    w = FILES[group][0]; p = ROOT/'.cache/models'/w['path']
    assert p.stat().st_size == w['bytes'] and hashlib.file_digest(p.open('rb'), 'sha256').hexdigest() == w['sha256']
    return p

def starts(size, window):
    return [0] if size <= window else list(dict.fromkeys(list(range(0, size-window, window*3//4)) + [size-window]))

def token_spans(text, srf):
    # Same character / ASCII word input granularity as the product. Actual reconstruction is tested in Kotlin.
    existing = '，。！？、；：\"\'“”‘’（）【】《》,!?.;:()[]'
    pattern = r'[^\s]' if srf else r"[a-zA-Z0-9']+|[^\s]"
    return [(m.group(), m.start(), m.end()) for m in re.finditer(pattern, text) if not (srf and m.group() in existing)]

def b64(s): return base64.b64encode(s.encode()).decode()

records=[]; fixtures=[]
for kind in ['srf', 'ct']:
    options = ort.SessionOptions(); options.intra_op_num_threads = options.inter_op_num_threads = 1
    options.add_session_config_entry('session.intra_op.allow_spinning', '0'); options.add_session_config_entry('session.inter_op.allow_spinning', '0')
    session = ort.InferenceSession(str(weight(kind)), options, providers=['CPUExecutionProvider'])
    if kind == 'srf':
        vocab = json.loads(weight('srf-vocab').read_text()); labels=['_','，','。','？','！']; unknown=vocab['<UNK>']
    else:
        metadata=session.get_modelmeta().custom_metadata_map
        vocab={t:i for i,t in enumerate(metadata['tokens'].split('|'))}; labels=metadata['punctuations'].split('|'); unknown=vocab[metadata['unk_symbol']]
    for name,text in CASES:
        tokens=token_spans(text, kind=='srf')
        ids=[vocab.get(t if kind=='srf' else t.lower(), unknown) for t,_,_ in tokens]
        for window in ([128] if kind=='srf' else [128,512]):
            predicted=['_']*len(tokens); margins=[-1]*len(tokens); begun=time.perf_counter()
            for start in starts(len(tokens),window):
                n=min(window,len(tokens)-start)
                x=np.array([ids[start:start+n]],dtype=np.int64 if kind=='srf' else np.int32)
                inputs={'input_ids':x,'attention_mask':np.ones_like(x)} if kind=='srf' else {'inputs':x,'text_lengths':np.array([n],dtype=np.int32)}
                logits=session.run(None,inputs)[0]; assert logits.shape == (1,n,len(labels))
                for j,k in enumerate(logits[0].argmax(-1)):
                    margin=min(j,n-1-j)
                    if margin>margins[start+j]:predicted[start+j]=labels[int(k)];margins[start+j]=margin
            elapsed=round((time.perf_counter()-begun)*1000,3)
            rec=dict(case=name,kind=kind,contextWindow=window,text=text,tokens=[t for t,_,_ in tokens],predictions=predicted,desktopInferenceMs=elapsed)
            records.append(rec)
            fixtures.append('\t'.join([name,f'{kind}{window}',b64(text),b64('\0'.join(rec['tokens'])),','.join(predicted)]))
    del session
out=ROOT/'evidence/focused-punctuation-020.json'
out.write_text(json.dumps(dict(scope='Illustrative real model predictions; no quality score or Android latency. Kotlin tests reconstruct with the real product tokenizer.',onnxruntime=ort.__version__,records=records),ensure_ascii=False,indent=2)+'\n')
resource=ROOT/'app/src/test/resources/focused-punctuation.tsv';resource.parent.mkdir(parents=True,exist_ok=True);resource.write_text('\n'.join(fixtures)+'\n')
print('Wrote',len(records),'real model predictions and',len(fixtures),'Kotlin fixtures to',out)
