# System Monitor

A native Android app (Kotlin + Jetpack Compose) that displays:

- **CPU** — per-core usage % and current frequency (read from `/proc/stat` and `/sys/devices/system/cpu/cpuN/cpufreq/scaling_cur_freq`)
- **Memory** — used / available RAM, low-memory state
- **Battery** — charge %, health, charging status, temperature, voltage, technology, live current draw
- **GPU** — vendor / renderer / GL version strings (queried via a throwaway EGL context)

Refreshes automatically every ~1.2 seconds.

### A note on GPU monitoring
Android does not expose a public API for live GPU utilization (no "GPU %" like CPU has). This app shows GPU **identity** info (vendor, renderer, driver version) via OpenGL ES. True real-time load numbers exist only in vendor-specific files under `/sys/class/kgsl/` or similar, which require root to read reliably across devices — so they're intentionally left out rather than faked.

### Permissions
No dangerous permissions are requested. Everything reads from public system files/APIs available to any app.

---

## How to build the APK (no Android Studio needed)

This repo includes a GitHub Actions workflow that builds the APK on GitHub's servers for free.

1. Create a new **public or private** repository on GitHub.
2. Upload every file/folder from this project, preserving the folder structure (including the hidden `.github` folder).
3. Go to the **Actions** tab of your repo. If it asks you to enable workflows, click to enable them.
4. The build will run automatically on push (or click "Run workflow" manually under the "Build APK" workflow).
5. When it finishes (green checkmark, ~2-3 minutes), open the completed run and scroll down to **Artifacts** → download `system-monitor-debug-apk`.
6. Unzip it — you'll get `app-debug.apk`.
7. Transfer it to your phone and install it (you'll need to allow "install unknown apps" for whichever app you use to open the file).

This is a **debug build**, which is fine for installing on your own device. It is not signed for Play Store distribution.

## Building locally instead (optional)
If you ever do have Android Studio: open this folder as a project and click Run, or use `./gradlew assembleDebug` from a terminal with the Android SDK installed.
