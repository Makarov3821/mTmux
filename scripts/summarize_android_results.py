#!/usr/bin/env python3
"""Summarize a scripts/run_android_matrix.sh output folder: one line per non-passing test.

Reads each class's testlog/test-results.log (am instrument status codes), so Gradle task lines
such as "Task ... SKIPPED" are never mistaken for test results. Classes without a result log
(compile error, crash before start, timeout) are reported as NORUN with the last console line.

usage: python3 scripts/summarize_android_results.py <matrix-dir> [<other-matrix-dir>]
With two folders, prints tests whose status differs (e.g. base commit vs. change).
"""
import glob, os, re, sys
from collections import Counter

CODES = {1: 'START', 0: 'PASS', -1: 'ERROR', -2: 'FAIL', -3: 'IGNORED', -4: 'SKIP'}

def parse(folder):
    results = {}
    for console in sorted(glob.glob(os.path.join(folder, '*.console.log'))):
        cls = os.path.basename(console)[:-len('.console.log')]
        logs = glob.glob(os.path.join(folder, cls, '*', 'testlog', 'test-results.log'))
        if not logs:
            tail = open(console, errors='ignore').read().strip().splitlines()
            results[cls + '#*'] = ('NORUN', tail[-1][:120] if tail else '')
            continue
        text = open(logs[0], errors='ignore').read()
        for block in re.finditer(r'((?:INSTRUMENTATION_STATUS: .*\n|[^\n]*\n)*?)INSTRUMENTATION_STATUS_CODE: (-?\d+)', text):
            body, code = block.group(1), int(block.group(2))
            names = re.findall(r'INSTRUMENTATION_STATUS: test=(\S+)', body)
            if not names:
                continue
            state = CODES.get(code, str(code))
            key = f'{cls}#{names[-1]}'
            if state == 'START':
                results.setdefault(key, ('NORUN', 'started, no result'))
                continue
            info = ''
            if state in ('FAIL', 'ERROR'):
                first = re.search(r'stack=([^\n]*)', body)
                where = [l for l in body.splitlines() if l.startswith('at dev.mtmux')]
                info = (first.group(1)[:110] if first else '') + (' @ ' + where[0].split('(')[-1].rstrip(')') if where else '')
            results[key] = (state, info)
        if 'Process crashed' in text:
            results[cls + '#__process__'] = ('CRASH', 'instrumentation process crashed')
    return results

def main():
    if len(sys.argv) not in (2, 3):
        sys.exit(__doc__)
    a = parse(sys.argv[1])
    print(sys.argv[1], dict(Counter(v[0] for v in a.values())))
    for k, v in a.items():
        if v[0] != 'PASS':
            print(f'  {v[0]:6} {k}  {v[1]}')
    if len(sys.argv) == 3:
        b = parse(sys.argv[2])
        print(sys.argv[2], dict(Counter(v[0] for v in b.values())))
        print('differences:')
        for k in sorted(set(a) | set(b)):
            sa, sb = a.get(k, ('ABSENT',))[0], b.get(k, ('ABSENT',))[0]
            if sa != sb:
                print(f'  {k}: {sa} -> {sb}')

if __name__ == '__main__':
    main()
