# J7 Prime ROM - Strong Self-Hosted Runner

The repository now has a Crave-independent Android build workflow:

- Workflow: **Build J7 Prime ROM - Strong Self-Hosted Runner**
- Target: Samsung Galaxy J7 Prime / SM-G610F / on7xelte
- Base: LineageOS 19.1 / Android 12L
- Architecture: ARM64
- GApps: MindTheGapps sigma integrated
- WebView: retained
- Magisk: excluded
- Slim profile: applied by scripts/prepare-j7prime.sh

## Runner requirements

Recommended:

- Linux x86_64
- Ubuntu 22.04
- 32 GB RAM or more
- 200-250 GB SSD or more
- 8+ CPU threads
- Fast internet connection

The workflow stops before syncing if the runner has less than:

- 24 GB RAM
- 180 GB free disk

## Run

1. Register the Linux x64 machine as a repository self-hosted runner.
2. Keep its default labels `self-hosted`, `linux`, and `x64`.
3. Open GitHub Actions.
4. Run **Build J7 Prime ROM - Strong Self-Hosted Runner**.
5. First build:
   - CLEAN_SOURCE: `yes`
   - SYNC_JOBS: `8`
6. Later builds:
   - CLEAN_SOURCE: `no`
   - keep the synchronized source tree to avoid a full re-sync.

## Crave

Crave workflows are still present for compatibility, but this build workflow does not require Crave credentials or the Crave CLI.

## Output

The workflow uploads:

`J7-Prime-on7xelte-Android-12L`

The artifact contains the ROM ZIP and generated boot/recovery/dtbo images when present, plus SHA256SUMS.txt.

The final ZIP target is under 1 GB, but the actual size can only be confirmed after a successful build.
