# Component visual regression

The component catalog has Compose previews in
`apps/android/app/src/main/java/ai/memora/app/ui/ComponentPreviews.kt`. Previews cover
Azerbaijani labels, both themes, large text, long labels, and the confirmation sheet.

The repository does not use a screenshot-test runner. Its Android instrumentation runner is a
custom opt-in `Instrumentation` entry point for synthetic storage and provider probes; replacing it
would risk disabling existing test workflows. Instead, debug builds include an exported,
debug-only `ComponentGalleryActivity` with fixed presentation-only values and no database access.
The Gradle task captures its Compose output on an emulator and compares decoded RGBA pixels against
reviewed PNG baselines. Normal runs never update baselines. The comparator fails on a size mismatch,
more than 0.3% materially changed pixels (per-channel delta greater than 12/255), or mean RGB delta
above 0.35/255.

Run against one disposable emulator:

```sh
cd apps/android
ANDROID_SERIAL=emulator-5556 ./gradlew :app:componentVisualRegression --no-daemon --console=plain --max-workers=1
```

Expected screenshots are in `docs/ui-ux-redesign-v1/phase-1/component-goldens/`; captures and
diff inputs are ignored under `apps/android/app/build/ui-visual-regression/`. To intentionally
record a baseline after reviewing the produced PNGs, build/install the debug app and run:

```sh
python3 tools/ui_visual_regression.py --record
```

The script rejects physical devices, requires an emulator, and restores font scale and orientation
settings on exit. It changes no production data and does not call Gemini, open the microphone, or
access SQLCipher. Golden images are deterministic Azerbaijani UI examples, not screenshots of user
records.

If NumPy is installed, RGBA comparison uses its vectorized path. The pure-Python
implementation remains the fallback on clean machines; both paths preserve the
same per-pixel tolerance and mean-delta thresholds.

## Latest evidence — ACT-0278

The full current 31-state emulator comparison on v178 is 30/31. Only
`confirmation-light` fails (9.020% changed pixels, mean RGB delta 9.942). Two
fresh targeted captures match. The real capture starts the sheet at y=1574 while
the checked-in golden starts at y=1637, a 63 px / 24 dp shift at 420 dpi. The
other 30 states pass. Removing the manual `navigationBarsPadding()` did not
change the comparison and that production edit was reverted. The cause is
unresolved. Current screenshot is ignored at
`apps/android/app/build/ui-visual-regression/confirmation-light.png`; preserve
committed goldens and thresholds until the difference is understood and reviewed.
