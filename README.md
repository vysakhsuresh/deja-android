# Deja

**You've already seen it. Now you can find it.**

Deja reads the screenshots on your phone so you can search them in words instead of scrolling
back through months of them, and it clears out the ones you never needed to keep.

Everything happens on the device. Deja does not declare the `INTERNET` permission, so the process
cannot open a socket — your screenshots and the text read out of them cannot leave the phone even
if a dependency tried to send them. That is checkable on the Play Store listing without taking
anyone's word for it.

## Why there is no INTERNET permission

Screenshots are the most sensitive images on a phone: bank balances, private chats, IDs, one-time
codes. Any app that reads all of them is asking for a lot of trust, and a privacy policy is a
promise rather than a guarantee.

Withholding the permission turns the promise into something the operating system enforces. The
cost is real and accepted: **no analytics, no crash reporting, no remote config, no A/B tests.**
If something breaks in the field, we find out because someone tells us.

`app/src/main/AndroidManifest.xml` strips network access explicitly:

```xml
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />
```

Libraries declare permissions of their own and the manifest merger unions them into the app, so
these two lines remove network access no matter what any current or future dependency asks for.
**Never resolve a merger conflict by deleting them.** Verify the result after a build in
`app/build/outputs/logs/manifest-merger-*-report.txt`.

Two consequences that shape the code:

- **OCR uses ML Kit's bundled text recogniser**, not the unbundled Play-Services-backed one. The
  model ships inside the APK; the unbundled build downloads it at runtime and would need network.
- **Thumbnails load straight from MediaStore**, not through an image library. The usual Compose
  image loaders declare `INTERNET` in their own manifests, which would be merged in.

## What works today

| | |
|---|---|
| Indexing | Scans the Screenshots bucket via MediaStore, OCRs anything new, drops rows for screenshots deleted elsewhere. Runs in WorkManager so it survives the app closing. |
| Timeline | Screenshots grouped by day, filterable by category, with live indexing progress. |
| Search | Full-text search over the OCR'd text, ranked by how many of your terms actually hit. |
| Detail | The screenshot, what Deja pulled out of it (codes, amounts, booking refs, Wi-Fi passwords, dates, links), one tap to copy, and the raw text it read. |
| Clean up | Groups of screenshots that are safe to remove, with live totals. Deletion goes through `MediaStore.createTrashRequest`, so items land in the system trash and stay recoverable, and the user confirms in a dialog Deja cannot bypass. |
| Privacy | The permission story, what is indexed, and a way to wipe the index or force a re-read. |

### What is deliberately not built yet

- **Search is keyword search, not language understanding.** Typing *"the wifi password from the
  hotel"* drops the filler words and matches `wifi OR password OR hotel`, then ranks by how many
  terms hit. It is genuinely useful and it is not an on-device LLM. The search field says "Search
  your screenshots" rather than "Ask anything" for that reason.
- **Ask Deja and Collections** from the design are not implemented. Ask needs an on-device
  language model and is the intended paid tier.
- **Categories come from a keyword scorer**, not a model — see `Classifier.kt`. It is fast and
  explainable, and it is wrong sometimes, which is why nothing destructive keys off the category
  alone.
- **The fonts are the system face.** The design pairs Bricolage Grotesque with Instrument Sans;
  dropping those into `res/font` and pointing `Theme.kt` at them is the only change needed.

## Project layout

Single `:app` module. Unlike LayerLink there is no `:core` split, because nothing here is shared
with another app yet — that can happen when there is a second consumer, not before.

```
app/src/main/java/com/layerbit/deja/
  data/
    db/            Room entities, DAO, and the FTS4 index over the OCR text
    scan/          MediaStoreScanner - finds screenshots, and only screenshots
    ocr/           ScreenshotTextReader - ML Kit bundled Latin recogniser
    index/         IndexWorker (the scan/OCR loop), Classifier, EntityExtractor,
                   SearchQuery (query -> FTS MATCH), IndexingState (progress)
    ShotRepository Search ranking, cleanup grouping, trash requests
  ui/
    theme/         Palette and type from the design canvas
    components/    Bottom bar, headers, and the MediaStore-backed thumbnail loader
    timeline/ search/ detail/ cleanup/ privacy/ onboarding/
```

Screen designs and a clickable prototype live in the `deja-design/` folder of the
`layerlink-android` repository.

`store-assets/` holds the source logo and a 512×512 listing icon. The launcher icon itself is a
vector traced from that logo (`ic_launcher_foreground.xml`) rather than an exported bitmap, so it
stays sharp at every density and can supply the themed-icon layer.

## Requirements

- Android Studio with an SDK for **compileSdk 36**
- JDK 17
- `minSdk` is **30**. That floor buys `MediaStore.createTrashRequest`, so deleting a screenshot is
  recoverable rather than permanent; supporting older releases would mean
  `WRITE_EXTERNAL_STORAGE` and an irreversible delete.

## Building

```
./gradlew :app:assembleDebug
```

**This has not been compiled.** The sandbox it was written in has no Android SDK and no route to
`dl.google.com`, so Gradle could not resolve AGP, AndroidX, or ML Kit and no build was ever run.
The source was written and reviewed by hand against those APIs. Open it in Android Studio, sync,
and expect to fix whatever the first compile turns up.

Release builds read signing credentials from a git-ignored `keystore.properties` — copy
`keystore.properties.example` and fill it in. Without that file the release variant still builds,
unsigned, which is fine locally but cannot be uploaded to Play Console.
