# StackCam v0.2

Camera2 camera for a Vivo X60 Pro. Pure framework code, no dependencies, no INTERNET permission.

Modes (swipe the strip above the shutter): Video, Photo, Portrait (focus-sweep bokeh), Pro (manual shutter/ISO/focus),
Time-lapse (MP4), Long exposure (silky water, light trails, traffic trails, light painting, star trails),
Astro (star-aligned stack with auto stretch for Milky Way), Clone.

Portrait UI. Photos save to Pictures/StackCam, videos to Movies/StackCam. Output is rotated using the phone's orientation sensor.
Gear icon: mirror / flip preview and grid (use if the viewfinder ever looks flipped).

## Build with GitHub
1. Upload everything in this folder to a GitHub repo (keep .github and app/debug.keystore).
2. Actions -> "Build APK" -> run. Download the artifact "stackcam-debug-apk" (zip with app-debug.apk).
3. Install over the old version (same debug key).
