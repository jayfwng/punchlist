"""런처 아이콘 생성: python3 tools/make_icon.py"""
from PIL import Image, ImageDraw
import os
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
def draw(n):
    S = n * 8
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([S*0.04, S*0.04, S*0.96, S*0.96], radius=S*0.22, fill=(30, 41, 59))
    # 클립보드
    d.rounded_rectangle([S*0.24, S*0.2, S*0.76, S*0.84], radius=S*0.06, fill=(255, 255, 255))
    d.rounded_rectangle([S*0.38, S*0.14, S*0.62, S*0.26], radius=S*0.04, fill=(249, 115, 22))
    for i, y in enumerate([0.38, 0.53, 0.68]):
        x0 = S*0.31
        if i < 2:
            w = S*0.025
            d.line([(x0, S*y), (x0+S*0.04, S*(y+0.04)), (x0+S*0.11, S*(y-0.05))], fill=(22, 163, 74), width=int(w*2))
        else:
            d.ellipse([x0, S*(y-0.045), x0+S*0.09, S*(y+0.045)], outline=(249, 115, 22), width=int(S*0.03))
        d.rounded_rectangle([S*0.47, S*(y-0.02), S*0.69, S*(y+0.02)], radius=S*0.02, fill=(148, 163, 184))
    return im.resize((n, n), Image.LANCZOS)
root = os.path.join(os.path.dirname(__file__), "..", "app", "res")
for k, n in SIZES.items():
    p = os.path.join(root, f"mipmap-{k}")
    os.makedirs(p, exist_ok=True)
    draw(n).save(os.path.join(p, "ic_launcher.png"))
draw(512).save(os.path.join(os.path.dirname(__file__), "icon512.png"))
