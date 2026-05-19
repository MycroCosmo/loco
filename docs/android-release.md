# Android Play Store Release

Create `apps/mobile/android/key.properties` locally before building. Do not
commit `key.properties`, `.jks`, or `.keystore` files.

```properties
storeFile=C:/path/to/upload-keystore.jks
storePassword=your-store-password
keyAlias=upload
keyPassword=your-key-password
```

Release builds require production HTTPS URLs through `--dart-define`.

```bash
cd apps/mobile
flutter build appbundle --release \
  --dart-define=API_BASE_URL=https://api.example.com/api \
  --dart-define=SERVER_URL=https://api.example.com
```

For iOS release, remove `NSAllowsArbitraryLoads` from `ios/Runner/Info.plist`
after confirming every production endpoint uses HTTPS.
