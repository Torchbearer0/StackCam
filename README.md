# StackCam v0.3

Camera2 camera for a Vivo X60 Pro. Pure framework code, no dependencies, no INTERNET permission.

Modes (swipe the strip above the shutter): Video, Photo, Portrait (focus-sweep bokeh), Night (aligned multi-frame),
Pro (manual shutter/ISO/focus), Pano, Time-lapse (MP4), Long exposure (silky water, light trails, traffic trails,
light painting, star trails), Astro (star-aligned stack with stretch), Clone, Document (scan).

Tap the viewfinder to focus. Resolution chip cycles 2.8 / 8 / 12 MP where the mode allows it.
Photos go to Pictures/StackCam, videos to Movies/StackCam. Gear icon: mirror / flip preview and grid.

## Build with GitHub
1. Upload everything in this folder to a GitHub repo (keep .github and app/debug.keystore).
2. Actions -> "Build APK" -> run. Download the artifact "stackcam-debug-apk" (zip with app-debug.apk).
3. Install over the old version (same debug key).
