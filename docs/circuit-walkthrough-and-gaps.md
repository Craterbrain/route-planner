# Circuit / Spoke: what the real app does, and where we fall short

Written 2026-10-31 against app version 0.8.1. Unlike `circuit-research.md` (built from help-center text only), this
comes from **looking at the app**: frames pulled from public tutorial videos, Spoke's own 36-second "4 steps" video,
the six official App Store screenshots, and the videos' spoken captions. The videos and images were used for analysis
only and are not kept in the repo.

## Sources

| What | Where |
|---|---|
| Screen recording, Android, full first-use flow (3:41) | YouTube "How To Use Circuit Route Planner" (Tutorial Wiz), id DuxuWqOqMPI |
| Screen recording, Android, optimize + load vehicle + stop card (2:04) | "How to Use Circuit Route Planner App (Complete Guide)" (ByteWise), id hwWvnA2Ucj0 |
| Screen recording, Android, sign-up and quickstart (2:13) | "Circuit Route Planner Tutorial For Beginners" (Ned TECH), id MLdc7Nvr3nM |
| Spoke's own "Optimize your Delivery Route in 4 Steps" (36 s) | id vQfUCc8i8nQ |
| Spoke Dispatch driver walkthrough, iPhone (5:29, low resolution, used for the captions) | id 8Ni-qCdHscc |
| Six official App Store screenshots + listing text | App Store id 1198232244 ("Spoke Route Planner", Circuit Routing Limited) |

Not useful: Spoke's "How to Save Your Time… | Pete the Courier Driver" (a talking head, no app screens).

## How it works, screen by screen (observed)

**First run.** Sign in (Google / Apple / email / phone). A "Quickstart: what best describes your job?" picker (package
delivery, order delivery, services, sales, none). A 15-second "How to use Circuit" intro. The location permission prompt.

**Empty route.** Map full screen with the blue current-location dot, a hamburger (☰) top-left that lists routes, and two
round map buttons on the right (layers/map type, recenter). At the bottom a sheet holds the search bar ("Tap to add stops")
with three icons in it: scan (viewfinder), microphone, ⋮. The sheet says "Add your first stops to start creating your
route" and has one wide **+ Add stops** button. Tapping it opens a full-height add screen with three big shortcuts:
**Map**, **Scan**, **Voice**.

**Adding stops.**
- Typing shows address autocomplete.
- **Voice** is a *list* mode ("Say your addresses. Say streets & numbers. Skip postcodes."): each spoken address
  appears as a row "Finding address… '128 Flatbush Ave'" with a play button, and the list fills while you keep talking.
  Cancel / Done at the top.
- Stops can also come from the clipboard, contacts, a pasted list, a spreadsheet import, or a scanned printed manifest.
- Picking an address opens the **stop editor** straight away with a green "Added" tag and a live status ("Pending").

**Stop editor** (one scrolling sheet; ETAs update as you change it):
Access instructions (a "+" link; a star saves them as the default for that address) · Add notes (+ a camera button to
photograph the package) · **Package finder** (opens a sheet: package *description* Small / Medium / Large and Box / Bag /
Letter; *place in vehicle* Front / Middle / Back, Left / Right, Floor / Shelf; shows as e.g. "Medium, BRS") · Packages
(− count +) · Order (First / Auto / Last) · Type (Delivery / Pickup) · Arrival time (Anytime / a window; "10am and Anytime"
means after 10) · Estimated time at stop (default 1 min; minutes and seconds) · Change address · Duplicate stop · Remove stop.
Older iOS builds show Priority (ASAP / Normal) and Time window (earliest / latest) instead of Order and Arrival time.

