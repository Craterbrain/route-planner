# Only used with -Pshrink. Libraries ship their own consumer rules (MapLibre, Valhalla, ML Kit, CameraX).
# Our own code that is reached by name:
-keep class app.routeplanner.OverlayService { *; }
-keep class app.routeplanner.MainActivity { *; }
-keep class app.routeplanner.ScanActivity { *; }
-dontwarn org.chromium.net.**
