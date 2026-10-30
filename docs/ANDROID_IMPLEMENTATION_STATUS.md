# Android implementation status

## Included in this repository
- The EMBER HTML interface at `www/index.html`.
- Capacitor Android packaging configuration.
- Android landscape orientation and immersive display patch.
- GitHub Actions workflow to build a debug APK.
- A source-derived map of the Python project's capabilities and platform boundaries.

## Not automatically included by the HTML wrapper
The Python desktop runtime, PyQt desktop window, Python automation modules, local Python dependencies, and desktop OS integrations are not executed inside the Android APK.

## Android phases
1. **Phase 1 — UI packaging:** open the original EMBER interface inside the Android app and verify layout, orientation, touch input and startup.
2. **Phase 2 — Android-native services:** add one native feature at a time behind explicit permissions and visible status indicators.
3. **Phase 3 — paired desktop companion:** expose selected desktop capabilities through an authenticated, encrypted connection. The phone must not gain unrestricted remote control.
4. **Phase 4 — validation:** test permission denial, offline behavior, provider failures, kill-switch behavior, background/foreground transitions and data deletion.

## Current limitations
- The current build workflow packages the web UI only.
- The HTML interface can call configured model APIs where Android networking and provider CORS policies allow it. Native HTTP support is enabled in Capacitor for later integration.
- The current Android shell does not implement desktop automation, game control, WhatsApp Web automation, arbitrary file-system access, or unrestricted app control.
- Microphone use depends on Android runtime permission handling and the WebView's media permission behavior; this must be verified on-device before claiming voice support.
- A green CI build proves APK compilation, not that every assistant feature works on a physical phone.

## Security
Do not commit provider API keys, WhatsApp sessions, personal memory, or local secrets. Store future Android credentials using Android Keystore-backed storage, not browser localStorage.
