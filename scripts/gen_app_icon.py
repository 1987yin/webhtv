#!/usr/bin/env python3
"""生成 MO TV 应用图标全套资源（位图 + VectorDrawable）。

设计目标：在一屏同质化的浅色立方体图标里能远距离认出来。
  - 宝蓝 #2563EB -> 亮蓝 #4B93F8 对角渐变实心底。亮度是两头夹出来的，不能随意调：
      下限：桌面背景实测是深灰 (48,51,60)，图标中间调对它需要 >= 3:1 才不糊成一片，
            即 L_mid >= 0.20。同屏能跳出来的参考图标（EasyBox 4.0:1）都在这一档。
      上限：白字标压在最亮点上需要 >= 3:1（WCAG 1.4.11 大字/图形阈值，注意不是
            正文的 4.5:1），即 L_bright <= 0.30。当前 #4B93F8 上白字 3.08:1。
    再往上加亮就得把字标改成深色，那是另一套设计。
  - 同色系深浅渐变，不用双色调：双色调被启动器裁掉两端后会读成第三种颜色
  - 纯白极粗几何 M / O 字标，字形按几何参数自绘，不依赖系统字体，跨平台结果一致
  - O 兼作播放键（内嵌三角），点出影视属性；O 刻意大于 M 字高，详见 R_O_W
  - 字标收在自适应图标 66dp 安全区内，被系统裁成圆形也不缺字。注意 layout()
    只按【宽度】反算基准高度，而 O 高出 M 字高 25%，所以竖向实际占比要按
    h*R_O_W 另算——改动 R_O_W / FILL_* 后需重新确认安全区（见 --preview）

用法:
  py scripts/gen_app_icon.py --preview   只输出预览图到 build/icon-preview/
  py scripts/gen_app_icon.py             写入 app/src/**/res/
"""

import argparse
import os
import time

from PIL import Image, ImageDraw

GRAD_A = (0x25, 0x63, 0xEB)   # 左上 宝蓝
GRAD_B = (0x4B, 0x93, 0xF8)   # 右下 亮蓝
GRAD_A_HEX = "#2563EB"
GRAD_B_HEX = "#4B93F8"
WHITE = (255, 255, 255, 255)
HOLE = (0, 0, 0, 0)

SS = 4          # 超采样倍率，先大图绘制再降采样得到干净边缘
VIEWPORT = 512  # VectorDrawable 视口边长

# 渐变内缩比例：各种外形会裁掉画布对角的两端，若渐变端点落在被裁区域，
# 可见部分就只剩中段而退化成近似平色。按每种外形沿主对角线的真实可见
# 范围内缩端点，使【可见区】正好跨满 GRAD_A -> GRAD_B 的完整色域。
#   自适应图标：108dp 画布只有中央 72dp 可见 -> 起点 18/108 = 1/6
#   圆形：内切圆的对角极值在 0.5-0.5/sqrt2 处 -> (1 - 1/sqrt2)/2 ≈ 0.1464
#   圆角方形：圆角半径 0.22，圆弧对角极值 -> 0.22*(1 - 1/sqrt2) ≈ 0.0644
GRAD_INSET_ADAPTIVE = 1.0 / 6.0
GRAD_INSET_CIRCLE = 0.1464
GRAD_INSET_ROUNDED = 0.0644
GRAD_INSET_SQUARE = 0.0

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 字标几何参数，全部以 M 的 cap height h 为单位
R_STEM = 0.26     # M 竖干宽度 / M 斜笔水平宽度
R_M_W = 1.06      # M 宽度（需足够宽，否则两侧字怀被挤成细缝）
R_O_W = 1.25      # O 直径 / M cap height。O 是播放徽章，刻意大于字高：
                  # 1.0 时 O 的字怀在 48px 下只有约 5px，塞不进能读出形状的三角。
                  # 实测 1.25 时 48px 下三角高 5.65px，环厚与 M 竖干只差 2%。
