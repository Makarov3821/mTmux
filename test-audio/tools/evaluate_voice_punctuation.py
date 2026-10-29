#!/usr/bin/env python3
"""Compare raw punctuation predictions on a fixed public-text corpus (desktop CPU only).

Install numpy and onnxruntime==1.23.2 in a local venv first. This is a model
comparison, not a replacement for the Android reconstruction/gesture tests.
"""
import argparse
import json
from pathlib import Path
import re
import statistics
import time

import numpy as np
import onnxruntime as ort
from fetch_voice_benchmark import CACHE
from fetch_voice_punctuation import model_specs

CORPUS = [
    "先检查连接超时的原因然后修改代码修改完成后运行测试",
    "不要删除现有配置如果连接失败就回到主界面并保留错误日志",
    "这个问题是什么原因造成的可以帮我检查一下吗",
    "今天天气很好我们出去散步吧",
    "我现在想要重构语音输入的逻辑按下准备好后发出绿色的光晕表示开始录音松手就进行转文字",
]


def evaluate(conservative: bool = False) -> dict:
    cases = [{"text": text} for text in CORPUS]
    details = {}
    for kind in ["srf", "ct"]:
        specs = model_specs(kind)
        model = next(name for name in specs if name.endswith(".onnx"))
        options = ort.SessionOptions()
        options.intra_op_num_threads = options.inter_op_num_threads = 1
        options.add_session_config_entry("session.intra_op.allow_spinning", "0")
        options.add_session_config_entry("session.inter_op.allow_spinning", "0")
        started = time.perf_counter()
        session = ort.InferenceSession(str(CACHE / "punctuation" / model), options, providers=["CPUExecutionProvider"])
        if kind == "srf":
            vocabulary = json.loads((CACHE / "punctuation/srf-vocab.json").read_text())
            unknown, labels = vocabulary["<UNK>"], ["", "，", "。", "？", "！"]
        else:
            metadata = session.get_modelmeta().custom_metadata_map
            vocabulary = {t: i for i, t in enumerate(metadata["tokens"].split("|"))}
            unknown, labels = vocabulary["<unk>"], metadata["punctuations"].split("|")
        details[kind] = {"session_and_vocabulary_ms": round((time.perf_counter() - started) * 1000, 3), "files": specs}
        for case in cases:
            text = case["text"]
            tokens = list(text) if kind == "srf" else re.findall(r"[a-zA-Z0-9']+|[^\s]", text)
            ids = np.array([[vocabulary.get(t if kind == "srf" else t.lower(), unknown) for t in tokens]],
                dtype=np.int64 if kind == "srf" else np.int32)
            inputs = {"input_ids": ids, "attention_mask": np.ones_like(ids)} if kind == "srf" else {
                "inputs": ids, "text_lengths": np.array([len(tokens)], dtype=np.int32)}
            timings = []
            for _ in range(5):
                started = time.perf_counter()
                logits = session.run(None, inputs)[0][0]
                predicted = logits.argmax(-1)
                timings.append((time.perf_counter() - started) * 1000)
            output = "".join(t + (labels[int(k)] if labels[int(k)] not in ["_", "<unk>"] else "") for t, k in zip(tokens, predicted))
            case[kind] = {"raw_output": output, "median_inference_ms": round(statistics.median(timings), 3)}
            if conservative and kind == "srf":
                scores = logits.astype(np.float64)
                probabilities = np.exp(scores - scores.max(-1, keepdims=True))
                probabilities /= probabilities.sum(-1, keepdims=True)
                selected = predicted.copy()
                for i, label in enumerate(predicted[:-1]):
                    if label and (probabilities[i, 0] > .10 or (label in (2, 3, 4) and probabilities[i, label] < .97)):
                        selected[i] = 0
                case[kind]["filtered_output"] = "".join(t + labels[int(k)] for t, k in zip(tokens, selected))
                case[kind]["removed_insertions"] = int(np.count_nonzero(predicted) - np.count_nonzero(selected))
        del session
    return {"environment": "desktop Linux CPU; 1 inference thread; not an Android latency measurement",
        "onnxruntime": ort.__version__, "details": details, "cases": cases,
        "scope": "Five illustrative natural-language examples; no held-out benchmark, F1, phone latency or product reconstruction claim.",
        "srf_conservative_filter": conservative}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--srf-conservative", action="store_true")
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evaluate(args.srf_conservative), ensure_ascii=False, indent=2) + "\n")
    print(args.output)
