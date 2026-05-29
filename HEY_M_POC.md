# Hey M Wake Word POC

This Android app is wired as a tablet-first proof of concept:

- Foreground microphone service with a persistent notification.
- Porcupine custom wake word listener.
- Boot receiver for restart after reboot/package update.
- Screen wake and activity launch when the wake word fires.
- Two-minute AAC recording saved directly with `MediaRecorder` in the app's
  external files directory.
- Manual `Simulate Wake` control to test the recording/screen flow before the model is ready.
- Optional root buttons for stay-awake behavior on a controlled tablet.

## Porcupine Setup

1. Create a Picovoice AccessKey in the Picovoice Console.
2. Train/download the Android custom wake word model for `Hey M`.
3. Place the `.ppn` file at:

```text
app/src/main/assets/hey_m_android.ppn
```

4. Install the debug APK, open the app, paste the AccessKey, keep the keyword field as `hey_m_android.ppn`, and tap `Save`.

The app uses `ai.picovoice:porcupine-android:4.0.0`.

## Tablet Setup

For the most reliable PoC tablet mode:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then on the tablet:

- Grant microphone and notification permissions.
- Open `Battery Settings` from the app and allow unrestricted/background behavior.
- Open `Home Settings` from the app and set `Hey M` as the default home app.
- Use a dedicated tablet, keep it plugged in, and keep it on power during testing.
- Use the `Root` controls, Android developer settings, or device-owner policy to make
  the tablet never sleep.
- On Android 14+, boot can post an `Arm Hey M` notification instead of directly starting
  the microphone listener. Opening the app arms the service; if `Hey M` is the default
  home app, this happens naturally when the tablet reaches home after boot/unlock.

## Recording Backend

This PoC currently uses `MediaRecorder` because it saves directly to `.m4a` and keeps
the first prototype simple. `AudioRecord` is the right next step if you want raw PCM,
streaming chunks, custom silence detection, waveform analysis, or local speech models.

If the tablet is rooted, the in-app `Root` controls run:

```text
settings put global stay_on_while_plugged_in 3
svc power stayon true
```

For stricter lab devices, you can also run rooted ADB commands such as:

```powershell
adb shell su -c "dumpsys deviceidle disable"
adb shell su -c "cmd appops set com.example.wakewordpoc RUN_ANY_IN_BACKGROUND allow"
adb shell su -c "cmd appops set com.example.wakewordpoc WAKE_LOCK allow"
```

## Test Flow

1. Tap `Start`.
2. Tap `Simulate Wake`.
3. Confirm the app opens/wakes and starts a two-minute recording.
4. Check `Last file` in the UI for the saved `.m4a` path.

Once `hey_m_android.ppn` and the AccessKey are set, the same path is triggered by the real wake word.
