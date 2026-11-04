# MyKadriTv

A plain-text client for browsing and playing titles from mykadri.tv on Android TV and phones: category tabs, genre
filter, search, series expand into seasons and episodes, and playback resumes where you stopped.

Pages are scraped, so a change to the site's markup can break listing or playback (see `Site.java`). If the local DNS
blocks the site, the app falls back to DNS-over-HTTPS.

## Build

Needs the Android SDK (platform 35) and JDK 17+. Create `local.properties` with your SDK path:

```
sdk.dir=C\:/path/to/Android/Sdk
```

Then `gradle assembleDebug`.
