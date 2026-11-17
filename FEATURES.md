# Feature ideas

Ideas for Metro, worked out before any code. Nothing here is built yet unless it says so.

## The boundary: almost zero battery

Every feature has to fit inside these rules. They exist to force better ideas, not to be worked around.

| # | Rule |
|---|---|
| B1 | Nothing runs while the screen is off or Start is hidden: no alarms, jobs, services or wakelocks. |
| B2 | No new data sources (sensors, location, network) unless a feature clearly earns it. |
| B3 | Zero frames when nothing changes. Motion only when Start appears, state changes, or on touch. |
| B4 | A reaction is short: about 1.5 s at 12 fps, about 3 s of motion per visible minute. |
| B5 | A frame is cheap: only the tile redraws, with no blur, shaders or per-frame allocation. |
| B6 | Work happens on events (a notification, Start appearing), never per frame. Writes are batched. |
| B7 | Battery saver or reduced motion means still images only. |
| B8 | Small memory: about 1.5 MB of decoded images per feature. |

Charging lifts the limits: heavy work (indexing, precomputing) may run only while plugged in.

### Shortcuts that make "impossible" tiles cheap

- **Your looks are the timer:** take a reading each time Start appears, with no background polling.
- **Android already keeps the counts** (data used, steps, uptime): read and subtract.
- **Calculate instead of fetching:** sun, moon, tides, timetables.
- **Convert relative times to absolute once** ("arriving in 12 min" becomes 14:32), then count down for free.
- **Use what already passes through:** the notification listener, app launches, the media session.

## Delivery tile (next up)

One tile for everything on its way today: parcels (Amazon, Flipkart), food (Swiggy, Zomato) and rides
(Uber, Rapido). It's built entirely from the notifications these apps already post, so it needs no
accounts, no network and no new permission beyond the notification access Metro already asks for.
See the design in the conversation that created this file; it will move to its own note once settled.

## Shared tiles (parked)

Tiles shared live with family or friends: a shopping list, handwritten notes, "who's home", quick votes.

- **Transport:** Firebase Cloud Messaging, sending to each member's token, plus a small stateless relay
  (for example a Cloudflare Worker) that holds the sending key. No database: the phones hold all state.
  Don't use FCM device groups (deprecated, removed 2027-09-29) or server-side topic management.
- **Encryption:** on the phone, with a group key. The relay and Google see only scrambled data.
- **Merging:** each change is a new entry with a unique id; edits never overwrite each other.
- **Joining:** any member can invite. An invite (a QR code or link with a one-time secret) is the approval;
  the inviter's phone answers with the key, the member list and a snapshot.
- **Backup:** only the group id, stored inside the tile. After a restore the tile asks for an invite and
  joins as a new device. Keys, tokens and device identity are never exported.
- **Limits:** 4 KB per message, so handwriting, lists and status fit and photos don't. Needs Google Play services.
- **Fallback without a backend:** a shared Google Calendar as the transport, slower and limited to about 8 KB.

## On-device AI (optional extra)

Gemini Nano through ML Kit GenAI on recent Pixels and Galaxy flagships. Check availability at runtime and
never require it. Run it once per event or while charging, never continuously. Good uses: reading any
app's notification into structured data, a one-line "what you missed", tidying handwritten notes.
Handwriting recognition and reply suggestions (ML Kit) work on all phones.

## Other tile ideas

- **Battery forecast:** "lasts until 23:40", fitted from readings taken each time Start appears.
- **Screen time without permission:** from leaving and returning to Start.
- **Sky tile:** sun, moon and golden hour, calculated from the saved weather location.
- **Weather "now":** worked out from cached hourly data when you look.
- **Waiting for your reply:** who is waiting on you, from messaging notifications.
- **Spending today:** parsed from bank and payment notifications, on the phone.
- **Trips:** flights and hotels that Gmail adds to the calendar (needs checking on a device).

## Buddy (parked)

An animated character tile. Parked because it lacked a reason to keep it on screen over a normal tile.
The good parts (a pose worked out each time you come home, a "pocket" for later, gentle feedback on
phone use) can come back if a clear motive appears.
