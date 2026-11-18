# TODO: mediad (persistent ffmpeg-based compositor/encoder)

Goal: replace the per-track ffmpeg -> balance-service -> publisher (concat) chain with ONE
long-running process built on libav* (`mediad`) that owns the clock, decodes tracks, applies
overlay/transitions, encodes once with QSV and pushes a single continuous RTMP stream.

Status legend: `[ ]` todo, `[x]` done, `[?]` needs a decision from the owner.

## Decisions (resolved)

- [x] Language: **Rust** (`ffmpeg-next` / `ffmpeg-sys-next`).
- [x] sudo available through the workspace root terminal.
- [x] Shared ffmpeg build installed under `/opt/ffmpeg-mediad`.
- [x] media-service moves to a cursor-based rotation driven by mediad events.
- [x] A few seconds of downtime at cutover is fine.
- [x] Include `libzmq` in the ffmpeg build (runtime control of filters instead of reload files).
- [x] Work happens on git branch `experimental`.

## Phase 0: Baseline and quick wins (independent of mediad)

- [ ] Look at the "Abnormal invoke" storm in `stream-service.log` (e.g. 2026-11-17 23:16, `___UNDERGROUND/Oiran Dochu`) and find the root cause.
- [ ] Limit `volumedetect` concurrency to 1 and run it with `nice`/`ionice` (startFullProcessing / fixVolume path). Server load is ~9 on 8 cores.
- [ ] Fix the `/media/now` -> `/fixVolume` self-call loop (blocking call, volume stays null, restart every cycle).
- [ ] Add log rotation (`stream-service.log` is ~690 MB, `chat-service.log` ~80 MB).
- [ ] Record baseline numbers to compare later: stream ffmpeg CPU/RSS, load average, publisher restarts per day (`grep -c "Publisher terminated" publisher-service.log`), abnormal-invoke count.

## Phase 1: Build ffmpeg for linking

> **Finding (2026-11-18):** the existing `ffmpeg_stream` is a mid-cycle master snapshot (`n6.2-dev-670`, Jan 4 2024). On it, ANY hardware-frame path
> (`-hwaccel qsv|vaapi`, `hwupload`, `hwdownload`) segfaults in `avfilter_graph_config` (libavfilter, not the GPU stack). Production only works
> because it never uses GPU frames (software decode + `h264_qsv` fed with software frames; the `-hwaccel` flag is commented out in `FfmpegHelper`).
> A tagged release does not have the problem: **mediad builds against ffmpeg `n7.1.5`** (final choice; 8.x would need dav1d >= 1.0, the system has 0.9.2) installed at `/home/ftpud/ffmpeg-7.1`
> (source `~/src/ffmpeg-7.1`, a git worktree of `~/src/ffmpeg`). The `n6.2-dev` build in `/home/ftpud/ffmpeg-mediad` (symlinked `/opt/ffmpeg-mediad`) is obsolete and can be deleted.
> Use `LD_LIBRARY_PATH=/home/ftpud/ffmpeg-7.1/lib` and `PKG_CONFIG_PATH=/home/ftpud/ffmpeg-7.1/lib/pkgconfig`.

- [x] Fetch source for the existing build: `N-113212-g42982b5a5d` is commit `42982b5a5d` (`n6.2-dev-670`), checked out in `~/src/ffmpeg`.
- [x] Configure with `--enable-shared` and the same hardware/filter flags (`libvpl`, `vaapi`, `libass`, `libfreetype`, `libharfbuzz`, `libx264`, `libdav1d`, `libzmq`, `openssl`). Dropped `libfdk-aac` (nonfree; the streamer already uses the native `aac` encoder) and `libsrt`/`libvorbis` (unused). Added `--enable-version3` for OpenSSL 3.
- [x] Installed to `/home/ftpud/ffmpeg-mediad` (symlinked as `/opt/ffmpeg-mediad`; `/` has only ~11 GB free, `/home` has ~245 GB). Use `LD_LIBRARY_PATH=/opt/ffmpeg-mediad/lib` and `PKG_CONFIG_PATH=/opt/ffmpeg-mediad/lib/pkgconfig`. Source/build tree: `~/src/ffmpeg`. Install (do not touch `/home/ftpud/media-server/app/ext/ffmpeg_stream`; it stays as the rollback path).
- [x] Verify dev headers/libs are present (installed `nasm libzmq3-dev libx264-dev libdav1d-dev libclang-dev`) (`libva-dev`, `libvpl-dev` are installed). QSV encode verified with the new build (software NV12 frames -> `h264_qsv`, ~8x realtime, same as the production binary; run via the root terminal since `ftpud` is not in the `render` group). NOTE: a `hwupload` -> `h264_qsv` pipeline segfaults in BOTH the new build and the old `ffmpeg_stream` (exit 139), so mediad should feed `h264_qsv` software frames like production does, and `overlay_qsv`/GPU-resident frames need separate investigation.
- [x] Build ffmpeg `n7.1.5` with the same flags (shared, `libvpl`, `vaapi`, `libass`, `libx264`, `libdav1d`, `libzmq`, ...). Verified: no segfaults, `hwupload` -> `h264_qsv` works, QSV encode works.
- [x] Verify QSV/VAAPI decode against the real library (see "Library codec census" below).
- [ ] Switch the `/opt/ffmpeg-mediad` symlink to the 7.1 build (needs root) or just reference `/home/ftpud/ffmpeg-7.1` directly, and delete the obsolete n6.2-dev install/build tree.

