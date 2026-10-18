"""
Creates the launcher icon: Secher, Bello and Max fanned out (as in the main menu) on the wooden table.

- app/src/main/res/mipmap-<dpi>/ic_launcher_foreground.png   the fan (adaptive icon foreground)
- app/src/main/res/mipmap-<dpi>/ic_launcher_background.png   wood (adaptive icon background)
- app/src/main/res/mipmap-<dpi>/ic_launcher_monochrome.png   white silhouette (themed icons, Android 13+)
- app/src/main/res/mipmap-<dpi>/ic_launcher[_round].webp      composed icon for old launchers
- design/launcher_icon_preview.png                            preview with the usual masks
- fastlane/metadata/android/<locale>/images/icon.png         512 px store icon (F-Droid)

Usage: python design/make_launcher_icon.py
"""
import math
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
CARDS = ROOT / "design" / "cards"
RES = ROOT / "app" / "src" / "main" / "res"

# Pixels per dp
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
ADAPTIVE_DP = 108  # adaptive icon layer size
SAFE_RADIUS_DP = 31  # launchers may cut everything outside a circle of 33 dp; keep a margin
LEGACY_DP = 48

# Like the KrittenFan in MainMenu.kt (card width 72, offset 46, lift 6, angles ±18°), Max on top.
FAN = [("card_eichel_sieben", -18, -1, 1), ("card_schell_sieben", 0, 0, -1), ("card_herz_koenig", 18, 1, 1)]
OFFSET_X, LIFT = 46 / 72, 6 / 72  # relative to the card width


def card(name):
    return Image.open(CARDS / f"{name}.png").convert("RGBA")


def fan_extent(width):
    """Largest distance of a card corner from the fan centre, for a card width in dp."""
    w0, h0 = card(FAN[0][0]).size
    height = width * h0 / w0
    extent = 0
    for _, angle, dx, dy in FAN:
        cx, cy = dx * OFFSET_X * width, dy * LIFT * width
        a = math.radians(angle)
        for sx in (-1, 1):
            for sy in (-1, 1):
                x, y = sx * width / 2, sy * height / 2
                rx = cx + x * math.cos(a) - y * math.sin(a)
                ry = cy + x * math.sin(a) + y * math.cos(a)
                extent = max(extent, math.hypot(rx, ry))
    return extent


# Card width (dp) so that the whole fan stays inside the safe circle.
CARD_DP = SAFE_RADIUS_DP / fan_extent(1)


def render_fan(px_per_dp, size_dp=ADAPTIVE_DP, shadow=True, cards=FAN):
    size = round(size_dp * px_per_dp)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    centre = size / 2
    for name, angle, dx, dy in cards:
        src = card(name)
        w = round(CARD_DP * px_per_dp)
        h = round(w * src.height / src.width)
        img = src.resize((w, h), Image.LANCZOS)
        # Compose rotates clockwise for positive angles, PIL counter-clockwise.
        img = img.rotate(-angle, resample=Image.BICUBIC, expand=True)
        x = round(centre + dx * OFFSET_X * CARD_DP * px_per_dp - img.width / 2)
        y = round(centre + dy * LIFT * CARD_DP * px_per_dp - img.height / 2)
        if shadow:
            alpha = img.getchannel("A").point(lambda a: a * 0.45)
            blur = Image.new("RGBA", img.size, (0, 0, 0, 0))
            blur.putalpha(alpha)
            blur = blur.filter(ImageFilter.GaussianBlur(1.2 * px_per_dp))
            canvas.alpha_composite(blur, (x + round(0.6 * px_per_dp), y + round(1.0 * px_per_dp)))
        canvas.alpha_composite(img, (x, y))
    return canvas


def render_background(px_per_dp, size_dp=ADAPTIVE_DP):
    size = round(size_dp * px_per_dp)
    wood = Image.open(RES / "drawable-nodpi" / "table_wood.webp").convert("RGB")
    side = min(wood.size)
    left, top = (wood.width - side) // 2, (wood.height - side) // 2
    wood = wood.crop((left, top, left + side, top + side)).resize((size, size), Image.LANCZOS)
    wood = ImageEnhance.Brightness(wood).enhance(0.8)
    # Slight vignette so the bright cards stand out.
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((-size * 0.2, -size * 0.2, size * 1.2, size * 1.2), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(size * 0.15))
    dark = ImageEnhance.Brightness(wood).enhance(0.55)
    return Image.composite(wood, dark, mask).convert("RGBA")


