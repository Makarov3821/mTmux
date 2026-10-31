# 拼接 png/compare.png：python3 docs/plan-ui/shared/compare.py（需先运行 render.mjs）
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
png = Path(__file__).resolve().parent.parent / 'png'
ids = ['01-home', '05-terminal', '06-connecting', '04-host-key']
rows = [('A 精修现状', 'a-refined'), ('B 终端原生', 'b-tui'), ('C 安静纸面', 'c-paper')]
W, H, pad, lab = 360, 800, 24, 40
font = ImageFont.truetype('/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc', 26)
img = Image.new('RGB', (pad + len(ids) * (W + pad), pad + len(rows) * (H + lab + pad)), '#e9e9ec')
d = ImageDraw.Draw(img)
for r, (name, sub) in enumerate(rows):
    y = pad + r * (H + lab + pad)
    d.text((pad, y), name, fill='#111', font=font)
    for c, i in enumerate(ids):
        im = Image.open(png / sub / f'{i}.png').convert('RGBA').resize((W, H), Image.LANCZOS)
        img.paste(im, (pad + c * (W + pad), y + lab), im)
img.save(png / 'compare.png', optimize=True)
