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

- **Every ML Kit model is the bundled build**, never the unbundled Play-Services-backed one —
  Latin text, Devanagari text and barcode scanning alike. The weights ship inside the APK; the
  unbundled builds download them at runtime and would need network. That is the single largest
  thing in the APK and it is the price of the guarantee.
- **Thumbnails load straight from MediaStore**, not through an image library. The usual Compose
  image loaders declare `INTERNET` in their own manifests, which would be merged in.

## What works today

| | |
|---|---|
| Indexing | Scans the Screenshots bucket via MediaStore, reads anything new in **Latin or Devanagari** and decodes any **QR or barcode** in it, drops rows for screenshots deleted elsewhere. Reads several screenshots at once, writes in batches, keeps the **line geometry** alongside the text, and re-reads anything an older engine version produced — in the background, without the index ever going dark. Runs in WorkManager so it survives the app closing, and can be stopped and resumed. |
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
| Re-derive | a change to the classifier, extractor, app detection, **code parser** or search blob | database only — a few thousand rows a second, no file I/O |
| Read new | screenshots not in the index | OCR |
| Backfill | a change to what reading itself captures — a new script, barcode decoding | OCR, in the background, capped per run |

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

### Reading two scripts without paying for two

A Latin recogniser handed Devanagari cannot return Devanagari — its output alphabet is Latin, so
it returns the nearest Latin shapes it can find, and दिल्ली comes back as something like
"fereft". That junk used to be stored as if it were text, which did three quiet kinds of damage:
it poisoned the search index with tokens matching nothing, it randomised the duplicate hash so
two identical screenshots stopped matching, and it filled the "text Deja read" panel with
nonsense — which makes the app look broken rather than honestly limited.

So `TextQuality` measures whether what came back reads like language: Latin tokens that are
mixed-case mid-word, consonant runs, and stray single letters are what shape-matching produces.
The same measurement does two jobs at two thresholds, and the difference matters because the two
decisions have completely different costs:

| | Threshold | If wrong |
|---|---|---|
| **Router** — try the Devanagari model too | 0.45 | one extra OCR pass; the better result wins anyway |
| **Filter** — store nothing rather than junk | 0.75 **and** almost no plausible text | real text is lost |

An English-only library never crosses the first threshold, so it **never pays for the second
model at all** — and the Devanagari recogniser is built lazily, so on those devices it is never
even loaded. A mixed Hindi/English page does cross it, and because the Devanagari model reads
Latin as well, it comes back whole rather than half-read.

> **`\p{M}` is load-bearing.** Devanagari vowel signs and the virama are Unicode category *Mark*,
> not *Letter*, so a word-splitting class of `[^\p{L}\p{N}]+` cuts दिल्ली into द, ल, ल — three
> meaningless single consonants. That shreds Hindi search terms, breaks the near-duplicate text
> comparison, and makes the plausibility check condemn every correctly-read Devanagari page as
> garbage. Every splitter in the codebase includes `\p{M}`. Do not remove it.

### Codes a camera can read and OCR cannot

A QR code has no text in it, so OCR has never been able to touch one — and Wi-Fi QRs, UPI QRs and
boarding passes are among the most deliberately-kept screenshots there are.

| Payload | What comes out |
|---|---|
| `WIFI:T:WPA;S:…;P:…;;` | network name, and the password as a **sensitive** value — masked until tapped, never in the search index |
| `upi://pay?pa=…&am=…` | payee UPI ID and the exact amount |
| IATA BCBP (PDF417/Aztec) | booking reference, flight number, both airports |
| vCard, `mailto:`, `tel:`, `SMSTO:` | phone, email, link |
| `http(s)://` | link |

Payloads are stored **raw**; parsing happens in `CodeExtractor` at derive time. So sharpening the
boarding-pass reader or adding a payload format later is a one-second `DERIVE` bump, not a
re-read of every image on the phone. Where a code and the OCR text disagree about a value, the
code wins — it was encoded deliberately, the text is a guess at some pixels.

A screenshot that is *only* a QR code now works end to end: no readable text, but the Wi-Fi
password is extracted, the category comes out right from the entity signal, and tapping copies
it.

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
- **Latin and Devanagari only.** Devanagari covers Hindi, Marathi, Nepali, Sanskrit and Konkani.
  Tamil, Telugu, Bengali, Kannada, Malayalam, Gujarati, Urdu, Chinese, Japanese and Korean are
  not read — ML Kit ships bundled models for the last three and nothing for the Indic ones
  beyond Devanagari. A screenshot in an unread script is recorded as unreadable rather than
  filled with junk, and the detail screen says so.
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
re-reading screenshots recovers it. **v2 → v3, v3 → v4 and v4 → v5 all ship real `Migration`s**
and the index survives every one. Every schema change from here on does the same.

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
    ocr/           ScreenshotTextReader - bundled Latin + Devanagari recognisers and a
                   barcode scanner over one bounded decode, plus line geometry and a
                   perceptual hash
    index/         IndexWorker (three-pass scan), EngineVersion (what is stale and why),
                   Derivation (the pure text -> meaning step), Classifier, EntityExtractor,
                   TextLayout (reading order, headline, geometry codec), PerceptualHash,
                   TextQuality (is this language or shape-matching?), CodeExtractor
                   (QR/barcode payloads -> entities), SearchQuery + DateHints, Ranker,
                   IndexingState
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
