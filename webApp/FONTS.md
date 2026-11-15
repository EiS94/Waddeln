# Fonts of the web app

Browsers give a Compose app no system fonts, so the web app brings the two it needs
(`src/commonMain/composeResources/font`). Both are the fonts Android itself uses, taken from the
Android SDK (`platforms/android-29/data/fonts`):

| File | Font | Licence |
|---|---|---|
| `noto_serif_bold.ttf` | Noto Serif Bold (Google) | SIL Open Font License 1.1 |
| `droid_sans_mono.ttf` | Droid Sans Mono (Google) | Apache License 2.0 |

The Android app keeps using the system fonts; these files are not part of the APK.
