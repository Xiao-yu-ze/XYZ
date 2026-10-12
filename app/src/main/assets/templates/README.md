# templates/

Put your PVZ2 template PNGs here. **Nothing in this folder is required by the build** —
the app scans the folder at runtime and simply skips rules whose template is missing.

## How templates are matched

* Templates are authored for a **1280x720** canvas. At runtime each one is resized by
  `screenHeight / 720` (`INTER_AREA`) before matching, so one set works on every
  resolution (see `core/ScreenAdapter.kt`).
* Matching uses `Imgproc.matchTemplate(..., TM_CCOEFF_NORMED)` and accepts a hit when
  `Core.minMaxLoc(result).maxVal >= threshold`.
* A rule's `roi` is a rectangle in **720p logical coordinates**; matching is restricted to
  that region, which is both faster and less prone to false positives.

## Recommended files

Names must match the `template = "..."` strings in
`app/src/main/java/com/pvz2/auto/task/Pvz2Tasks.kt`.

| File | Suggested region (720p) | Notes |
|---|---|---|
| `sun.png` | any | a single collectable sun sprite |
| `seed_packet.png` | `0,600 - 600,720` | a seed packet in the seed bank |
| `btn_continue.png` | any | dialog "Continue"/"OK" button |
| `btn_close.png` | any | dialog close (X) button |

## Capturing them

1. Install and open the app, tap **Grab one screenshot**.
2. Pull the PNG from the app's external files dir:
   `adb pull /sdcard/Android/data/com.pvz2.auto/files/shots/`
3. The screenshot is at your device's real resolution. Because templates live in the
   720p logical space, scale the shot down to **1280x720** first, then crop the region
   you want to match, and save it here.

Keep templates tight around a distinctive, high-contrast feature. Avoid regions that
animate or contain a score/timer.
