"""
Creates the feature graphic of the Play Store listing (1024 x 500): the Kritten fanned out on the
dark wooden table next to the title, like the main menu.

- fastlane/metadata/android/<locale>/images/featureGraphic.png

Usage: python design/make_feature_graphic.py <serif bold font> <sans font>
The app uses Android's own fonts (Noto Serif Bold for the title, Roboto); they can be copied from a
phone or emulator, e.g. adb pull /system/fonts/NotoSerif-Bold.ttf and /system/fonts/Roboto-Regular.ttf.
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont

from make_launcher_icon import FAN, LIFT, OFFSET_X, card

ROOT = Path(__file__).resolve().parent.parent
WIDTH, HEIGHT = 1024, 500
HIGHLIGHT = (0xFF, 0xD5, 0x4F)
CARD_WIDTH = 128


def background():
    wood = Image.open(ROOT / "design" / "table_dark.jpg").convert("RGB")
    scale = max(WIDTH / wood.width, HEIGHT / wood.height)
    wood = wood.resize((round(wood.width * scale), round(wood.height * scale)), Image.LANCZOS)
    left, top = (wood.width - WIDTH) // 2, (wood.height - HEIGHT) // 2
    wood = wood.crop((left, top, left + WIDTH, top + HEIGHT))
    # Darker towards the edges so cards and title stand out.
    mask = Image.new("L", (WIDTH, HEIGHT), 0)
    ImageDraw.Draw(mask).ellipse((-WIDTH * 0.1, -HEIGHT * 0.3, WIDTH * 1.1, HEIGHT * 1.3), fill=255)
    mask = mask.filter(ImageFilter.GaussianBlur(120))
    dark = ImageEnhance.Brightness(wood).enhance(0.5)
    return Image.composite(wood, dark, mask).convert("RGBA")


def fan(canvas, cx, cy):
    for name, angle, dx, dy in FAN:
        src = card(name)
        h = round(CARD_WIDTH * src.height / src.width)
        img = src.resize((CARD_WIDTH, h), Image.LANCZOS).rotate(-angle, resample=Image.BICUBIC, expand=True)
        x = round(cx + dx * OFFSET_X * CARD_WIDTH - img.width / 2)
        y = round(cy + dy * LIFT * CARD_WIDTH - img.height / 2)
        shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
        shadow.putalpha(img.getchannel("A").point(lambda a: a * 0.5))
        canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(6)), (x + 4, y + 7))
        canvas.alpha_composite(img, (x, y))


def text_with_shadow(canvas, xy, text, font, fill, anchor):
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).text((xy[0] + 3, xy[1] + 4), text, font=font, fill=(0, 0, 0, 170), anchor=anchor)
    canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(4)))
    ImageDraw.Draw(canvas).text(xy, text, font=font, fill=fill, anchor=anchor)


def main(serif_path, sans_path):
    canvas = background()
    fan(canvas, cx=235, cy=255)
    title = ImageFont.truetype(serif_path, 112)
    subtitle = ImageFont.truetype(sans_path, 38)
    text_with_shadow(canvas, (695, 238), "Waddeln", title, HIGHLIGHT, "ms")
    text_with_shadow(canvas, (695, 300), "Das fränkische Kartenspiel", subtitle, (255, 255, 255), "ms")
    image = canvas.convert("RGB")
    for locale in ("de-DE", "en-US"):
        folder = ROOT / "fastlane" / "metadata" / "android" / locale / "images"
        folder.mkdir(parents=True, exist_ok=True)
        image.save(folder / "featureGraphic.png", optimize=True)
    print("featureGraphic.png written")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
