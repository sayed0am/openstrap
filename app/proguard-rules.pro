# WorkManager 2.7.1 (what Glance brings) makes its input mergers and workers by reflection, and its own rules no longer
# keep their constructors under R8 full mode. Without them Glance's SessionWorker never runs and every widget stays on
# the launcher's loading placeholder ("Could not create Input Merger" in logcat), in release builds only.
-keep class * extends androidx.work.InputMerger { public <init>(); }
-keep class * extends androidx.work.ListenableWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