## Library codec census (sampled 2,862 files with video, run as root)

| Codec | Share | QSV decode on this iGPU (UHD 630, Gen9.5), ffmpeg 7.1.5 |
|---|---|---|
| h264 (mostly High/Main 8-bit yuv420p; 220 are full-range `yuvj420p`) | 76% | Works. CPU time ~0.7 -> ~0.2 s per 6 s of 1080p |
| vp9 (incl. ~50 4K files in the sample) | 11% | Works (1080p ~0.75 -> ~0.22, 4K ~3.9 -> ~1.2) |
| mpeg2video (TS broadcasts, mostly interlaced) | 6.4% | Works; needs deinterlacing (production adds `yadif`) |
| hevc 8-bit | 4% | Works |
| av1 | 1.4% | NO hardware decode on Gen9.5 -> software (dav1d) |
| mpeg4 ASP / mpeg1 / h263 | 1.2% | No QSV decoder -> software |
| h264 10-bit / 4:2:2 / 4:4:4 (a handful) | <0.5% | Fails on Gen9 -> software |

- ~190 files are interlaced (`tt`/`bb`); ~460 report `unknown` field order. Deinterlace on a per-file basis (probe `field_order`), not unconditionally.
- Design consequence: mediad needs a **per-file decoder selection with automatic software fallback** (try QSV for h264/hevc/vp9/mpeg2 8-bit 4:2:0, otherwise software; also fall back if hardware init/decode fails at runtime).
- Full-range `yuvj420p` sources need correct range handling in scale/convert to avoid washed-out or crushed colors.
- Many files are owned by other users (e.g. `akb`, mode 700) and only readable as root. The streaming services run as root, so mediad must too, or the permissions must be fixed.
- The library contains macOS AppleDouble stubs (`._*.webm`, `._*.mp4`, ~4 KB). `media-service` imports them (supported extension), ffprobe fails, length becomes 0/null and they are silently skipped. Add a filter for `._*` in `MediaDbService` (Phase 4).

## Working notes

- Put anything that needs disk space under `/home` (`/` is at ~89%). Cargo target dirs, ffmpeg source/build and test media all live on `/home`.
- Root terminal (workspace terminal MCP) is available for `apt`, GPU access tests and installs.

## Phase 2: mediad skeleton

