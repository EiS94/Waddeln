"""Converts the design assets into app resources (WebP).

    training/.venv/Scripts/python design/convert_assets.py      (from the project root)

- design/cards/card_<farbe>_<wert>.png  ->  app/src/main/res/drawable-nodpi/card_<farbe>_<wert>.webp
  (cropped so that the card sits centred: the scans carry a few pixels of shadow on the right and bottom)
- design/cards/rueckseite.png  ->  card_back.webp (+ card_back_landscape.webp, turned by 90 degrees)
- design/table_<style>.jpg  ->  table_<style>.webp (dark and light wood, chosen in the settings)
"""
from collections import Counter
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"


def is_frame(pixel) -> bool:
    """The thin grey frame line printed on the card (and its outline)."""
    return pixel[3] > 128 and sum(pixel[:3]) < 620


def centred(card: Image.Image) -> Image.Image:
    """
    Crops [card] so the printed inner frame has the same distance to the left and right edge (and to
    the top and bottom). The scans have a shadow on the right and bottom, so the card art sits off
    centre and a frame drawn around the card in the app would hide more of one side.
    """
    card = card.convert("RGBA")
    w, h = card.size
    px = card.load()

    def most_common(values):
        found = [v for v in values if v is not None]
        return Counter(found).most_common(1)[0][0] if found else None

    def first(candidates, hit):
        return next((c for c in candidates if hit(c)), None)

    left = most_common(first(range(6, 18), lambda x: is_frame(px[x, y])) for y in range(60, h - 60))
    right = most_common(first(range(w - 7, w - 22, -1), lambda x: is_frame(px[x, y])) for y in range(60, h - 60))
    top = most_common(first(range(6, 18), lambda y: is_frame(px[x, y])) for x in range(40, w - 40))
    bottom = most_common(first(range(h - 7, h - 22, -1), lambda y: is_frame(px[x, y])) for x in range(40, w - 40))
    if None in (left, right, top, bottom):
        return card
    return card.crop((0, 0, min(w, right + 1 + left), min(h, bottom + 1 + top)))


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    cards = sorted((ROOT / "design" / "cards").glob("card_*.png"))
    for png in cards:
        centred(Image.open(png)).save(OUT / (png.stem + ".webp"), "WEBP", quality=92, method=6)
    print(f"{len(cards)} card images converted")

    back = Image.open(ROOT / "design" / "cards" / "rueckseite.png")
    back.save(OUT / "card_back.webp", "WEBP", quality=92, method=6)
    back.rotate(90, expand=True).save(OUT / "card_back_landscape.webp", "WEBP", quality=92, method=6)
    print("card_back.webp", back.size)

    for style in ("dark", "light"):
        wood = Image.open(ROOT / "design" / f"table_{style}.jpg").convert("RGB")
        wood.save(OUT / f"table_{style}.webp", "WEBP", quality=85, method=6)
        print(f"table_{style}.webp", wood.size)


if __name__ == "__main__":
    main()