def render_monochrome(px_per_dp):
    """White card shapes; each card cuts a thin gap into the ones below so the three stay visible."""
    alpha = None
    for i in range(len(FAN)):
        layer = render_fan(px_per_dp, shadow=False, cards=FAN[i:i + 1]).getchannel("A")
        if alpha is None:
            alpha = layer
            continue
        gap = layer.filter(ImageFilter.MaxFilter(2 * round(0.8 * px_per_dp) + 1))
        alpha = ImageChops.lighter(ImageChops.subtract(alpha, gap), layer)
    white = Image.new("RGBA", alpha.size, (255, 255, 255, 0))
    white.putalpha(alpha)
    return white



def legacy(px_per_dp, round_mask):
    """48 dp icon for launchers without adaptive icons: the inner 72 of 108 dp, masked."""
    full = render_background(px_per_dp)
    full.alpha_composite(render_fan(px_per_dp))
    inner = round(72 * px_per_dp)
    off = (full.width - inner) // 2
    icon = full.crop((off, off, off + inner, off + inner))
    size = round(LEGACY_DP * px_per_dp)
    icon = icon.resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size * 4, size * 4), 0)
    draw = ImageDraw.Draw(mask)
    if round_mask:
        draw.ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    else:
        draw.rounded_rectangle((0, 0, size * 4 - 1, size * 4 - 1), radius=size * 4 * 0.18, fill=255)
    icon.putalpha(ImageChops.multiply(icon.getchannel("A"), mask.resize((size, size), Image.LANCZOS)))
    return icon


def store_icon(size=512):
    """Square icon for the store listing (F-Droid): the visible inner 72 dp, unmasked."""
    s = size / 72
    full = render_background(s)
    full.alpha_composite(render_fan(s))
    off = (full.width - size) // 2
    return full.crop((off, off, off + size, off + size)).convert("RGB")


def preview():
    """Circle, squircle and rounded square as launchers show it, plus the themed variant."""
    s = 4
    full = render_background(s)
    full.alpha_composite(render_fan(s))
    inner = round(72 * s)
    off = (full.width - inner) // 2
    visible = full.crop((off, off, off + inner, off + inner))
    out = Image.new("RGBA", (inner * 4 + 50, inner + 20), (240, 240, 240, 255))
    for i, shape in enumerate(["circle", "squircle", "square"]):
        mask = Image.new("L", (inner, inner), 0)
        d = ImageDraw.Draw(mask)
        if shape == "circle":
            d.ellipse((0, 0, inner - 1, inner - 1), fill=255)
        else:
            d.rounded_rectangle((0, 0, inner - 1, inner - 1), radius=inner * (0.35 if shape == "squircle" else 0.12), fill=255)
        out.paste(visible, (10 + i * (inner + 10), 10), mask)
    mono = Image.new("RGBA", full.size, (50, 70, 60, 255))
    mono.alpha_composite(render_monochrome(s))
    mono = mono.crop((off, off, off + inner, off + inner))
    mask = Image.new("L", (inner, inner), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, inner - 1, inner - 1), fill=255)
    out.paste(mono, (10 + 3 * (inner + 10), 10), mask)
    out.save(ROOT / "design" / "launcher_icon_preview.png")


def main():
    for dpi, s in DENSITIES.items():
        folder = RES / f"mipmap-{dpi}"
        folder.mkdir(exist_ok=True)
        render_fan(s).save(folder / "ic_launcher_foreground.png", optimize=True)
        render_background(s).save(folder / "ic_launcher_background.png", optimize=True)
        render_monochrome(s).save(folder / "ic_launcher_monochrome.png", optimize=True)
        legacy(s, round_mask=False).save(folder / "ic_launcher.webp", "WEBP", quality=92)
        legacy(s, round_mask=True).save(folder / "ic_launcher_round.webp", "WEBP", quality=92)
    for locale in ("de-DE", "en-US"):
        folder = ROOT / "fastlane" / "metadata" / "android" / locale / "images"
        folder.mkdir(parents=True, exist_ok=True)
        store_icon().save(folder / "icon.png", optimize=True)
    preview()
    print(f"Card width {CARD_DP:.1f} dp; icons written for {', '.join(DENSITIES)}")


if __name__ == "__main__":
    main()
