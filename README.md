# Internet Tools

Run uBlock Origin in Samsung Internet, turn off Samsung browser integrations, and stop links from other apps reopening in Secret mode. Root required.

## Install

1. Download the APK from [Releases](https://github.com/ArduinoQuack/Internet-Tools/releases/latest) and install it.
2. Enable **Internet Tools** in LSPosed or your Xposed-compatible module manager. Select **Samsung Internet** (`com.sec.android.app.sbrowser`) only.
3. Open Internet Tools, grant root access, and tap **Set up uBlock**.
4. Tap **Restart browser** when shown. Follow any restart instruction from your module manager.

Updating from an earlier Internet Tools build? Install over it and tap **Restart browser** once. Keep the module enabled for Samsung Internet.

## Settings

### uBlock Origin

Blocks ads, trackers and page scripts. Tap **uBlock settings** to open its dashboard, change filter lists or add your own filters. This is the full uBlock Origin engine with changes needed to run in Samsung Internet.

### Samsung privacy

Turns off the Samsung Internet analytics, browser sync, open-tab sync, smart shopping, coupons and browsing-activity sharing paths covered by the module.

This also stops browser syncing while the switch is on. It does not delete your saved browser data, change your Samsung account, or turn off services in other Samsung apps. It does not block every Samsung network connection.

### Keep new links out of Secret mode

Open links from other apps in normal tabs, even if you last used Secret mode.

For example, leave Samsung Internet in Secret mode, then tap a link in Messages. The link opens in a normal tab without asking whether to use Secret mode. You can still open Secret mode yourself; your existing Secret tabs stay there.

Turn this off to use Samsung's usual behavior. Both switches are on by default. Tap **Restart browser** after changing them so already-running browser services use the new settings.

## Compatibility

Built for Samsung Internet **30.0.5.27**. Tested development setup: Galaxy S24+ (`SM-S926U`), Android 16, with an Xposed-compatible framework. The APK requires Android 11 or newer, but other devices and browser versions have not been verified.

Samsung updates can break the hooks. The bundled uBlock engine passed network-blocking, cosmetic-filtering and scriptlet tests on the development phone. The new settings screen and extra privacy controls passed local checks; phone testing of those changes is still pending.

## Troubleshooting

Open **Troubleshooting** for verification, reports and uBlock removal. Tests and log capture run only when requested there. Normal setup does not run the debugger or blocking tests.

If uBlock will not open, check the module scope and tap **Restart browser**. If a browser update causes problems, disable the module and force-stop Samsung Internet.

Before sharing a report, check it for URLs or other personal information. Include your device, Android version, Samsung Internet version and Internet Tools version.

## Source

- [Build and test](docs/BUILDING.md)
- [Changelog](CHANGELOG.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)
- [Report a security issue](SECURITY.md)

GPL-3.0. Not affiliated with Samsung or the uBlock Origin project. Signing keys and Samsung APKs are not included.
