#!/usr/bin/env python3
"""Collect native Android documentation captures and produce an offline gallery.

Only reads PNG headers/hashes; does not redraw or edit application screenshots.
Usage: python3 scripts/build_ui_catalog.py /path/to/adb-pulled/ui-catalog
"""
from __future__ import annotations
import argparse
import hashlib
from html import escape
import json
import shutil
import struct
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'docs/screenshots/current'
GROUPS = {'home':'主界面与分组','server':'SSH 登录与服务器配置','settings':'设置','terminal':'终端与输入','voice':'语音状态','dialogs':'验证与确认分支','doctor':'tmux Doctor'}
KINDS = {'production-ui':'实际程序界面','component-fixture':'原生组件固定状态','state-fixture':'瞬时分支预览','terminal-render-fixture':'原生界面＋离线终端画面'}

TEMPLATE = r'''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>mTmux 当前界面图集</title>
<style>
:root{color-scheme:dark;--bg:#11131a;--card:#1a1e2a;--line:#30394e;--ink:#e4e8f3;--muted:#aab4c9;--blue:#93b5ff}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.65 system-ui,-apple-system,"Noto Sans CJK SC",sans-serif}a{color:var(--blue)}header,main{max-width:1500px;margin:auto;padding:28px}h1{font-size:30px;margin:0 0 8px}p{margin:8px 0}.subtle{color:var(--muted)}.facts{display:flex;gap:8px;flex-wrap:wrap;margin:18px 0}.fact,.badge{font-size:12px;border:1px solid var(--line);border-radius:20px;padding:3px 10px;background:#1a2030}.toolbar{position:sticky;top:0;z-index:3;border-block:1px solid var(--line);background:#11131af2;backdrop-filter:blur(12px)}.controls{max-width:1500px;margin:auto;padding:14px 28px;display:flex;gap:10px;flex-wrap:wrap}input,select,button{font:inherit;color:var(--ink);background:#202635;border:1px solid #48546e;border-radius:9px;padding:8px 12px}input{flex:1;min-width:220px}button{cursor:pointer}button:hover{border-color:var(--blue)}.count{align-self:center;color:var(--muted);font-size:13px}section{margin-bottom:38px}h2{font-size:21px;margin:4px 0 16px}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:20px}.card{background:var(--card);border:1px solid var(--line);border-radius:15px;overflow:hidden;display:flex;flex-direction:column}.image-button{display:block;padding:12px;background:#0b0d13;border:0;border-radius:0;width:100%;line-height:0}.image-button img{display:block;width:100%;height:auto;max-height:730px;object-fit:contain;margin:auto}.card-body{padding:14px}.card h3{font-size:15px;line-height:1.5;margin:0 0 8px}.badge{color:#b3d5ff;display:inline-block}.badge.component-fixture{color:#9de5bb}.badge.state-fixture,.badge.terminal-render-fixture{color:#f1cb8c}.path{font:11px/1.6 ui-monospace,monospace;color:#aab4c9;word-break:break-all;margin-top:8px}.note{font-size:12px;color:var(--muted);margin-top:8px}dialog{border:1px solid #53617d;border-radius:16px;background:#151a26;color:var(--ink);padding:14px;max-width:96vw;max-height:97vh}dialog::backdrop{background:#000d}.viewer{display:flex;gap:14px;align-items:center}.viewer img{display:block;max-height:83vh;max-width:80vw;object-fit:contain;margin:auto}.viewer-title{margin-bottom:10px;display:flex;justify-content:space-between;gap:16px}.viewer button{padding:8px}.viewer-meta{font-size:12px;color:var(--muted);margin-top:9px}.empty{padding:50px;color:var(--muted)}footer{max-width:1500px;padding:0 28px 36px;margin:auto;color:var(--muted);font-size:13px}@media(max-width:600px){header,main{padding:20px}.controls{padding:12px 20px}.grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.card-body{padding:10px}.card h3{font-size:13px}.path,.note{font-size:10px}.image-button{padding:5px}.viewer{gap:5px}.viewer img{max-width:72vw}.viewer button{padding:5px}}
</style></head><body>
<header><h1>mTmux 当前界面图集</h1><p>基于代码 <strong>__SOURCE__</strong> 的 Android 原生视图快照，统一使用公开演示数据。</p>
<div class="facts"><span class="fact" id="total"></span><span class="fact">Android 15 · 360 × 800</span><span class="fact">深色 / 浅色 · 六套主题</span><span class="fact">不含真实服务器或密钥</span></div>
<p class="subtle">主界面、SSH 登录配置、设置、终端、输入编辑卡片、语音及确认弹窗。点击图片放大，可用方向键逐张查看。</p>
<p class="subtle">采集方式是实际 Android View / Compose 渲染快照，不是系统整屏截屏。终端图已标注离线画面拼合；瞬时分支预览使用当前程序文案和原生控件固定状态展示，已单独标注。<a href="README.md">范围、来源与复现方式</a> · <a href="manifest.json">图片清单与校验值</a></p></header>
<div class="toolbar"><div class="controls"><input id="search" type="search" placeholder="搜索页面、弹窗、状态或源码文件" aria-label="搜索截图"><select id="group" aria-label="界面类别"><option value="">所有界面</option></select><select id="kind" aria-label="采集类型"><option value="">所有采集类型</option></select><span class="count" id="count"></span></div></div>
<main id="gallery"></main><footer>应用没有独立账号登录页；SSH 登录表单位于“服务器配置”。系统权限、系统文件选择器及键盘外观由 Android / 输入法提供，不以自绘画面冒充截图。图集不穷举语言、字号、主题、服务器数量和每条错误文案的所有组合。</footer>
<dialog id="viewer"><div class="viewer-title"><strong id="viewer-title"></strong><button id="close" aria-label="关闭图片">关闭 ×</button></div><div class="viewer"><button id="prev" aria-label="上一张">‹</button><img id="large" alt=""><button id="next" aria-label="下一张">›</button></div><div class="viewer-meta" id="viewer-meta"></div></dialog>
<script id="catalog-data" type="application/json">__DATA__</script>
<script>
const data=JSON.parse(document.getElementById('catalog-data').textContent);const groups=__GROUPS__;const kinds=__KINDS__;
const $=id=>document.getElementById(id);let visible=[],position=0;
for(const [id,label] of Object.entries(groups)){$('group').add(new Option(label,id))}for(const [id,label] of Object.entries(kinds)){$('kind').add(new Option(label,id))}
$('total').textContent=`${data.screens.length} 张界面展示`;
function show(index){if(!visible.length)return;position=(index+visible.length)%visible.length;const row=visible[position];$('large').src=row.file;$('large').alt=row.title;$('viewer-title').textContent=row.title;$('viewer-meta').textContent=`${position+1} / ${visible.length} · ${kinds[row.kind]} · ${row.source} · ${row.file}`;if(!$('viewer').open)$('viewer').showModal()}
function render(){const q=$('search').value.toLowerCase();visible=data.screens.filter(x=>(!$('group').value||x.group===$('group').value)&&(!$('kind').value||x.kind===$('kind').value)&&[x.title,x.id,x.source,x.notes].join(' ').toLowerCase().includes(q));$('gallery').replaceChildren();$('count').textContent=`显示 ${visible.length} / ${data.screens.length} 张`;
for(const [group,label] of Object.entries(groups)){const rows=visible.filter(x=>x.group===group);if(!rows.length)continue;const section=document.createElement('section');const h=document.createElement('h2');h.textContent=`${label} · ${rows.length}`;section.append(h);const grid=document.createElement('div');grid.className='grid';section.append(grid);
for(const row of rows){const card=document.createElement('article');card.className='card';const button=document.createElement('button');button.className='image-button';button.setAttribute('aria-label',`放大：${row.title}`);const img=document.createElement('img');img.src=row.file;img.alt=row.title;img.loading='lazy';img.width=row.width;img.height=row.height;button.append(img);button.onclick=()=>show(visible.indexOf(row));const body=document.createElement('div');body.className='card-body';const title=document.createElement('h3');title.textContent=row.title;const badge=document.createElement('span');badge.className=`badge ${row.kind}`;badge.textContent=kinds[row.kind];const path=document.createElement('div');path.className='path';path.textContent=row.source;body.append(title,badge,path);if(row.notes){const note=document.createElement('div');note.className='note';note.textContent=row.notes;body.append(note)}card.append(button,body);grid.append(card)}$('gallery').append(section)}if(!visible.length){const empty=document.createElement('p');empty.className='empty';empty.textContent='没有匹配的界面';$('gallery').append(empty)}}
for(const id of ['search','group','kind'])$(id).addEventListener('input',render);$('close').onclick=()=>$('viewer').close();$('prev').onclick=()=>show(position-1);$('next').onclick=()=>show(position+1);document.addEventListener('keydown',e=>{if(!$('viewer').open)return;if(e.key==='ArrowRight'){e.preventDefault();show(position+1)}if(e.key==='ArrowLeft'){e.preventDefault();show(position-1)}});render();
</script></body></html>'''