- [x] (scaffold done: `mediad/Cargo.toml`, `build-env.sh`, `src/main.rs` probe that links ffmpeg-next 7.1 against ffmpeg 7.1.5; `source mediad/build-env.sh` before `cargo build`) Create `mediad/` at the repo root (outside the Maven build): `Makefile` or `Cargo.toml`, README with build instructions, config file format.
- [ ] Dev profile: software `libx264` and output to a file or null, so it can be developed without the iGPU.
- [ ] Prod profile: `h264_qsv`, settings copied from `FfmpegHelper.getFfmpegProdCommand` (4500k / maxrate 5500k / bufsize 4500k / g=60 / look_ahead / extbrc, 1080p30, AAC 320k 44.1 kHz stereo).
- [ ] Master clock: output loop ticks at 30 fps; PTS = frame counter; audio PTS = sample counter; paced to wall time.
- [ ] Decoder slots: demux + decode thread per active track, bounded frame queue.
- [ ] Filter graph per track: scale + pad to 1920x1080 (same as `FfmpegHelper`), drawtext for title and for the live text file (`reload=1`), optional subtitles (`ass` / `subtitles`).
- [ ] Audio: decode, resample to 44.1 kHz stereo, apply gain in dB per track.
- [ ] Single encoder + FLV mux + RTMP output (`rtmp://localhost/main`, same target as today's publisher).
- [ ] Filler frames and silence when no track is ready (decoder error, missing file, end of list), so the encoder never starves.
- [ ] RTMP reconnect inside the process (encoder keeps running, resume on a keyframe).
- [ ] Logging to `logs/mediad.log` with rotation; periodic stats line (fps, queue depth, dropped frames).

## Phase 3: Control API and events

- [ ] Local control API (Unix socket or HTTP on localhost): `load` (path, start offset, gain dB, title, subtitle file), `skip`, `seek`, `status`.
- [ ] Events back to the caller: `track_started`, `track_ending` (N seconds before end, for pre-roll), `track_ended`, `track_error`, `position`.
- [ ] Pre-roll: open the next track's decoder a few seconds before the current one ends. Cancel and re-roll on `skip`/`seek`.
- [ ] Define behavior for a bad file: log, emit `track_error`, play filler, continue.

## Phase 4: Java integration

- [ ] `stream-service`: replace the per-track ffmpeg spawning (`StreamService.runFfmpeg`) with a `MediadClient` that sends `load` and reacts to events. Keep `FfmpegHelper`/`SubtitlesService` logic as the source of settings until fully ported.
- [ ] `media-service` rotation: replace time-based lookup (`RotationRepository.getItemForTime` + `ChronoService`) with a cursor into the list advanced on `track_started`. Add `peek next` for pre-roll and for showing upcoming items.
- [ ] While there: fix the end-of-list bugs (`listNext`, `putNextQueued`), infinite recursion on an empty/all-missing list, thread safety of `rotationList`, regex search handling (case-insensitive pattern, invalid pattern).
- [ ] Pass volume gain from media-service data; clamp the gain (no unbounded boost, handle `-inf`).
- [ ] Unique per-track subtitle output path instead of `/tmp/converted_sub.*`; do not reuse a stale file if subsync fails.
- [ ] Escape `%` and `\` in drawtext titles (currently only `'` is stripped).
- [ ] `RestClient`: handle 5xx (`HttpServerErrorException`) so callers don't crash; do not let an exception in one callback end `StreamService.run()`.
- [ ] Chat: fix `!time` format mismatch with `/media/seek`, guard `!random` with empty results, tighten the `+`/`-` tag commands.
- [ ] Dashboard `ServiceController`: add `mediad` to `validServices`, log map and start/stop/deploy handling.

## Phase 5: Test and soak

- [ ] Unit/integration tests for the control API and clock (synthetic sources, e.g. `testsrc` / `sine`).
- [ ] Run mediad next to production on a separate RTMP app (`rtmp://localhost/test`), off-peak, for 24 h+.
- [ ] Fault tests: corrupt file, missing file, zero-length file, file without audio, very long file, `!next` / `!time` spam, RTMP server restart, media-service restart.
- [ ] Check A/V sync after several hours (clock drift) and memory growth of the mediad process.
- [ ] Compare against baseline: CPU, RSS, dropped frames, reconnect count.

## Phase 6: Cutover and cleanup

- [ ] Add a watchdog/supervisor for mediad (restart within seconds on crash).
- [ ] Cut over: stop publisher-service, balance-service and the old `ffmpeg_stream` concat process, start mediad on `rtmp://localhost/main`. Keep the old services deployable for rollback.
- [ ] Verify with the real viewers' player: no reconnect at track changes.
- [ ] After a stable period, remove `publisher-service`, `balance-service`, `list.txt`, the FIFOs, `detectAbnormality`, and the dashboard entries.
- [ ] Update `README.md`.

## Phase 7: Overlay renderer (later)

- [ ] Separate overlay service rendering RGBA frames (native EGL renderer or headless Chromium; decide after measuring iGPU headroom with `intel_gpu_top`).
- [ ] mediad reads overlay frames from a local TCP socket as an extra filter-graph input; fully transparent/skipped when idle.
- [ ] Wire live data (queue/upcoming items, chat) from media-service and live-service to the renderer; retire the `live.txt` reload.
- [ ] Check the cost: start with a small region at 15-30 fps; consider `overlay_qsv` to keep frames on the GPU.

## Phase 8: Transitions (later)

- [ ] Two overlapping decoder slots in the filter graph.
- [ ] Transition types (crossfade first, then wipe/slide/custom), with a configurable duration.
- [ ] Interaction with `skip`/`seek` (cut instead of transition when the user skips).
- [ ] Optional audio crossfade.

## Risks to keep in mind

- A mediad crash stops the whole stream (today a track ffmpeg crash only costs one track): needs the watchdog and the soak test.
- A/V drift: video and audio must be trimmed/padded to exact lengths per track.
- Server is already loaded (load ~9 on 8 cores): test off-peak, fix the volume analysis concurrency first.
- Everything runs as root today; mediad needs `/dev/dri/renderD128` access.
