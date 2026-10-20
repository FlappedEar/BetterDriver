# Contributing

- Work is tracked in Jira project FBBD; name the issue in the pull request.
- Every pull request gets a Codex review. If Codex has not started on its
  own, comment `@codex review` on the pull request.
- Merge only when CI is green on the latest commit, the Codex review has run,
  and every Codex comment is fixed or answered.
- Run `./gradlew build detekt` before pushing.
- CI also runs the emulator smoke tests (app/src/androidTest, FBBD-161) on an
  Android 14 emulator, split over two emulators (smoke tests; kill, fault and
  permission scripts), beside the Replay drive. A change to documentation only
  (docs/, Markdown outside src/) skips the emulator jobs. To run them locally, start an emulator and run
  `./gradlew :app:connectedDebugAndroidTest`.
