# Room (used inside WorkManager) creates the generated *_Impl database with reflection. AGP 9's
# strict R8 mode no longer keeps that no-arg constructor for library rules that only name the
# class, so WorkDatabase_Impl.<init>() was stripped and the app crashed on start (v0.7.16).
-keep class * extends androidx.room.RoomDatabase { <init>(); }
