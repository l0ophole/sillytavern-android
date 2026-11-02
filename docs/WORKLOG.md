# Work Log

## Done
- Plan 00 (stack + MVP) approved and saved: docs/plans/00-stack-and-mvp.md
- git repository initialised (reference source in SillyTavern/ is git-ignored; it is a separate repo)
- MVP item 1: project scaffold (Kotlin 2.2.20, AGP 8.13, Gradle 8.13 wrapper, Compose M3, minSdk 26, compileSdk 35).
  Debug APK builds and the smoke unit test passes.

## Environment notes
- SDK: ~/Android/Sdk (user-owned; platforms android-35/36.1, build-tools 34-37). local.properties is git-ignored.
- Build with JDK 21 (pinned via org.gradle.java.home in gradle.properties; system default JDK 27 is too new).

## In progress
- MVP item 2: storage layer

## Remaining
- MVP items 2-7 (see docs/plans/00-stack-and-mvp.md)
- Roadmap sets 2+ (docs/ROADMAP.md to be written after MVP approval)
