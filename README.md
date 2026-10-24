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
| Indexing | Scans the Screenshots bucket via MediaStore, OCRs anything new, drops rows for screenshots deleted elsewhere. Reads several screenshots at once, writes in batches, keeps the **line geometry** alongside the text, and re-reads anything an older engine version produced — in the background, without the index ever going dark. Runs in WorkManager so it survives the app closing, and can be stopped and resumed. |
| Timeline | Screenshots grouped by day, filterable by category **and by the app they came from**, with live scan progress and a stop control. Long-press anything to select, then keep, share or trash the lot in one go. Kept screenshots get a shelf along the top. |
| Search | Everything-must-match first, anything-matches as a fallback, ranked by **where** a word appears rather than only how often. Understands quoted `"phrases"`, `-exclusions`, spelling variants, and **time** — "receipt last week", "tickets in june", "2024". Matched words are picked out in each result. Remembers the last eight searches that found something. |
| Detail | Swipes sideways through whatever list you opened it from, double-taps to zoom, and turns what Deja pulled out into something you can act on — call a number, message it, save it as a contact, open a link, or find every other screenshot that mentions the same value. ID and card numbers are masked until tapped. The OCR text is selectable. |
| Keep | Star a screenshot and it is excluded from every automatic Clean up group, kept as the surviving copy when duplicates are collapsed, filterable as its own bucket, and one tap from the top of the timeline. |
| Clean up | Seven automatic groups plus your own picks, each expandable to choose individual screenshots rather than all-or-nothing. Two ways to remove them: `MediaStore.createTrashRequest` (recoverable from the gallery's trash) or `MediaStore.createDeleteRequest` (gone immediately, no undo) — both confirmed in a system dialog Deja cannot bypass. |
| Privacy | The permission story, what is indexed, and guarded controls to stop a scan, re-read, or wipe the index. |
| Browse | Every category and every app as a readable vertical list with counts. The timeline's chip row only ever shows what fits, and the list grows as a scan runs, so this is where the whole thing lives. Picking anything drops you back on the timeline already filtered. |
| About | What the library looks like in aggregate, plus support and feedback links. |

## The engine

### Why keeping the line geometry changes everything

ML Kit returns blocks, lines, bounding boxes and font sizes. The first engine kept `result.text`
and threw the rest away, which cost more than it sounds like:

- A receipt reading "₹49 delivery · ₹32 taxes · ₹450 total" reported **₹49**, because that was
  the first currency match in the string. With line boxes, the amount next to the word "total" —
  or simply the one set in the biggest type, which is how every payment app renders it — wins.
- "Verification code" on one line and `481902` on the next is the normal phone layout, and a
  flat-text regex cannot tell "the next line" from "somewhere further down the page".
- Every screenshot has a clock in the status bar, and it is never the time anyone wants. A line
  box says which one it is; a flat string does not.
- ML Kit orders blocks the way its model found them, which on a two-column layout interleaves the
  columns. Lines are now sorted top-to-bottom, left-to-right before being joined, so every
  label-then-value pattern reads the page the way a person does.

### The headline

Near the top of almost every screenshot is an app bar naming the app or the screen: "Payments",
"Boarding pass", "WhatsApp". It is the shortest, most deliberate, most informative string on the
image and it was being discarded. It now carries its own weight in classification, and it is the
**only app signal that exists at all on Pixel and stock builds**, which write nothing but a
timestamp into the filename — so the Apps list in Browse stops being empty for those users.

### Categorisation

Four signals, weighted rather than chained (`Classifier.kt`):

1. **The app**, resolved from the filename — `Screenshot_..._WhatsApp.jpg` on Samsung and
   OnePlus, `..._com.whatsapp.jpg` on Xiaomi and Realme — or, when the filename says nothing,
   from the app bar in the picture.
2. **What was extracted** — a PAN number means an ID document regardless of what else the OCR
   picked up; a PNR or a flight number means travel.
3. **The headline**, at nearly twice the weight of body text. "Total" in the middle of a chat is
   noise; "Total" as the title of the screen is a receipt.
4. **Words in the body**, split into strong terms that mean one thing and ordinary ones that
   only count alongside others.

The first version demanded two keyword hits and dropped everything else into "Everything else",
which is where most of a real library ended up. Scoring is what fixes that.

### Engine versions, or: why improvements now reach screenshots you already have

Everything above used to be computed once, inside the OCR loop, and frozen. A better classifier
did precisely nothing for the four thousand screenshots already indexed.

The work is now split by what it costs. Deciding what text *means* is cheap and will change
often; reading an image is expensive and rare. Each row records the version of each stage that
produced it (`EngineVersion.kt`), and the worker brings stale rows forward in three passes:

| Pass | Triggered by | Cost |
|---|---|---|
| Re-derive | a change to the classifier, extractor, app detection or search blob | database only — a few thousand rows a second, no file I/O |
| Read new | screenshots not in the index | OCR |
| Backfill | a change to what OCR itself captures | OCR, in the background, capped per run |

So shipping a smarter classifier is now a one-line version bump that reaches the whole library
before the progress bar finishes drawing. A change to OCR schedules a background re-read that
leaves the index **completely usable the whole time** — searchable, filterable, pins intact; the
old text keeps answering until better text replaces it. Nothing is ever emptied.

The scan is marked finished after the second pass, never the third. A backfill is housekeeping:
it must not be the reason anyone is asked "resume the unfinished scan?" on launch.

### Search

FTS gives recall and nothing else useful — FTS4 has no BM25 and no notion of one column
mattering more than another. So matching and ranking are separate jobs:

- **Strict first.** Every word typed must be present. "swiggy receipt" means the screenshot that
  is both, not the union of two piles. Only when that comes back thin is the net widened.
- **Where beats how often.** A term in the app name or the screen title outranks the same term
  buried in body text, and matching *everything* gets a large bonus of its own.
- **Time is a first-class term.** "receipt last week", "tickets in june", "otp yesterday",
  "2024". The date phrase is parsed into a range, removed from the search words, and shown back
  to you in the result line. A query that is *only* a date works on its own.
- **Phrases and exclusions.** `"boarding pass"` must appear intact; `-whatsapp` must not appear
  at all.
- **Spelling variants** widen the loose pass only, so they can surface a result but never
  outrank a direct hit. This is also how `wifi` finally matches `Wi-Fi`: FTS tokenises that as
  `wi` + `fi`, so the expansion includes the phrase `"wi fi"` rather than a `wi*` prefix that
  would match "with", "will" and "window".
- **Recency breaks ties and nothing more** — never enough to push an exact match below a vague
  one, which is what pure date ordering did.

### Finding the duplicate that actually exists

Exact matching on text and byte size only catches a file copied verbatim. What people accumulate
is the same screen captured twice seconds apart, or one image re-saved through a share sheet:
identical to look at, different in every byte.

Each screenshot now carries a 64-bit difference hash, computed from the bitmap the reader already
decoded, so it costs no extra I/O. Two screenshots are offered as near-identical only when the
**images** look alike *and* the **text** substantially agrees — pages of dark text on white are
most of a screenshot library and can land close together in dHash space without being related,
so a false positive needs two unlikely things at once. The copy kept is the pinned one, then the
largest, then the newest.

### What is deliberately not built yet

- **Search is keyword search, not language understanding.** It understands phrases, exclusions,
  spelling variants and dates, and it ranks properly. It does not understand meaning: *"the thing
  I screenshotted about the car"* will not work unless the word "car" is on the screen. That is a
  real ceiling and it is not an on-device LLM. The search field says "Search your screenshots"
  rather than "Ask anything" for that reason.
- **Latin script only.** A Hindi, Marathi or Tamil screenshot indexes as noise. ML Kit ships a
  bundled Devanagari recogniser that would fix it offline, at the cost of APK size — a decision
  about what the app weighs, not a technical obstacle.
- **No barcode or QR decoding.** Boarding passes, Wi-Fi QR codes and UPI QR codes carry a
  machine-readable payload that is thrown away today. ML Kit's barcode scanner is bundled and
  works offline; this is the same size trade as above. The engine-version machinery exists partly
  so adding it later is a `READ` bump rather than a migration.
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

### What is stored on the device

The index, and the last eight searches that found something. Both live in Deja's private
storage, neither can leave the phone, and **"Delete everything Deja knows" on the privacy screen
clears both** — a control that left search history behind would be a lie.

### Schema changes keep the index

Starring a screenshot takes it out of every automatic Clean up group, makes it the copy that
survives when duplicates are collapsed, gives it its own filter, and puts it on a shelf at the
top of the timeline. It is the thing that makes a bulk-delete screen safe enough to use without
reading every tile first.

Pinning also ended the destructive-migration shortcut. Earlier schema changes dropped the index
and re-read everything, on the argument that an index is derived data. That argument holds right
up until a column records a decision: pinning is something the user did, and no amount of
re-reading screenshots recovers it. **v2 → v3 and v3 → v4 both ship real `Migration`s** and the
index survives. Every schema change from here on does the same.

The FTS table needs nothing either time. Its triggers fire on the `shots` table and copy
`searchBlob` across; adding a column beside it leaves both the column and the triggers intact.

## Project layout

Single `:app` module. Unlike LayerLink there is no `:core` split, because nothing here is shared
with another app yet — that can happen when there is a second consumer, not before.

```
app/src/main/java/com/layerbit/deja/
  data/
    db/            Room entities, DAO, and the FTS4 index over the OCR text
    scan/          MediaStoreScanner - finds screenshots, and only screenshots
    ocr/           ScreenshotTextReader - ML Kit bundled Latin recogniser, bounded decode,
                   line geometry and a perceptual hash out of one pass
    index/         IndexWorker (three-pass scan), EngineVersion (what is stale and why),
                   Derivation (the pure text -> meaning step), Classifier, EntityExtractor,
                   TextLayout (reading order, headline, geometry codec), PerceptualHash,
                   SearchQuery + DateHints (query -> plan), Ranker, IndexingState
    ShotRepository Search, cleanup grouping, near-duplicates, trash requests
  ui/
    theme/         Palette and type from the design canvas
    components/    Bottom bar, headers, Motion (press/haptics/counters/shimmer), and the
                   MediaStore-backed thumbnail loader
    detail/        DetailScreen, EntityActions (what a found value can do), DetailContext
    timeline/ search/ browse/ cleanup/ privacy/ about/ onboarding/
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
