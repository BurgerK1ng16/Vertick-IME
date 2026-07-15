# Contributing

Use JDK 17, Android SDK, and NDK 26.1. Keep credentials in the app's local
configuration screen, never in source, tests, fixtures, commits, or issues.

Before opening a pull request:

```powershell
gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

Test changes against at least one normal editor and one sensitive/password
field. Changes to Rime, Room, native JNI, cloud protocol, or privacy behavior
must include focused tests and update `THIRD_PARTY_NOTICES.md` or `PRIVACY.md`
when applicable.
