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
| Indexing | Scans the Screenshots bucket via MediaStore, OCRs anything new, drops rows for screenshots deleted elsewhere. Runs in WorkManager so it survives the app closing, and can be stopped and resumed. |
| Timeline | Screenshots grouped by day, filterable by category **and by the app they came from**, with live scan progress and a stop control. |
| Search | Full-text search over the OCR'd text, the app name, the category and the non-sensitive extracted values — ranked by how many of your terms actually hit. |
| Detail | The screenshot, what Deja pulled out of it, one tap to copy, share and open. ID and card numbers are masked until tapped. |
| Clean up | Six groups of screenshots that are safe to remove, with live totals, select-all, and a re-scan. Deletion goes through `MediaStore.createTrashRequest`, so items land in the system trash and stay recoverable, and the user confirms in a dialog Deja cannot bypass. |
| Privacy | The permission story, what is indexed, and guarded controls to stop a scan, re-read, or wipe the index. |
| About | What the library looks like in aggregate, plus support and feedback links. |

### Categorisation

Three signals, weighted rather than chained (`Classifier.kt`):

1. **The app the screenshot came from**, read off the filename — `Screenshot_..._WhatsApp.jpg` on
   Samsung and OnePlus, `..._com.whatsapp.jpg` on Xiaomi and Realme. The strongest signal
   available offline and free to obtain. Pixel and stock builds write no app name, so it can never
   decide alone.
2. **What was extracted** — a PAN number means an ID document regardless of what else the OCR
   picked up; a PNR means travel.
3. **Words in the text**, split into strong terms that mean one thing and ordinary ones that only
   count alongside others.

The first version demanded two keyword hits and dropped everything else into "Everything else",
which is where most of a real library ended up. Scoring is what fixes that.

### What is deliberately not built yet

- **Search is keyword search, not language understanding.** Typing *"the wifi password from the
  hotel"* drops the filler words and matches `wifi OR password OR hotel`, then ranks by how many
  terms hit. It is genuinely useful and it is not an on-device LLM. The search field says "Search
  your screenshots" rather than "Ask anything" for that reason.
- **Ask Deja and Collections** from the design are not implemented. Ask needs an on-device
  language model and is the intended paid tier.
- **Names of people are not extracted.** Phone numbers, emails and handles are, and they are what
  drives the People & contacts bucket; picking human names out of arbitrary OCR text needs a model
  that would have to be downloaded.
- **Sensitive values are stored in the index in the clear** but kept out of the search blob and
  masked in the UI until tapped. The database is app-private and Deja has no network, so the
  threat being addressed is a glance over your shoulder, not exfiltration.

### Scanning is incremental

A full read is a **first-run cost, not a per-launch one**. Two things keep it that way:

- The worker diffs MediaStore against the index and only OCRs screenshots it has never seen.
- Before even starting a worker, Deja compares MediaStore's version counter
  (`MediaStore.getGeneration`) with the one recorded at the last completed scan. Unchanged means
  nothing on the device has been added, edited or deleted, so the scan is skipped entirely and no
  progress UI appears at all.

The counter covers the whole media volume rather than just screenshots, so an unrelated photo also
moves it. That costs one MediaStore query that finds nothing new; it never causes a re-read.

Wiping the index or re-reading on purpose clears the recorded counter, so those still work.

### Permissions, including the refusals

Android stops showing the permission dialog once someone has declined twice, so an app that only
calls `launch()` becomes permanently useless with no way back short of reinstalling.
`MediaPermission` distinguishes three states — never asked, refused but askable again, and refused
for good — and the first screen changes accordingly, sending the user to the system settings page
when that is the only thing left that works. The permission is re-read on every resume, so
granting it in Settings takes effect straight away.

Android 14's "Select photos" is treated as a supported state rather than a broken one: Deja
declares `READ_MEDIA_VISUAL_USER_SELECTED`, indexes whatever was picked, and offers a way to pick
more from both the timeline and the privacy screen.

### Upgrading from an earlier build

The schema changed (source app, search blob), and the database is set to
`fallbackToDestructiveMigration`. **The existing index is dropped on first launch and every
screenshot is read again.** That is deliberate — the index is derived data that can always be
rebuilt from the screenshots themselves, so it is cheaper than shipping migrations for it.

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
