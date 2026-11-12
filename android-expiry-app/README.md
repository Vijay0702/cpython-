# ExpiryMate for Android

The Android version of ExpiryMate: scan a grocery item's barcode and printed
expiry date, then get a daily summary and hourly reminders on the day before
it expires. See `../ios-expiry-app/README.md` for the iPhone version.

## Installing

Every push builds `ExpiryMate.apk` in GitHub Actions (workflow "ExpiryMate
Android build"). Download it from the run's Artifacts, unzip, copy the `.apk`
to the phone and open it. Android asks once to allow installing apps from that
source. It is a test build signed with the key in `app/debug.keystore`, so
later builds install over earlier ones and keep your saved items.

## Building locally

Requires JDK 17 and the Android SDK.

    ./gradlew testDebugUnitTest assembleDebug

## Differences from the iPhone version

- Reminders use one alarm for the next reminder time, re-armed each time it
  fires and after a restart. Android may deliver it a few minutes late when
  the phone is idle.
- Items are marked used or thrown away with buttons on each row instead of a swipe.
