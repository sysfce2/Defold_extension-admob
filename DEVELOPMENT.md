# Notes on setup of this extension

The extension uses the [iOS Mobile Ads SDK](https://developers.google.com/admob/ios/quick-start) and the [GMA Next-Gen SDK for Android](https://developers.google.com/admob/android/next-gen/quick-start).

## Android SDK update

Run the Android updater from the repository root:

```sh
python3 updater/android.py
```

The updater reads the latest GMA Next-Gen SDK release and mediation adapter definitions, then regenerates `extension-admob/manifests/android/build.gradle`. Do not edit that generated file by hand.

Check the [GMA Next-Gen SDK release notes](https://developers.google.com/admob/android/next-gen/rel-notes) for breaking changes that require extension API updates.

## iOS SDK update

Open `extension-admob/manifests/ios/Podfile` and change version in `pod 'Google-Mobile-Ads-SDK', 'X.X.X'` to the latest.

Check [Release Notes](https://developers.google.com/admob/ios/rel-notes) to make sure there are no breaking changes and all new APIs implemented.
