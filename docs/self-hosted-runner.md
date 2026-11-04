# Self-hosted CI runner on the Mac mini (FBBD-434)

## Why

iOS CI (`ios.yml`) spent most of its time waiting for a macOS runner, not working
(2026-11-01/02: wall time median 113 min, about 45 min of work). GitHub-hosted plans run at most
5 macOS jobs at once ([limits](https://docs.github.com/en/actions/reference/limits)), and one
`ios.yml` run uses 5 (build + 4 test shards), so two overlapping runs queue.
A runner on Arek's Mac mini has no such queue and keeps the Gradle and Kotlin/Native caches.

## How the workflow picks the runner

* `.github/ios-runner.json` holds the runner labels for `ios.yml`'s build and test jobs:
  `"macos-15"` (GitHub's runners) or `["self-hosted","bd-mac"]` (the Mac mini). Claude changes it
  by pull request; going back to GitHub's runners (Mac away or off: jobs otherwise wait for it up
  to 24 h) is a one-line change. The job "iOS runner" reads it.
* Pull requests from forks always run on GitHub's runners. The repository is public, and GitHub
  advises against self-hosted runners for public repositories because anyone can open a pull
  request ([secure use](https://docs.github.com/en/actions/reference/security/secure-use)).
* Each runner instance keeps its own simulator per device (`ios-simulator.py --private`, named
  `BD <runner name> <iPad|iPhone> <runtime>`), made once and reused by later jobs, so two runner
  instances on one Mac never drive the same simulator and no job pays for a first boot. Each job
  removes the app and its data first, and shuts down the runner's other simulator.
* `ios-release.yml` (TestFlight) runs on GitHub's `macos-26` by default, so the signing key and
  App Store Connect secrets do not reach the Mac. When GitHub's macOS runners take no jobs (both
  1.33.0 TestFlight runs were cancelled after 15 min queued on 4 Nov 2026), a dispatch from main
  with `runner` = `["self-hosted","bd-mac"]` builds and uploads on a Mac mini: the key is then
  written to the runner's temp folder for the run and removed after it, and the run's development
  certificate is revoked at the end, but Xcode keeps the signing identity in the Mac's keychain.
  `ios-release-fallback.yml` makes that dispatch itself, for the same commit, when a hosted
  TestFlight job was cancelled after 14 min or more in the queue without a machine. Its gate runs
  only the smoke and first-run tests when `.github/scripts/ios-tested.py` finds an iOS CI pass
  that differs from the release only outside the app.
* Overflow (FBBD-457): a pull request run goes to the labels in `.github/ios-overflow.json`
  (e.g. `"macos-15"`) when another unfinished pull request run already has a job for the Mac
  minis. `null` (since 4 Nov 2026, when GitHub's macOS runners left jobs queued until they were
  cancelled) = no overflow: the run waits for the Mac minis.
* Main (FBBD-457): a push to main runs only the rest of the iPad's UI tests when the merged pull
  request's run passed on the same source tree (its "iOS passed (tree ...)" job names the tree it
  tested); otherwise everything on both devices. Queued main runs are dropped when a newer merge
  queues behind them (a running one is never cancelled). Nothing waits for a main commit's iOS
  run: `release.yml` gates on `ci.yml`, `ios-release.yml` has its own iPhone UI gate.
* Test jobs (FBBD-457, `.github/scripts/ios-test-parts.py`): a pull request runs every test on the
  iPhone in three parts of about equal time, and on the iPad only what checks the iPad itself
  (iPad layout, screen layout, review screens, first start); main then runs the iPad's rest, so
  every test runs on both devices before a release. Two Mac runners take two jobs at a time.
* Test time (FBBD-457): the app tests link the release build of the shared core; the demo
  sessions and demo video are made once per app build and copied at each launch (`UITestCache`);
  the unit tests run on the iPhone only; each test job's log ends with `PROFILE` lines (slow
  steps of each UI test, time per kind of step) from `.github/scripts/ios-test-profile.py`.

## Setup on the Mac mini (once, in Terminal)

### A. As Arek (admin)

```sh
xcodebuild -version                 # Xcode 26.x expected; else install Xcode 26 from the App Store
xcrun simctl list runtimes          # an iOS 26.x runtime expected; else: xcodebuild -downloadPlatform iOS
sudo xcodebuild -license accept
sudo xcodebuild -runFirstLaunch
brew --version || /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
brew install xcodegen
sudo pmset -a sleep 0 disksleep 0   # the Mac never sleeps (screen may)
sudo sysadminctl -addUser ci -fullName "CI runner" -password -   # standard user, asks for its password
```

Then log in as `ci` once in the GUI (fast user switching: menu bar > Control Center > user) and
keep that session open; switch back to Arek's account as usual. After a restart, log in as `ci`
again.

### B. As `ci` (Terminal inside the `ci` session)

```sh
echo 'eval "$(/opt/homebrew/bin/brew shellenv)"' >> ~/.zprofile && source ~/.zprofile
cd ~ && rm -rf ~/actions-runner-1 ~/actions-runner-2
url=$(curl -s https://api.github.com/repos/actions/runner/releases/latest | grep -o 'https://[^"]*actions-runner-osx-arm64-[0-9.]*\.tar\.gz' | head -1); echo "$url"
curl -L -o ~/runner.tar.gz "$url"
mkdir ~/actions-runner-1 ~/actions-runner-2
tar xzf ~/runner.tar.gz -C ~/actions-runner-1
tar xzf ~/runner.tar.gz -C ~/actions-runner-2
ls ~/actions-runner-1/config.sh ~/actions-runner-2/config.sh
```

The token: FlappedEar/BetterDriver > Settings > Actions > Runners > New self-hosted runner >
macOS, ARM64, the `--token` value in its "Configure" section (valid 1 h; reload the page for a
new one).

```sh
cd ~/actions-runner-1
./config.sh --url https://github.com/FlappedEar/BetterDriver --token TOKEN --name mac-mini-1 --labels bd-mac --unattended
./svc.sh install && ./svc.sh start
cd ~/actions-runner-2
./config.sh --url https://github.com/FlappedEar/BetterDriver --token TOKEN --name mac-mini-2 --labels bd-mac --unattended
./svc.sh install && ./svc.sh start
```

The Runners page then lists `mac-mini-1` and `mac-mini-2` as Idle. Claude switches
`.github/ios-runner.json` to `["self-hosted","bd-mac"]` and measures the first run. The first run
downloads the Kotlin/Native toolchain (~1 GB) and fills the Gradle cache; later runs reuse them.

## Spare PC (Android): not used, on purpose

Checked 2026-11-02 for Arek's spare PC (AMD Ryzen 7 8845HS, 8 cores / 16 threads, Windows 11).
Android CI has no queue (13.6-24 min, every job starts at once on its own 4-vCPU runner), and its
critical path is the emulator jobs, not compiling: "Replay drive" replays a 10-minute drive in
real time (`replay-drive.sh`, `REPLAY_MINUTES=10`), and the smoke tests take about 12-16 min on
the emulator. A PC cannot shorten real time, and one PC running the 7 jobs of a run (3 of them
emulators) would have fewer cores than GitHub gives the run in parallel. Expected gain: a few
minutes at most (low confidence), against an extra machine exposed to a public repository's
pull requests. Revisit if GitHub's Linux runners start queueing.
