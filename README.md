# StackCam (v0.1)

Camera2 long-exposure and clone camera, built for a Vivo X60 Pro. Pure framework code, no dependencies, no INTERNET permission.

Modes: Photo/Pro (manual shutter, ISO), Silky water (average), Light trails, Traffic trails, Light painting, Star trails (lighten stack), Clone (median background + pasted subject).
Hold the phone in landscape. Photos save to Pictures/StackCam.

## Build with GitHub
1. Create a new GitHub repo and upload everything in this folder (keep the .github folder and app/debug.keystore).
2. Open the Actions tab -> "Build APK" -> run it (it also runs on every push to main).
3. When it finishes, open the run and download the artifact "stackcam-debug-apk". It is a zip containing app-debug.apk.
4. Install the APK. Later builds install over the old one because the debug key is fixed.
