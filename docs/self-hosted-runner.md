# Self-hosted CI runner on the Mac mini (FBBD-434)

## Why

iOS CI (`ios.yml`) spent most of its time waiting for a macOS runner, not working
(2026-11-01/02: wall time median 113 min, about 45 min of work). GitHub-hosted plans run at most
5 macOS jobs at once ([limits](https://docs.github.com/en/actions/reference/limits)), and one
`ios.yml` run uses 5 (build + 4 test shards), so two overlapping runs queue.
A runner on Arek's Mac mini has no such queue and keeps the Gradle and Kotlin/Native caches.

## How the workflow picks the runner

* Repository variable `IOS_RUNNER` (Settings > Secrets and variables > Actions > Variables):
  a JSON list of runner labels, `["self-hosted","bd-mac"]`. When it is set, `ios.yml`'s build and
  test jobs run on the Mac. Delete the variable to go back to GitHub's `macos-15` runners
  (for example while the Mac is away or off: jobs otherwise wait for it up to 24 h).
* Pull requests from forks always run on GitHub's runners. The repository is public, and GitHub
  advises against self-hosted runners for public repositories because anyone can open a pull
  request ([secure use](https://docs.github.com/en/actions/reference/security/secure-use)).
* On a self-hosted runner each job creates its own simulator (`ios-simulator.py --private`,
  named `BD <runner name> <iPad|iPhone>`) and deletes it at the end, so two runner instances on
  one Mac never drive the same simulator.
* `ios-release.yml` (TestFlight) stays on GitHub's runners: the signing key and App Store
  Connect secrets never reach the Mac.

## Setup on the Mac mini (once)

1. **Separate user.** System Settings > Users & Groups > Add User: Standard (not admin), name `ci`.
   The runner runs as this user, so CI code cannot read Arek's files or keychain.
2. **Xcode.** As the admin user: the same Xcode as GitHub's `macos-15` image (Xcode 26) with the
   newest iOS simulator runtime; then `sudo xcodebuild -license accept` and
   `sudo xcodebuild -runFirstLaunch`.
3. **Tools.** As the admin user: `brew install xcodegen` (the `ci` user cannot run Homebrew).
4. **No sleep.** System Settings > Energy: prevent automatic sleeping.
5. **Runners.** Log in as `ci`. On GitHub: FlappedEar/BetterDriver > Settings > Actions >
   Runners > New self-hosted runner > macOS, ARM64. Run the download commands it shows in
   `~/actions-runner-1`, then the `config.sh` command it shows with
   `--name mac-mini-1 --labels bd-mac --unattended` appended. Repeat in `~/actions-runner-2` with
   `--name mac-mini-2` (a new token from the same page). In each folder:
   `./svc.sh install && ./svc.sh start`. Keep the `ci` user logged in (fast user switching) so
   the simulators have a session.
6. **Fork approval.** FlappedEar/BetterDriver > Settings > Actions > General > section on fork
   pull request workflows from outside collaborators: require approval for all outside
   collaborators.
7. **Switch on.** Settings > Secrets and variables > Actions > Variables > New repository
   variable: `IOS_RUNNER` = `["self-hosted","bd-mac"]`.

The first run downloads the Kotlin/Native toolchain (~1 GB) and fills the Gradle cache; later
runs reuse them.

## Spare PC (Android): not used, on purpose

Checked 2026-11-02 for Arek's spare PC (AMD Ryzen 7 8845HS, 8 cores / 16 threads, Windows 11).
Android CI has no queue (13.6-24 min, every job starts at once on its own 4-vCPU runner), and its
critical path is the emulator jobs, not compiling: "Replay drive" replays a 10-minute drive in
real time (`replay-drive.sh`, `REPLAY_MINUTES=10`), and the smoke tests take about 12-16 min on
the emulator. A PC cannot shorten real time, and one PC running the 7 jobs of a run (3 of them
emulators) would have fewer cores than GitHub gives the run in parallel. Expected gain: a few
minutes at most (low confidence), against an extra machine exposed to a public repository's
pull requests. Revisit if GitHub's Linux runners start queueing.
