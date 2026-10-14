"""Converts the design assets into app resources (WebP).

    training/.venv/Scripts/python design/convert_assets.py      (from the project root)

- design/cards/card_<farbe>_<wert>.png  ->  app/src/main/res/drawable-nodpi/card_<farbe>_<wert>.webp
- clean wood area of design/Spieltisch.jpeg -> app/src/main/res/drawable-nodpi/table_wood.webp
  (placeholder until a separate wood texture is available)
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

    draft = Image.open(ROOT / "design" / "Spieltisch.jpeg").convert("RGB")
    # Area without cards and labels (pixels of the 930 x 2048 screenshot)
    wood = draft.crop((90, 380, 840, 1660))
    wood.save(OUT / "table_wood.webp", "WEBP", quality=85, method=6)
    print("table_wood.webp", wood.size)


if __name__ == "__main__":
    main()