R_RING = 0.98     # O 环厚度 / M 竖干宽度（1.0 = 完全等重）
R_TRI = 0.74      # 播放三角半高 / O 字怀半径（外接半径约为 0.85，余量 15%）
R_GAP = 0.11      # M 与 O 的间距
R_TOTAL = R_M_W + R_GAP + R_O_W

# 字标占画面宽度的比例
FILL_SAFE = 0.52    # 自适应图标：收在 66/108 安全区内
FILL_LEGACY = 0.70  # 传统位图图标：满版更醒目

def m_shapes(x, y, h):
    """极粗几何 M：拆成 4 个凸多边形的并集（几何粗体的标准构造）。

    左右两根竖干 + 两道平行四边形斜笔。字怀和中缝由形状自然留出，
    不用手推整条外轮廓——手推轮廓极易让中缝退化成尖楔。
    斜笔从竖干顶端出发，交汇于中轴 apex 处。
    """
    w = h * R_M_W
    s = h * R_STEM
    cx = x + w / 2
    apex = y + h * 0.72       # 中间 V 的谷底高度
    top = y
    bot = y + h

    left_stem = [(x, top), (x + s, top), (x + s, bot), (x, bot)]
    right_stem = [(x + w - s, top), (x + w, top), (x + w, bot), (x + w - s, bot)]
    # 斜笔顶部贴竖干顶端，底部在中轴收成尖点（平底会读成 "И" 或断笔）。
    # 两道斜笔各自的尖点重合于 apex，并集后形成干净的 V。
    left_diag = [(x, top), (x + s, top), (cx, apex)]
    right_diag = [(x + w - s, top), (x + w, top), (cx, apex)]
    return [left_stem, right_stem, left_diag, right_diag]


def layout(size, fill):
    """按画面尺寸和填充比算出字标的基准几何。

    返回 (x0, y0, h)：x0 是字标左边界，h 是 M 的 cap height，
    y0 是 M 的顶边。O 比 h 高，垂直方向以画面中线为准另算（见 o_geom）。
    """
    total_w = size * fill
    h = total_w / R_TOTAL
    x0 = (size - total_w) / 2
    y0 = (size - h) / 2
    return x0, y0, h


def o_geom(x0, h):
    """O（播放徽章）的几何：(左边界, 上边界, 直径, 环厚)。

    O 与 M 共用垂直中线，因此 O 上下各超出 M 字高 (R_O_W-1)/2，
    这是刻意的徽章效果而非排版失误。
    """
    d = h * R_O_W
    ox = x0 + h * (R_M_W + R_GAP)
    oy = (h - d) / 2          # 相对 M 顶边的偏移，负值表示更高
    ring = h * R_STEM * R_RING
    return ox, oy, d, ring


def play_triangle(cx, cy, inner_r):
    """播放三角：等腰，尖端朝右，光学对齐 O 的圆心。

    左右按 -0.55/+0.95 分配而非 -0.5/+0.5：几何居中会让三角显得偏右，
    因为右侧被拉成尖端、视觉重量小于左侧的整条竖边。这样一来三角的
    重心落在圆心左侧约 0.05r 处，正是所需的补偿量。
    """
    r = inner_r * R_TRI
    return [(cx - r * 0.55, cy - r), (cx - r * 0.55, cy + r), (cx + r * 0.95, cy)]


# --- 位图绘制 ----------------------------------------------------------------
def make_gradient(size, inset=0.0):
    """左上 -> 右下对角线性渐变。沿对角线投影插值，符合线性渐变观感。

    inset: 渐变端点沿对角线内缩的比例。可见区落在 [inset, 1-inset] 内，
    该区间被重映射到完整色域，两端超出的部分钳制到端点色。
    """
    grad = Image.new("RGB", (size, size))
    px = grad.load()
    denom = 2.0 * (size - 1)
    span = max(1e-6, 1.0 - 2.0 * inset)
    for y in range(size):
        for x in range(size):
            t = (x + y) / denom
            t = min(1.0, max(0.0, (t - inset) / span))
            px[x, y] = (
                round(GRAD_A[0] + (GRAD_B[0] - GRAD_A[0]) * t),
                round(GRAD_A[1] + (GRAD_B[1] - GRAD_A[1]) * t),
                round(GRAD_A[2] + (GRAD_B[2] - GRAD_A[2]) * t),
            )
    return grad.convert("RGBA")


