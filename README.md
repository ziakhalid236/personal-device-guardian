# Personal Device Guardian — Android app

Private companion app for zia's OWN devices only. It enrolls this phone with
the owner's backend, then runs in the background: heartbeat (location +
battery), command polling, and owner-triggered actions from the private
dashboard (locate, ring, lock, photo, audio).

## Owner consent only

- Every permission is granted manually by the owner in the setup screen.
- Camera and microphone run ONLY when the owner triggers `request_photo` or
  `request_audio` from the dashboard. Android shows its camera/mic indicator
  while they run. There is no silent or continuous recording.
- The owner password is used ONCE at enrollment and never stored on the
  phone. Only the per-device API key is stored.
- The launcher icon can be hidden after setup; the app stays visible in
  Settings > Apps, and the persistent guardian notification reopens setup.

## Backend

Default base URL (changeable in setup):
`https://personal-api.personal-device-guardian.workers.dev`
See `../..` — backend code lives in `~/workspace/projects/cloudflare-backend/`
and the dashboard in `~/workspace/projects/personal-device-guardian/dashboard/`.

## Build

Pushing to `main` builds a debug APK with GitHub Actions
(`.github/workflows/android.yml`). Download it from the Actions run's
artifacts (`pdg-debug-apk`) and install it on the phone. No signing is
configured — debug builds install directly.

Local build (Android SDK + JDK 17 required):

```bash
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Setup on the phone

1. Install the APK, tap Done.
2. Open Device Guardian, grant permissions (location, camera, mic).
3. Allow location all the time; enable device admin for the lock command.
4. Enter the backend URL, a device name, the owner email and password, tap
   "Enroll this phone".
5. Tap "Start guardian service". Optionally hide the launcher icon.

## Commands (from the dashboard)

`locate`, `ring`, `lock`, `refresh_status`, `request_photo`, `request_audio`.
The phone polls for commands every 60 seconds while the service runs and
acknowledges each one after running it.

## Not in v1

- Notification capture (needs a backend endpoint for notification events).
- Real-time push (polling first; Queues/WebSockets later if needed).
