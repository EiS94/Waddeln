"""Converts the design assets into app resources (WebP).

    training/.venv/Scripts/python design/convert_assets.py      (from the project root)

- design/cards/card_<farbe>_<wert>.png  ->  app/src/main/res/drawable-nodpi/card_<farbe>_<wert>.webp
- design/cards/rueckseite.png  ->  card_back.webp (+ card_back_landscape.webp, turned by 90 degrees)
- design/table_<style>.jpg  ->  table_<style>.webp (dark and light wood, chosen in the settings)
"""
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    cards = sorted((ROOT / "design" / "cards").glob("card_*.png"))
    for png in cards:
        Image.open(png).save(OUT / (png.stem + ".webp"), "WEBP", quality=92, method=6)
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
