# ExpiryMate

An iPhone app that tracks grocery expiry dates.

1. Scan the product's barcode. The name is looked up in the free
   [Open Food Facts](https://world.openfoodfacts.org) database.
2. Point the camera at the printed expiry date. It is read on the device.
3. Confirm, correct if needed, and save.

## Reminders

- **Every day**: one summary notification at a time you choose (default 9:00)
  listing the items closest to expiry.
- **The day before an item expires**: a reminder every hour (default 8:00 to
  21:00) until you mark the item as *Used* or *Thrown away*, either in the app
  or straight from the notification. *Snooze* pauses that item for 3 hours.

iOS allows an app 64 pending notifications, so the app schedules the soonest 60
and rebuilds the schedule every time it is opened or an item changes. If many
items expire on the same day and the app is not opened, the later hourly
reminders for that day may not be scheduled.

## Running it

Requires a Mac with Xcode 16 or later, and iOS 17 or later.

1. Open `ExpiryMate.xcodeproj`.
2. Select the ExpiryMate target, open *Signing & Capabilities*, and pick your team.
3. Run on a real iPhone. The camera scanner does not work in the simulator;
   there the app falls back to typing the name and picking the date.

## Layout

| Path | Purpose |
| --- | --- |
| `ExpiryMate/Models/GroceryItem.swift` | Stored item (SwiftData) |
| `ExpiryMate/Services/ExpiryDateParser.swift` | Finds the expiry date in scanned text |
| `ExpiryMate/Services/ReminderPlanner.swift` | Decides which notifications should exist |
| `ExpiryMate/Services/ReminderScheduler.swift` | Schedules them and handles notification buttons |
| `ExpiryMate/Services/ProductLookup.swift` | Barcode to product name |
| `ExpiryMate/Views/` | List, scan flow, scanner, settings |