def draw_wordmark(size, fill):
    """把 MO 字标画成一张 RGBA 图层（O 内含播放三角）。"""
    layer = Image.new("RGBA", (size, size), HOLE)
    d = ImageDraw.Draw(layer)
    x0, y0, h = layout(size, fill)

    for shape in m_shapes(x0, y0, h):
        d.polygon(shape, fill=WHITE)

    # O：外圆挖内圆成环，环内再放播放三角
    ox, oy, ow, ring = o_geom(x0, h)
    oy += y0
    d.ellipse([ox, oy, ox + ow, oy + ow], fill=WHITE)
    d.ellipse([ox + ring, oy + ring, ox + ow - ring, oy + ow - ring], fill=HOLE)
    d.polygon(play_triangle(ox + ow / 2, oy + ow / 2, ow / 2 - ring), fill=WHITE)
    return layer


def _mask(size, shape, radius_ratio=0.0):
    m = Image.new("L", (size, size), 0)
    md = ImageDraw.Draw(m)
    if shape == "circle":
        md.ellipse([0, 0, size - 1, size - 1], fill=255)
    elif shape == "rounded":
        md.rounded_rectangle([0, 0, size - 1, size - 1],
                             radius=size * radius_ratio, fill=255)
    else:
        md.rectangle([0, 0, size - 1, size - 1], fill=255)
    return m


def render(size, shape="rounded", fill=FILL_LEGACY, radius_ratio=0.22, inset=None):
    """渲染完整图标（渐变底 + 字标 + 外形裁切）。

    inset 默认按外形自动选取，使裁切后的可见区跨满完整色域。
    """
    if inset is None:
        inset = {"circle": GRAD_INSET_CIRCLE,
                 "rounded": GRAD_INSET_ROUNDED}.get(shape, GRAD_INSET_SQUARE)
    big = size * SS
    img = make_gradient(big, inset)
    img.alpha_composite(draw_wordmark(big, fill))
    if shape != "square":
        img.putalpha(_mask(big, shape, radius_ratio))
    return img.resize((size, size), Image.LANCZOS)


def render_banner(w, h):
    """Android TV banner 320x180：渐变底 + 左置字标，四周留安全边距。

    电视端图标会被放大显示且可能有 overscan，字标高度控制在 44% 左右。
    """
    bw, bh = w * SS, h * SS
    # banner 是完整矩形不裁切，渐变按原样铺满
    img = make_gradient(max(bw, bh)).resize((bw, bh), Image.LANCZOS)
    # 以 banner 高度为画布渲染字标，再整体缩放贴入，保证上下留白对称
    mark_box = int(bh * 0.62)
    mark = draw_wordmark(mark_box, 0.92)
    img.alpha_composite(mark, (int(bw * 0.075), int((bh - mark_box) / 2)))
    return img.resize((w, h), Image.LANCZOS)


