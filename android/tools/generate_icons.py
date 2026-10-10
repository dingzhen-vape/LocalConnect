"""
生成 Android 启动图标。

为什么需要这个脚本：
    IDEA 的 Android 插件生成项目时，AndroidManifest.xml 里写了
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
    却没有生成 res/mipmap-*/ 下的图标文件，导致构建失败：
        AAPT: error: resource mipmap/ic_launcher not found

    这个脚本补上缺失的资源。以后想换图标，改这里的颜色和图案再跑一次即可。

用法（在仓库根目录执行）：
    python android/tools/generate_icons.py

依赖：Pillow
"""

from pathlib import Path

from PIL import Image, ImageDraw

# 各种屏幕密度对应的图标尺寸（px）
# 这些数字是 Android 官方规定的，不要随意改
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# 颜色
BG_COLOR = (0x1E, 0x88, 0xE5)  # Material Blue 600
FG_COLOR = (0xFF, 0xFF, 0xFF)  # 白色

OUT_ROOT = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"


def draw_icon(size, round_shape):
    """画一个图标。用 4 倍超采样再缩回来，得到平滑边缘（简单的抗锯齿手法）。"""
    ss = size * 4
    img = Image.new("RGBA", (ss, ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # ---- 背景 ----
    if round_shape:
        d.ellipse((0, 0, ss - 1, ss - 1), fill=BG_COLOR)
    else:
        # 圆角方形，圆角半径取 22%（接近 Android 自适应图标的观感）
        d.rounded_rectangle((0, 0, ss - 1, ss - 1), radius=int(ss * 0.22), fill=BG_COLOR)

    # ---- 前景：向上的箭头，表示「上传」 ----
    cx = ss / 2
    # 箭杆
    shaft_w = ss * 0.13
    d.rectangle(
        (cx - shaft_w / 2, ss * 0.44, cx + shaft_w / 2, ss * 0.74),
        fill=FG_COLOR,
    )
    # 箭头三角
    d.polygon(
        [
            (cx, ss * 0.26),          # 顶点
            (cx - ss * 0.20, ss * 0.48),  # 左下
            (cx + ss * 0.20, ss * 0.48),  # 右下
        ],
        fill=FG_COLOR,
    )

    return img.resize((size, size), Image.LANCZOS)


def main():
    made = 0
    for density, size in DENSITIES.items():
        out_dir = OUT_ROOT / f"mipmap-{density}"
        out_dir.mkdir(parents=True, exist_ok=True)

        for filename, is_round in (("ic_launcher.png", False), ("ic_launcher_round.png", True)):
            icon = draw_icon(size, is_round)
            target = out_dir / filename
            icon.save(target)
            made += 1
            print(f"  {target.relative_to(OUT_ROOT.parent.parent.parent.parent)}  {size}x{size}")

    print(f"\n完成，共生成 {made} 个图标文件")


if __name__ == "__main__":
    main()
