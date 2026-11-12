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
   ```
4. Build and look at it on a device: `./gradlew :app:android:assembleDebug :app:desktop:compileKotlin`.
   Launchers crop the adaptive icon; the dust is allowed to be cut off, but the diary and padlock
   must stay in the central 66x66 of the 108x108 canvas.
5. If the background color changed, update `icon_background` in
   `app/android/src/main/res/values/colors.xml` and the tile (`rect`) in the generator.

## Other places that need an image

Not generated yet; render the `.svg` when you need them:

- Google Play listing icon: 512x512 PNG. `rsvg-convert -w 512 -h 512 icon.svg -o icon.png`
- Desktop installers (`.icns` for macOS, `.ico` for Windows): see [BACKLOG.md](../../BACKLOG.md).
