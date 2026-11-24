# netspeed

**Android network speed test + memory floating ball.** Pure Java, **no third-party dependencies**, **69 KB APK**.

## Features

**Speed test** — download / upload / latency / jitter.
The download side probes several sources concurrently and picks the best, then uses HTTP Range to fetch in parallel segments; upload uses multi-threaded POST; latency is the median of TCP handshakes.

**Floating ball** — shows memory usage with a usage arc. The number is strictly centred (the baseline is computed from font metrics, not a hardcoded offset).

- Single tap → two lines: `RAM 5.6G/11.3G 55%` / `DISK 202GB/256GB 83%`
- Drag → follows your finger exactly (absolute coordinates, not accumulated deltas)
- Long press → snaps to the edge and hides
- Idle for 3 s → auto-snaps to the edge and turns semi-transparent
- Tap the edge it's stuck to → pull it back out

## How it stays small in memory

| Area | Approach |
|---|---|
| Process | the floating service runs in its own `:float` process, separate from the main UI |
| Memory reading | scans `/proc/meminfo` through `FileInputStream` byte-by-byte, never allocating a `String` |
| Disk reading | one `StatFs` call, no directory walk |
| Panel | two `TextView`s, set to `null` when collapsed |
| Redraw | `invalidate()` is skipped when the percentage hasn't changed |
| Speed test | 8 KB buffer, 32 MB total cap, nothing written to disk |
| Notification | `IMPORTANCE_MIN` — no sound, no banner |

## Colour bands

`< 75` green · `75–90` yellow · `> 90` red

## Build

Needs a JDK plus the Android SDK build-tools (aapt2 / d8 / apksigner).

```bash
./build.sh .
```

Set these four paths in `env.sh`:

```bash
export ANDROID_JAR=/path/to/android-34/android.jar
export ANDROID_D8=/path/to/build-tools/lib/d8.jar
export ANDROID_KS=/path/to/debug.keystore
```

No Gradle, no AndroidX — one shell script produces the APK.

## Install

Grab `apk/netspeed-v1.2.apk` from the releases, or build it yourself. On first launch, grant the **Display over other apps** permission.

## Layout

```
src/com/y/netspeed/
  MainActivity.java   main screen + speed-test scheduling
  FloatService.java   the floating ball (separate process)
```

## License

MIT — see [LICENSE](LICENSE).
