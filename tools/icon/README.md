# App icon

`gen_icon.py` draws the app icon: a closed diary with a padlock on the cover, its top-right corner
turning to dust. It needs only Python 3 (no libraries). It is the source of truth: do not edit the
generated files by hand.

The output format follows the extension of the file you give it:

```
python3 tools/icon/gen_icon.py OUT.{svg,xml} [seed] [feature ...]
```

| Extension | What it is | Goes to |
|---|---|---|
| `.svg` | The whole icon, tile included. The desktop window icon, and the preview. | `app/desktop/src/main/resources/icon.svg` |
| `.xml` | Android foreground layer (no tile: the tile is `@color/icon_background`). | `app/android/src/main/res/drawable/ic_launcher_foreground.xml` |

`seed` (default 5) picks which chunks fly off and where. `feature`s (default `border ribbon`) add
details: `strap`, `pages`, `all`, `none`. Colors come from the app theme (`ui/theme/Theme.kt`).

## Changing the icon

1. Edit `gen_icon.py` (geometry and colors are constants at the top).
2. Preview: `python3 tools/icon/gen_icon.py /tmp/icon.svg && open /tmp/icon.svg`
   (or `rsvg-convert -w 1024 /tmp/icon.svg -o /tmp/icon.png`).
3. Regenerate the two files the apps use:
   ```
   python3 tools/icon/gen_icon.py app/android/src/main/res/drawable/ic_launcher_foreground.xml
   python3 tools/icon/gen_icon.py app/desktop/src/main/resources/icon.svg
   rsvg-convert -w 1024 -h 1024 app/desktop/src/main/resources/icon.svg -o app/desktop/src/main/resources/icon.png
   ```
   The desktop app sets its Dock/taskbar icon from `icon.png` (`setTaskbarIcon` in `Main.kt`), so
   that PNG is rendered from the SVG with `rsvg-convert`. Don't use ImageMagick's `magick` for this:
   its built-in SVG renderer drops strokes (the padlock's shackle and the inner border on the cover).
4. Build and look at it on a device: `./gradlew :app:android:assembleDebug :app:desktop:compileKotlin`.
   Launchers crop the adaptive icon; the dust is allowed to be cut off, but the diary and padlock
   must stay in the central 66x66 of the 108x108 canvas.
5. If the background color changed, update `icon_background` in
   `app/android/src/main/res/values/colors.xml` and the tile (`rect`) in the generator.

## Google Play listing icon

Play wants a 512x512 32-bit sRGB PNG of at most 1024 KB, a full square with no rounded corners and
no shadow (Play adds both). The `square` option draws the tile that way:

```
python3 tools/icon/gen_icon.py build/play-icon.svg 5 square
rsvg-convert -w 512 -h 512 build/play-icon.svg -o build/play-icon-512.png
magick build/play-icon-512.png -define png:color-type=6 build/play-icon-512.png   # force RGBA
```

`build/` is ignored by git.

## Google Play feature graphic

A 1024x500 banner: "Secrt" on the left, the artwork on the right. The text is a system font (Avenir
Next, with fallbacks), so render it on a Mac, or the font will differ.

```
python3 tools/icon/gen_icon.py build/play-banner.svg 5 banner
rsvg-convert -w 1024 -h 500 build/play-banner.svg -o build/play-feature-1024x500.png
```

## Desktop package icons

The installers use files in `app/desktop/packaging/`, made from the same SVG with `rsvg-convert`
(the generator doesn't write them, and `magick`'s built-in renderer drops strokes, see above):

```
mkdir -p build/iconset && for s in 16 32 128 256 512; do
  d=$((s*2)); rsvg-convert -w $s -h $s app/desktop/src/main/resources/icon.svg -o build/iconset/icon_${s}x${s}.png
  rsvg-convert -w $d -h $d app/desktop/src/main/resources/icon.svg -o build/iconset/icon_${s}x${s}@2x.png
done
iconutil -c icns build/iconset -o app/desktop/packaging/secrt.icns          # macOS (.dmg, .app)
for s in 16 32 48 64 128 256; do rsvg-convert -w $s -h $s app/desktop/src/main/resources/icon.svg -o build/ico-$s.png; done
magick build/ico-16.png build/ico-32.png build/ico-48.png build/ico-64.png build/ico-128.png build/ico-256.png app/desktop/packaging/secrt.ico   # Windows (.msi)
rsvg-convert -w 512 -h 512 app/desktop/src/main/resources/icon.svg -o app/desktop/packaging/secrt.png   # Linux (.deb)
```

Regenerate them whenever the icon changes, and commit them with it. `build/` is ignored by git.