**Planning list.** The sheet's header shows "1hr 6m · 1 stop · 53.9 km" and an editable route name ("My first route"). Then:
- an orange banner "Break automatically skipped ›" (breaks are planned from here; if one can't fit you get "Break not
  added: Include anyway / Remove break");
- rows for **Start location** ("Used GPS position when optimizing" / "Start from current location"), **Round trip**
  ("Return to start location") and **No break** ("Tap to plan a break");
- the stops as a **timeline**: a vertical line, the ETA at the left (or right on iOS), a stop number or ID ("01"), the
  address in two lines, then a row of **chips**: size ("Medium"), vehicle code ("BRS"), time at stop ("5 min"), type
  ("Pickup"), package count; a purple tag marks a pickup;
- a big **Optimize route** button; while it runs a modal says "Optimizing route: Finding the best order…" with a progress bar.

After optimizing, the bottom bar shows the total time at the left and **Refine** and **Confirm**. Refine offers Reverse
route and Manually order route (draw loops on the map). **Confirm** locks the order and the stop IDs. A dialog then asks
"Ready to load your vehicle? Spoke can help you load your vehicle so your parcels are first in, last out." (Continue / Skip).

**Editing a confirmed route.** Changes collect in an **Apply changes** sheet with two choices: *Update route* (reorder
only changed stops) and *Reoptimize route* (reorder all stops for optimal efficiency).

**Map.** After optimizing, numbered teardrop markers (1, 2, 3…) joined by a **blue line along the real roads**, a flag for
the finish, the blue dot for you. Marker styles show pickup, priority, delivered, failed, new/edited; zoomed in, a marker
shows its address and ETA.

**Before driving.** Buttons **Share live route** and **Load vehicle** sit above the list; the bottom bar becomes the total
time, **Edit** and **Start route**.

**Driving.** *Start route* focuses the first stop. A card shows the title, "3/12 · 9:44 AM" with a colored dot (blue = on
time, orange = tight, purple = pickup), then three buttons: **Navigate** (primary), **Failed**, **Delivered** (or *Picked up*
for a pickup). Rows below: notes, the town, "Medium and Back Right Shelf", **ID 1 "Originally 1st"**, time at stop, type,
packages, then Edit stop / Duplicate stop / Remove stop. Swiping the card moves to the previous or next stop; after
Delivered it advances by itself. **Navigate** runs **in-app turn-by-turn** on Google Maps: a dark turn banner ("0.36 mi
Castleton Avenue"), a flag with the arrival time, speed, settings / layers / recenter buttons, live **traffic** colouring
and a "Report" button, and a bottom bar with minutes remaining, arrival time, distance, mute and a list button. The stop
card stays above it with just the failed and delivered buttons. Navigation can instead be handed to Google Maps, Waze or
Apple Maps.

**Delivered / Failed (Spoke Dispatch).** A green check marks delivered; **Other** adds up to 10 photos, a *note for
recipient* and a *note for internal use*, then Done and Submit. At the end a **Route completed** button.
Failed reasons: Recipient not home, Payment not received, Can't find address, No parking, No time, Other.

**Finish.** A card "Route completed! 2:35 PM. You saved 1.4 hours and 22 miles".

**Sharing.** "Transfer stops": show a **QR code** on one phone and **scan to receive stops** on the other, or Copy link /
Share / Save. "Share live route" sends a link that shows the driver's progress.

**Other listed abilities (App Store text):** keypad, voice or Live Text entry; unlimited stops (free tier is capped);
last-minute changes during the day; use with Waze / Google Maps / Apple Maps; windows and priority; time at each stop and
rest breaks; instant ETAs; package details "to make loading your truck easier"; traffic-aware routing.

## Where we are and where we fall short

Legend: **have** = in the app (0.8.1, unverified on a phone) · **partial** · **missing**.

### Adding stops
| Circuit | Us |
|---|---|
| Typed autocomplete | **have** (Google, offline pack, phone, OSM) |
| Voice, one address | **have** |
| **Voice list mode** (keep talking, rows fill in) | **missing** |
| Paste a list / clipboard of addresses | **missing** |
| Contacts as stops | **missing** |
| Scan an address / label | **have**, and better: ranks delivery vs return address, validates, saves barcodes |
| **Scan a printed manifest** (many addresses at once) | **partial**: the label parser already finds several addresses; no "add all" screen |
| CSV / spreadsheet import | **have** (and phone-to-phone share) |
| Long-press the map | **have** |
| Add screen with Map / Scan / Voice shortcuts | **missing** (we have the search bar's two icons) |

### Stop editor
| Circuit | Us |
|---|---|
| Order First / Auto / Last | **have** |
| Arrival window, time at stop | **have** |
| Notes | **have** |
| **Access instructions saved per address** (separate from notes, star = default) | **missing** (notes only; drop-off pin is remembered, text is not) |
| **Type: Pickup vs Delivery** (Picked up button, purple, different counting) | **missing** |
| **Package description** (Small/Medium/Large × Box/Bag/Letter) | **missing** |
| Place in vehicle (Front/Middle/Back, L/R, Floor/Shelf) | **have** (free text as well) |
| Package count with − and + | **partial**: add one, can't remove one |
| Photo of the package on the stop | **partial**: proof photo only, after delivery |
| **Change address** of an existing stop | **missing** |
| **Duplicate stop** | **missing** |
| Remove stop, Make next, Move to position | **have** |
| Bulk edit (select many: type, order, window, time) | **missing** |
| Stop IDs | **have** (and fixed, never reused); Circuit also shows "Originally 1st"; we don't |

### Planning and optimizing
| Circuit | Us |
|---|---|
| Optimize, reverse, update vs reoptimize | **have** |
| Start and round trip rows in the list | **partial**: the start row exists and says how the route ends; the end is not its own row |
| **Breaks** (plan one; "automatically skipped" when it can't fit) | **missing** |
| **Manual ordering by drawing on the map** | **missing** |
| **Confirm route** (locks order and IDs) | **missing** (IDs are already fixed on entry; there is no confirm step) |
| Progress modal while optimizing | **have** (a busy dialog) |
| **Traffic-aware ETAs** | **missing** (no live traffic; on-device road speeds only) |
| Route name and date; copy stops from a past route | **partial**: we have Save / Open; no date, no "reuse stops with statuses cleared" |

### Map
| Circuit | Us |
|---|---|
| Numbered markers | **have** |
| **Blue route line along the real roads** | **missing**: ours is a dashed straight line between stops. On-device Valhalla can return the road geometry |
| Marker styles for pickup / priority / edited | **partial**: current, done, failed only |
| ETA / address next to markers | **have** (ETA, street for the current stop) |
| **Blue dot for your position and a recenter button** | **missing** |
| Map layers (satellite, traffic) | **missing** (no free satellite/traffic source) |
| Offline map | **have** (Circuit has none) |

### Driving
| Circuit | Us |
|---|---|
| Next-stop card with Navigate / Failed / Delivered | **have** |
| Auto-advance after Delivered, prev/next | **have** |
| Hand off to Google Maps / Waze | **have** |
| **In-app turn-by-turn with live traffic** | **missing**. We hand off, and add a floating button over the other app, which Circuit does not have |
| Failed reasons | **have** (same list plus Access code needed) |
| **Delivered-to options** (recipient, third party, mailbox, safe place, pickup point, other) | **missing** |
| **Several photos, a note for the recipient, a separate internal note** | **partial**: one photo, one note |
| Signature | **have** |
| **Load vehicle** (first in, last out; package finder codes) | **missing** (we store the location per package but have no loading mode) |
| Check a parcel in your hand | **have** (scan a barcode against the route), which Circuit doesn't do in what I saw |
| **"You saved 1.4 hours and 22 miles"** finish card | **missing** (we show counts, not the saving) |

### Sharing and the rest
| Circuit | Us |
|---|---|
| **QR "Transfer stops"** (scan to receive) | **missing**. We share a CSV file through the share sheet. A QR only fits a short route (a QR holds ~3 KB) |
| Share live route (a link customers/dispatch can follow) | **missing**, and needs a server |
| Android Auto / CarPlay | **missing** |
| Battery saver mode, map detail setting | **missing** |
| Quickstart job type, intro, account sign-in | **missing**, and the account part is deliberately absent |
| Route Planner works offline end to end | **have** (Circuit needs a connection) |

## Suggested order, by value for a courier and cost

1. **Road-following route line** (Valhalla `route` on-device; draws the real path and gives true leg distances). Biggest visual gap, no new dependency.
2. **Your position on the map + recenter button.** Small, and expected.
3. **Load vehicle mode** (loading order = reverse of the route; scan each parcel to tick it off; suggest a spot). Uses features we already have (package zones, barcodes).
4. **Pickup type**, **package size/type**, **packages − button**, **duplicate stop**, **change address**: the stop-editor gaps, all small.
5. **Access instructions saved per address** (like the remembered drop-off pin).
6. **Voice list mode** and **paste a list of addresses**: fast bulk entry.
7. **Delivered-to options, extra photos, recipient vs internal note.**
8. **Finish card with time and distance saved** (compare against the order the stops were added in).
9. **Breaks** in the solver.
10. Bulk edit; scan-a-manifest "add all"; manual order by drawing; Confirm step.

Not recommended for now: in-app turn-by-turn (a large build; the hand-off plus floating button works), live traffic and
live-share links (need servers or paid data), satellite layer, Android Auto, accounts.
