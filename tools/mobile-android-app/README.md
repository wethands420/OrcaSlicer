# Orca Mobile Android MVP

Android companion prototype for the OrcaSlicer VM service.

The app browses model websites in a local Android WebView. When the user taps a
download link, the Android `DownloadListener` intercepts the download and sends
the URL, filename, MIME type, user agent, and cookies to the VM service. The file
is downloaded and imported on the VM; no model file is saved on the phone.

## Open in Android Studio

1. Install Android Studio with Android SDK 35.
2. Open `tools/mobile-android-app` as a project.
3. Let Android Studio sync Gradle.
4. Run the `app` configuration on an Android device connected to the same LAN as
   the VM.

The default VM URL in the app is:

```text
http://192.168.1.79:8787
```

Change it in the text field at the top of the app if your VM IP changes.

## Current MVP Flow

1. Choose Printables, MakerWorld, Thingiverse, or Cults3D.
2. Browse and log in normally in the WebView.
3. Tap a model download button.
4. Confirm `VM Download`.
5. Wait for VM download and inspection.
6. Choose a supported file from the import list.

Slicing, upload to Bambu A1, and print start are already available in the VM API
but are not wired into this Android screen yet.

## Login Notes

Google sign-in may block Android WebView with a `disallowed_useragent` style
message. That is a Google OAuth restriction for embedded browsers, not a VM
download bug. Use a platform-native username/password login when available.

The app includes an `Extern öffnen` button for pages that need a full browser,
but cookies from Chrome are not shared back into the WebView.
