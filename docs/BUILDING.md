# Build and test

Requires JDK 17, Android SDK platform 35 and build tools 35.0.0. Set `ANDROID_HOME` or put your SDK path in `local.properties`. The wrapper downloads Gradle 8.13.

```bash
bash ./gradlew assembleRelease testDebugUnitTest
```

The APK is written to `build/outputs/apk/release/InternetTools-release.apk`. Local builds use the Android debug key unless you supply your own signing settings. They cannot update the official APK without its private signing key.

To sign with your own key, set `IT_KEYSTORE`, `IT_STORE_PASSWORD`, `IT_KEY_ALIAS` and `IT_KEY_PASSWORD` in your build environment. Never commit the key or its passwords.

## Bundled uBlock Origin

The app includes a pinned Samsung-compatible build of uBlock Origin 1.75.0. Its extension manifest version is 1.75.0.7. The signed package is `assets/ublock-origin.crx`; its SHA-256 is:

```
0ff73764cc9768b0938dfe97938740a28b72f87aca6cc0a315fc9c958674df73
```

The original extension and upstream source archive are in `upstream/`. Compatibility changes and build scripts are in `compat/`. An ordinary Android build uses the existing CRX and does not rebuild it.

To inspect or test the compatibility bundle:

```bash
npm ci --prefix compat
node compat/build.cjs build/ubo-compat
node tests/reference/run.cjs build/ubo-compat
```

Repacking a changed extension requires your own extension signing key and corresponding extension identity changes. The official CRX signing key is private. Do not replace the bundled package and expect it to keep the official extension ID.

The Android tests cover settings, command authentication, operation deadlines and diagnostic controls. JavaScript tests use simulated browser APIs; they do not replace testing Samsung Internet on a phone. Target validation needs a separately obtained Samsung Internet APK, which is not distributed here.
