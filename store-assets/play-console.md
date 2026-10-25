# Play Console answers for Deja

Copy these into Play Console. Each answer is checked against the code at the commit that
added this file; re-check if permissions or dependencies change.

## 1. Privacy policy

URL: `https://layerbit.co.in/deja/privacy`

Upload `store-assets/privacy.html` to that path (source text: `privacy-policy.md`). Play
rejects the listing if the URL does not load, so publish it **before** submitting.

## 2. Data safety form

**Data collection and security**

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |

Choosing "No" ends the form; Play then shows "No data collected" and "No data shared with
third parties" on the listing. That is correct for Deja because, in Play's definitions:

- *Collected* means transmitted off the device. Deja cannot transmit anything — the merged
  manifest has no `INTERNET` permission (verify in
  `app/build/outputs/logs/manifest-merger-release-report.txt`).
- Data processed only on the device (the OCR index, search history, settings, the local
  crash log) is explicitly **not** collection.
- Messages the user chooses to send through another app ("Get help", "Report a problem" —
  their own email or WhatsApp app) are user-initiated sharing through that app, which Play
  does not count as collection by Deja.

If Play asks the follow-ups anyway:

| Question | Answer |
|---|---|
| Is all of the user data collected by your app encrypted in transit? | Not applicable — nothing is transmitted |
| Do you provide a way for users to request that their data is deleted? | Yes — Privacy → "Delete everything Deja knows" (also: uninstall) |

## 3. Photo and video permissions declaration

Deja declares `READ_MEDIA_IMAGES`, so Play requires this declaration. It is a common
rejection — use this text.

**Core functionality that requires broad access to photos:**

> Deja is a screenshot search and clean-up app. Its core feature is reading the text in
> every screenshot on the device, on-device and offline, so the user can search their whole
> screenshot library by what it says, and find duplicates and near-duplicates across all of
> it to remove them. Both depend on the complete set of screenshots and on noticing new ones
> as they are taken: a search over only the images picked in a one-time photo picker cannot
> find a screenshot the user has forgotten about, which is the entire purpose of the app,
> and duplicate detection needs both copies.
>
> Deja reads only the Screenshots folder (MediaStore query scoped by RELATIVE_PATH /
> BUCKET_DISPLAY_NAME), never the rest of the photo library. It declares no INTERNET
> permission, so nothing it reads can leave the device.
>
> Deja also supports Android 14's partial access as a first-class mode: it declares
> READ_MEDIA_VISUAL_USER_SELECTED, indexes exactly the photos the user selected, shows that
> it is in partial mode, and offers "Select more" on the timeline and the Privacy screen.

**Video (Play asks for a short screen recording):**

Record on the phone (Quick Settings → Screen record), about 30 seconds:

1. Fresh install → Deja's first screen explains the permission → tap Allow access.
2. The system permission dialog appears — show the "Allow all" / "Select photos" choice.
3. Choose Allow all → the timeline fills as screenshots are read (progress banner visible).
4. Type a search ("receipt") → results with highlighted words.
5. Optional: Clean up → the duplicate group, to show it spans the library.

Upload it unlisted to YouTube or as a Drive link, per the form's instructions.

## 4. Other declarations

| Section | Answer |
|---|---|
| Ads | No, the app does not contain ads |
| App access | All functionality is available without special access (no login) |
| Content rating | Utility / productivity; no user-generated content shared, no violence etc. → expected rating: Everyone / 3+ |
| Target audience | 18+ (not designed for children; avoids the Families policy requirements) |
| News app | No |
| Government app | No |
| Financial features | None (Deja reads amounts and UPI IDs from the user's own screenshots but offers no financial service) |
| Health | No |
| Foreground service permissions | None declared by Deja. WorkManager's merged `FOREGROUND_SERVICE` has no typed `FOREGROUND_SERVICE_*` permission with it, so no FGS declaration should be requested |
| Notifications | `POST_NOTIFICATIONS` — requested only when the user turns on new-screenshot notifications |

## 5. Release

- Upload `app/build/outputs/bundle/release/app-release.aab` (App Bundle, not APK).
- Enrol in Play App Signing (default for new apps). The key in `keystore.properties` is then
  only the **upload** key — if it is ever lost, Play support can reset it.
- Release notes (≤500 chars):

```
First release. Search every screenshot by what it says — English and Hindi, QR codes
and boarding passes included — with no internet permission at all. Copy codes, call
numbers, open links, clean up duplicates safely, and get an optional notification
with the code from a screenshot you just took.
```