def main():
    parser=argparse.ArgumentParser();parser.add_argument('capture_dir',type=Path);args=parser.parse_args()
    manifests=sorted(args.capture_dir.glob('manifest-*.json'))
    if not manifests:manifests=[args.capture_dir/'manifest.json']
    rows={};metadata={}
    for path in manifests:
        source=json.loads(path.read_text());metadata.update({k:v for k,v in source.items() if k!='screens'})
        for row in source['screens']:rows[row['id']]=row
    if not rows:raise SystemExit('No native captures')
    for row in rows.values():
        source=args.capture_dir/row['file'];raw=source.read_bytes()
        if raw[:8]!=b'\x89PNG\r\n\x1a\n':raise SystemExit(f'Not PNG: {source}')
        width,height=struct.unpack('>II',raw[16:24])
        if (width,height)!=(row['width'],row['height']):raise SystemExit('PNG dimensions do not match manifest')
        if len(raw)<4000:raise SystemExit(f'Blank/invalid capture: {source}')
        target=DEST/row['file'];target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
        doctor_titles={'doctor/02-report':'tmux Doctor：诊断报告','doctor/03-old-version':'tmux Doctor：低于兼容基线警告','doctor/04-missing':'tmux Doctor：未安装 tmux','doctor/05-mouse-off':'tmux Doctor：鼠标滚动未开启'}
        if row['id'] in doctor_titles:row['title']=doctor_titles[row['id']]
        if row['id'] in ('terminal/04-editor-keyboard','terminal/05-keyboard'):
            row['title']='编辑卡片：键盘避让布局' if row['id'].endswith('04-editor-keyboard') else '终端直接输入：键盘避让布局'
            row['notes']=(row.get('notes','')+' 系统输入法由另一进程绘制，不包含在视图快照内；本图展示应用的避让布局。').strip()
        if row['kind']=='terminal-render-fixture':
            row['notes']=(row.get('notes','')+' 终端输出为同版本 xterm.js 的离线渲染画面；未作为 Android WebView 运行验证。').strip()
        row.update(group=row['id'].split('/')[0],sha256=hashlib.sha256(raw).hexdigest(),bytes=len(raw))
    order={g:i for i,g in enumerate(GROUPS)}
    records=sorted(rows.values(),key=lambda x:(order[x['group']],x['file']))
    production_paths=['app/src/main','app/src/voice','app/src/standard','core/src/main','voice/src/main','web/terminal.js','web/protocol.js']
    source_commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    production_diff=subprocess.check_output(['git','diff','--name-only','HEAD','--',*production_paths],cwd=ROOT,text=True)
    digest=hashlib.sha256()
    for entry in production_paths:
        path=ROOT/entry
        for f in sorted(path.rglob('*') if path.is_dir() else [path]):
            if f.is_file():digest.update(f.relative_to(ROOT).as_posix().encode());digest.update(f.read_bytes())
    metadata['productionSourceSha256']=digest.hexdigest()
    metadata.update(sourceCommit=source_commit,productionSourceMatchesCommit=not production_diff.strip(),captureMethod='android-view-canvas',screens=records,count=len(records))
    (DEST/'manifest.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n')
    payload=json.dumps(metadata,ensure_ascii=False).replace('<','\\u003c')
    html=TEMPLATE.replace('__DATA__',payload).replace('__SOURCE__',escape(metadata.get('appVersion','working-tree'))).replace('__GROUPS__',json.dumps(GROUPS,ensure_ascii=False)).replace('__KINDS__',json.dumps(KINDS,ensure_ascii=False))
    (DEST/'index.html').write_text(html)
    lines=['# 当前程序界面图集','',f'代码基线：`{source_commit[:12]}`；工作区是否一致见 manifest.json。','',f'共 {len(records)} 张 Android 原生视图快照。打开 [离线浏览图集](index.html)，支持按界面类别、采集类型和关键词筛选，点击放大。也可直接查看 [Markdown 缩略图](gallery.md)。','',
      '所有地址使用文档保留网段，口令、指纹、终端输出均为演示数据。未读取或连接真实服务器，未使用真实私钥，语音状态不启动录音或识别。','',
      '## 采集类型','','- **实际程序界面**：当前代码中的页面、菜单、弹窗及交互结果，部分终端状态通过测试数据固定。','- **原生组件固定状态**：直接调用当前生产组件，例如录音光晕，以演示状态固定倒计时。','- **原生界面＋离线终端画面**：真实 MainActivity 原生控件和弹窗；终端输出由同版本 xterm.js 在桌面 Chromium 离线渲染后注入视图。软件模拟器 WebView 无法稳定工作，不能将这些图当作 Android WebView 运行验证。','- **瞬时分支预览**：网络验证、创建进度与语音失败等短暂分支，用当前资源文案和 Android Material 控件复现；不冒充已实际执行的认证或转写。','',
      '采集方式为 Android `View.draw(Canvas)`，由实际 Compose/View 树生成，包含应用内弹窗。它与系统整屏截屏不同：不包含其他进程绘制的状态栏图标、系统键盘、权限控制器或文件选择器。受保护页面仅使用专用测试设备中的演示数据；截图测试操作不打入 Release，也不改变正式应用的截图保护。','',
      '应用没有独立账户登录页面；SSH 登录表单见“服务器配置”。这里覆盖独立页面、菜单、弹窗和主要状态，不穷举语言、字号、主题、服务器数量及所有错误文案的组合。','',
      '界面分支对照与覆盖边界见 [coverage.md](coverage.md)，本次检查见 [verification.md](verification.md)。','','## 分类索引','','| 类别 | 图片数 |','| --- | ---: |']
    for group,title in GROUPS.items():lines.append(f'| {title} | {sum(r["group"]==group for r in records)} |')
    lines+=['','## 截图清单','','| 界面 | 类型 | 图片 |','| --- | --- | --- |']
    for row in records:lines.append(f'| {row["title"]} | {KINDS[row["kind"]]} | [打开]({row["file"]}) |')
    lines+=['','## 复现','','测试驱动：[UiCatalogTest.kt](../../../app/src/androidTestVoice/java/dev/mtmux/UiCatalogTest.kt)。只放在 `androidTestVoice` 中。','',
      '1. 先在 `web/` 执行 `node render-catalog-terminal.mjs` 生成离线终端素材，再构建当前 voice 调试 APK 和 Android 测试 APK，安装到空白专用 Android 15 测试模拟器。驱动只允许 debug 模拟器和 catalog 演示配置，默认在正常回归测试中跳过。','2. 执行 `adb shell am instrument -w -e class dev.mtmux.UiCatalogTest,dev.mtmux.UiTerminalCatalogTest -e uiCatalog true dev.mtmux.test/androidx.test.runner.AndroidJUnitRunner`。','3. 从 `sdcard/Android/data/dev.mtmux/files/ui-catalog` 拉取图片。','4. 执行 `python3 scripts/build_ui_catalog.py /path/to/ui-catalog`，然后在 `web/` 执行 `node check-ui-catalog.mjs` 验证离线图片和浏览交互。','',
      '可用 `-e catalogGroup home|server|settings|voice|dialogs|extras（UiCatalogTest）；terminal（UiTerminalCatalogTest）` 分组补采；`-e catalogSmoke true` 仅采集首页空态和列表态。软件模拟器无 KVM 时，需要给系统服务足够的超时预算。','',
      '本目录为 Git 忽略的本地产物；采集驱动及复现说明保留在源码中。','']
    (DEST/'README.md').write_text('\n'.join(lines))
    thumbs=['# 当前程序界面缩略图','',f'代码基线：`{source_commit[:12]}`。点击图片查看原图；筛选、来源和范围见 [交互图集](index.html) 与 [采集说明](README.md)。','','部分图片使用固定状态或离线终端正文，类型标在每张图下方。','']
    for group,title in GROUPS.items():
        thumbs += [f'## {title}','','| 界面 | 界面 | 界面 |','| --- | --- | --- |']
        cards=[f'<a href="{r["file"]}"><img src="{r["file"]}" width="180" alt="{escape(r["title"],quote=True)}"></a><br>{r["title"].replace("|","&#124;")}<br>{KINDS[r["kind"]]}' for r in records if r['group']==group]
        for start in range(0,len(cards),3):
            chunk=cards[start:start+3];thumbs.append('| '+' | '.join(chunk+['']*(3-len(chunk)))+' |')
        thumbs.append('')
    (DEST/'gallery.md').write_text('\n'.join(thumbs))
    print(json.dumps({'count':len(records),'groups':{k:sum(r['group']==k for r in records) for k in GROUPS},'gallery':str(DEST/'index.html')},ensure_ascii=False))
if __name__=='__main__':main()