# --- VectorDrawable ----------------------------------------------------------
def _p(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def wordmark_paths(fill):
    """返回 (M 的 pathData, O 的几何参数)，坐标基于 512 视口。"""
    x0, y0, h = layout(VIEWPORT, fill)
    # 4 个子多边形拼成一条 path；均为同向凸多边形，nonZero 填充即为并集
    m_path = "".join(
        "M" + " ".join(f"{_p(px)},{_p(py)}" for px, py in shape) + "z"
        for shape in m_shapes(x0, y0, h)
    )

    ox, oy, ow, ring = o_geom(x0, h)
    return m_path, (ox, y0 + oy, ow, ring)


def ellipse_path(cx, cy, rx, ry, clockwise=True):
    """用两段 arcTo 画整椭圆。

    clockwise 只改变 sweep 标志的取值。调用方用的是 fillType="evenOdd"，
    它按射线穿越次数判定内外、不看绕向，所以方向对渲染结果无影响；
    保留该参数是为了让"外圆 + 内圆"这对调用在语义上读得出挖空意图。
    """
    sw = 1 if clockwise else 0
    return (f"M{_p(cx - rx)},{_p(cy)}"
            f"a{_p(rx)},{_p(ry)} 0 1,{sw} {_p(rx * 2)},0"
            f"a{_p(rx)},{_p(ry)} 0 1,{sw} {_p(-rx * 2)},0z")


def vector_wordmark(fill, color="#FFFFFF"):
    """生成纯字标的 VectorDrawable（用于自适应图标前景 / 单色主题图标）。

    尺寸固定 108dp 方形：adaptive-icon 一律按方形渲染，曾经给 banner 的
    foreground 传过 320x180，结果 512 方形视口被拉成 16:9 把字标纵向压扁。
    横幅另由 ic_banner.png 承担，这里不再提供非方形入口。
    """
    m_path, (ox, oy, ow, ring) = wordmark_paths(fill)
    cx, cy = ox + ow / 2, oy + ow / 2
    # O 用 evenOdd 环：外圆顺时针 + 内圆逆时针，一条 path 完成挖空
    o_path = (ellipse_path(cx, cy, ow / 2, ow / 2, True)
              + ellipse_path(cx, cy, ow / 2 - ring, ow / 2 - ring, False))
    tri = play_triangle(cx, cy, ow / 2 - ring)
    tri_path = "M" + " ".join(f"{_p(px)},{_p(py)}" for px, py in tri) + "z"
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- 由 scripts/gen_app_icon.py 生成，请勿手工编辑 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="{VIEWPORT}"
    android:viewportHeight="{VIEWPORT}">
    <path
        android:fillColor="{color}"
        android:pathData="{m_path}" />
    <path
        android:fillColor="{color}"
        android:fillType="evenOdd"
        android:pathData="{o_path}" />
    <path
        android:fillColor="{color}"
        android:pathData="{tri_path}" />
</vector>
"""


def vector_background():
    """自适应图标背景：对角线性渐变铺满 108dp 视口。

    渐变端点必须内缩：启动器只显示 108dp 画布中央的 72dp，若端点落在
    0 和 512（画布对角顶点），两端色都在裁掉的区域里，可见部分只剩中段，
    观感退化为近似平色。这里把端点收到可见区的对角边界上，并用
    android:tileMode="clamp" 让区外保持端点色。
    """
    lo = VIEWPORT * GRAD_INSET_ADAPTIVE
    hi = VIEWPORT - lo
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- 由 scripts/gen_app_icon.py 生成，请勿手工编辑 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="{VIEWPORT}"
    android:viewportHeight="{VIEWPORT}">
    <path android:pathData="M0,0h{VIEWPORT}v{VIEWPORT}h-{VIEWPORT}z">
        <aapt:attr xmlns:aapt="http://schemas.android.com/aapt"
            name="android:fillColor">
            <gradient
                android:startX="{_p(lo)}"
                android:startY="{_p(lo)}"
                android:endX="{_p(hi)}"
                android:endY="{_p(hi)}"
                android:type="linear"
                android:tileMode="clamp">
                <item android:offset="0" android:color="{GRAD_A_HEX}" />
                <item android:offset="1" android:color="{GRAD_B_HEX}" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
"""


ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""

BANNER_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_banner_foreground" />
</adaptive-icon>
"""


# --- 输出清单 ----------------------------------------------------------------
DENSITIES = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
             ("xxhdpi", 144), ("xxxhdpi", 192)]
MAIN_RES = "app/src/main/res"


def save_img(img, rel, **kw):
    path = os.path.join(REPO, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    # Windows 上 Gradle 文件监视器/杀软会瞬时占用 res/ 里的文件，
    # 导致写入随机抛 OSError(EINVAL)。重试几次即可，不是路径问题。
    for attempt in range(8):
        try:
            img.save(path, **kw)
            break
        except OSError:
            if attempt == 7:
                raise
            time.sleep(0.25)
    print(f"  {rel:62s} {img.size[0]}x{img.size[1]}")


def save_text(text, rel):
    path = os.path.join(REPO, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print(f"  {rel}")


def do_preview():
    out = "build/icon-preview"
    print(f"[preview] -> {out}/")
    save_img(render(512, "rounded"), f"{out}/rounded_512.png")
    save_img(render(512, "circle", fill=0.64), f"{out}/circle_512.png")
    save_img(render(512, "square"), f"{out}/square_512.png")
    save_img(render(48, "rounded"), f"{out}/rounded_48.png")
    save_img(render(72, "rounded"), f"{out}/rounded_72.png")
    save_img(render(96, "rounded"), f"{out}/rounded_96.png")
    save_img(render_banner(320, 180), f"{out}/banner.png")
    # 自适应图标被系统裁成圆形/圆角方形时的实际观感
    save_img(render(432, "circle", fill=FILL_SAFE), f"{out}/adaptive_circle.png")
    save_img(render(432, "rounded", fill=FILL_SAFE, radius_ratio=0.30),
             f"{out}/adaptive_squircle.png")
    # 单色主题图标预览（白字铺黑底）
    mono = Image.new("RGBA", (432, 432), (0x1F, 0x1F, 0x1F, 255))
    mono.alpha_composite(draw_wordmark(432, FILL_SAFE))
    save_img(mono, f"{out}/monochrome.png")


def do_write():
    print("[vector drawables]")
    save_text(vector_background(), f"{MAIN_RES}/drawable/ic_launcher_background.xml")
    save_text(vector_wordmark(FILL_SAFE), f"{MAIN_RES}/drawable/ic_launcher_foreground.xml")
    # 单色主题图标（Android 13+）：系统会自行取色重染并铺背景，因此这里
    # 与 foreground 共用同一套几何、同为白色即可，由系统 tint 决定最终颜色。
    save_text(vector_wordmark(FILL_SAFE),
              f"{MAIN_RES}/drawable/ic_launcher_monochrome.xml")
    save_text(ADAPTIVE_XML, f"{MAIN_RES}/mipmap-anydpi-v26/ic_launcher.xml")
    save_text(ADAPTIVE_XML, f"{MAIN_RES}/mipmap-anydpi-v26/ic_launcher_round.xml")

    print("[legacy launcher bitmaps]")
    for name, base in DENSITIES:
        # 传统图标沿用仓库既有的偏大尺寸规格（mdpi 128 起）
        px = {"mdpi": 128, "hdpi": 192, "xhdpi": 256,
              "xxhdpi": 384, "xxxhdpi": 512}[name]
        save_img(render(px, "rounded"), f"{MAIN_RES}/mipmap-{name}/ic_launcher.png",
                 format="PNG")
        save_img(render(base, "circle", fill=0.64),
                 f"{MAIN_RES}/mipmap-{name}/ic_launcher_round.webp",
                 format="WEBP", lossless=True, quality=100)

    print("[play store]")
    save_img(render(512, "square"), "app/src/main/ic_launcher-playstore.png",
             format="PNG")

    print("[tv banner]")
    # adaptive-icon 会强制按方形渲染，foreground 必须用方形视口，
    # 否则 512x512 视口配 320x180 尺寸会把字标纵向压扁。
    # 320x180 的真实横幅由下面的 ic_banner.png 承担（API < 26 及 TV 启动器）。
    save_text(vector_wordmark(FILL_SAFE),
              "app/src/leanback/res/drawable/ic_banner_foreground.xml")
    save_text(BANNER_XML, "app/src/leanback/res/mipmap-anydpi-v26/ic_banner.xml")
    save_img(render_banner(320, 180), "app/src/leanback/res/drawable/ic_banner.png",
             format="PNG")
    print("\nDone.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true",
                    help="只输出预览图到 build/icon-preview/，不改 res/")
    args = ap.parse_args()
    do_preview() if args.preview else do_write()


if __name__ == "__main__":
    main()
