# Getting started — experimental branch

This guide covers a **fresh Linux build and safe first run** of [`media-server`](README.md). It includes an FFmpeg 7.1.5 shared-library build with Intel Quick Sync Video (QSV), plus a software-only path for machines without an Intel GPU.

> **Read this first:** The codebase and checked-in launchers contain paths from the original server (`/home/ftpud/...`, `/mnt/songs`, etc.). The instructions below deliberately put the build in your home directory, demonstrate portable direct launches, and distinguish an isolated smoke test from an actual 24/7 deployment. The repository does **not** currently provide a one-command installer or complete default `config.properties`.

## 1. What you'll need

- **OS:** Linux x86-64; examples target **Ubuntu 22.04/24.04 or compatible Debian-based hosts**. Other distributions need corresponding package names.
- **Java:** JDK 11 and Maven 3.x (parent POM uses Spring Boot 2.7.17 and `<java.version>11</java.version>`).
- **Rust:** stable `rustc` / Cargo; a C compiler, `pkg-config`, Clang and libclang for `ffmpeg-sys-next`.
- **Media:** locally readable video files; enough disk space for the imported H2 database, logs, test assets, and build artifacts.
- **Streaming:** a reachable RTMP server (the reference installation uses nginx-rtmp, app `main`). You **do not need RTMP** for the first local `.ts` smoke test.
- **For QSV:** supported Intel graphics with the Linux kernel DRM driver, working VA-API media driver, a usable QSV runtime, and access to `/dev/dri/renderD*`.
- **Unicode fonts:** a font that contains the glyphs in your titles (for Japanese, e.g. a WenQuanYi or Noto CJK font).

### Install base dependencies (Ubuntu example)

```bash
sudo apt update
sudo apt install -y \
  git build-essential pkg-config clang libclang-dev \
  nasm yasm cmake make \
  openjdk-11-jdk maven \
  libva-dev libdrm-dev libvpl-dev \
  libass-dev libfreetype-dev libharfbuzz-dev libfontconfig-dev libfribidi-dev \
  libx264-dev libdav1d-dev libzmq3-dev libssl-dev \
  vainfo curl jq python3 fontconfig
```

On some distributions or repositories, individual package names/versions differ. Install Rust via [rustup](https://rustup.rs/) if it is not already present; use a modern stable toolchain.

```bash
java -version
mvn -version
rustc --version
cargo --version
pkg-config --modversion libvpl libva
```

If `openjdk-11-jdk` isn't available in your distro's default repositories, use an installed JDK 11 distribution and set `JAVA_HOME` appropriately. **Do not silently switch to a newer JDK without checking Spring Boot 2.7 compatibility.**

## 2. Intel QSV: hardware and runtime first

**QSV is not an FFmpeg configure flag alone.** A working Linux QSV setup has several layers:

1. An Intel GPU with hardware support for the target codec and profile.
2. A loaded kernel DRM driver and a render node such as `/dev/dri/renderD128`.
3. A compatible userspace VA-API driver (typically Intel `iHD`).
4. The correct Intel runtime/dispatcher: **oneVPL GPU runtime for newer generations**, or a **legacy Media SDK implementation** for older generations.
5. FFmpeg configured with QSV (`--enable-libvpl` in this guide) and the application's required shared `libav*` libraries.

### Check the GPU

```bash
ls -l /dev/dri/
lsmod | grep -E '^(i915|xe)\b' || true
id -nG
# If a device exists, test it (substitute the actual render node):
vainfo --display drm --device /dev/dri/renderD128
```

If access fails with `Permission denied`, inspect `ls -l /dev/dri/renderD*`. On typical Ubuntu systems, joining the `render` group and **logging out/in** is enough:

```bash
sudo usermod -aG render "$USER"
# Re-login, then re-run: id -nG; vainfo --display drm --device ...
```

Some hosts also require the `video` group. Do not set device nodes world-writable. In containers, the render device and necessary userspace libraries must be passed in explicitly.

### Install a driver/runtime appropriate for your GPU

On Ubuntu 22.04/24.04, commonly useful packages are:

```bash
sudo apt install -y intel-media-va-driver-non-free libva2 libva-drm2
```

For an **older Intel iGPU**, such as **UHD 630 / Coffee Lake (Gen9.5)**, the legacy Media SDK runtime (`libmfx1` where available) may be needed:

```bash
# Only where provided by your distro/repositories:
sudo apt install libmfx1
```

For **newer Gen12+ GPUs**, install a supported oneVPL GPU implementation (often packaged as `libmfx-gen1.2` or `libmfxgen1`, depending on the distro/repository). `libvpl-dev` installs **development headers/dispatcher**, not necessarily a working GPU implementation.

**Compatibility caution:** Intel has deprecated the legacy Media SDK runtime; its availability varies by distribution and release. The reference project notes specifically report QSV working on **UHD 630 with its existing host setup and FFmpeg 7.1.5**. That is not a guarantee that a new oneVPL-only installation supports the same older GPU. Test the actual encoder, and use software encoding (`x264`) if your host lacks a supported runtime. On recent Ubuntu releases without `libmfx1`, an older GPU may be unable to use this QSV path without additional legacy packages.

Good external references: [Intel FFmpeg-QSV integration](https://github.com/intel/media-delivery/blob/master/doc/features/ffmpeg-qsv/README.md), [Intel Linux acceleration reference in Jellyfin](https://jellyfin.org/docs/general/post-install/transcoding/hardware-acceleration/intel/), and [QSVEnc's generation/runtime matrix](https://github.com/rigaya/QSVEnc/blob/master/Install.en.md).

## 3. Build FFmpeg 7.1.5 as *shared libraries*

The `mediad` crate uses `ffmpeg-next = "7.1"`; it links directly against FFmpeg's **headers and shared libraries**. A system `ffmpeg` executable is not sufficient if the corresponding development libraries are missing, compiled without needed filters, or have an incompatible version.

The reference server tested **FFmpeg 7.1.5**. Use that tagged version rather than an arbitrary FFmpeg development snapshot (an older untagged snapshot in the project's history crashed in the hardware-frame filter path).

```bash
mkdir -p "$HOME/src" "$HOME/opt"
cd "$HOME/src"
git clone https://github.com/FFmpeg/FFmpeg.git ffmpeg-7.1
cd ffmpeg-7.1
git checkout n7.1.5

export FFMPEG_PREFIX="$HOME/opt/ffmpeg-7.1"
./configure \
  --prefix="$FFMPEG_PREFIX" \
  --enable-shared --disable-static \
  --enable-gpl --enable-version3 \
  --enable-libvpl --enable-vaapi --enable-libdrm \
  --enable-libx264 --enable-libdav1d \
  --enable-libass --enable-libfreetype --enable-libharfbuzz \
  --enable-libfontconfig --enable-libfribidi \
  --enable-libzmq --enable-openssl

make -j"$(nproc)"
make install
```

**Why these options?**

| Option | Used for |
| --- | --- |
| `--enable-shared` | Rust FFmpeg bindings link to versioned shared `libav*` libraries. |
| `--enable-libvpl --enable-vaapi --enable-libdrm` | Linux QSV support via oneVPL and device infrastructure. **Does not install a working GPU runtime by itself.** |
| `--enable-libx264` | Software H.264 development/fallback encoder (`--encoder x264`), plus A/V sync tests. |
| `--enable-libdav1d` | Software AV1 decoding for sources not supported by an older GPU. |
| `--enable-libass` | Subtitles/ASS rendering. |
| `--enable-libfreetype --enable-libharfbuzz` | `drawtext`, titles and complex text shaping; optional `fontconfig`/`fribidi` improve font lookup and text. |
| `--enable-libzmq` | Runtime text/filter control if `MEDIAD_ZMQ` is enabled. |
| `--enable-openssl` | TLS-protocol support in FFmpeg. |
| `--enable-gpl --enable-version3` | License switches needed for this combination of optional dependencies; check licenses before distributing binaries. |

The reference build uses FFmpeg's **native AAC encoder**, not `libfdk-aac`; it does **not** require an Intel-specific FFmpeg fork. It is intentionally not a `--disable-everything` build because the media pipeline uses multiple codecs, demuxers, muxers, filters, text, and network protocols.

### Export build and runtime library paths

```bash
export FFMPEG_PREFIX="$HOME/opt/ffmpeg-7.1"
export FFMPEG_DIR="$FFMPEG_PREFIX"
export PKG_CONFIG_PATH="$FFMPEG_PREFIX/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
export LD_LIBRARY_PATH="$FFMPEG_PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export PATH="$FFMPEG_PREFIX/bin:$PATH"

pkg-config --modversion libavcodec libavformat libavfilter libavutil
"$FFMPEG_PREFIX/bin/ffmpeg" -version | head -n 2
"$FFMPEG_PREFIX/bin/ffmpeg" -hide_banner -encoders | grep -E '(h264_qsv|libx264)'
"$FFMPEG_PREFIX/bin/ffmpeg" -hide_banner -filters | grep -E '(drawtext|subtitles|zmq)'
```

> The repository's [`mediad/build-env.sh`](mediad/build-env.sh) **unconditionally exports** `FFMPEG_PREFIX=/home/ftpud/ffmpeg-7.1`. On a different machine, **edit that file** to your chosen prefix before sourcing it, or use the exports above in your shell instead. The reference `mediad/run-mediad.sh` also hard-codes the library and binary locations.

### Prove QSV *works*, not just that it is listed

First test software H.264 encoding (this should work on CPU-only hosts):

```bash
"$FFMPEG_PREFIX/bin/ffmpeg" -hide_banner -v warning -y \
  -f lavfi -i 'testsrc2=size=1280x720:rate=30' -t 5 \
  -c:v libx264 -pix_fmt yuv420p /tmp/ffmpeg-x264-test.mp4
```

Then test a **real QSV encode** (no extra `hwupload` step required; this feeds compatible software NV12 frames to the Intel encoder, like the project's original production path):

```bash
"$FFMPEG_PREFIX/bin/ffmpeg" -hide_banner -loglevel verbose -y \
  -f lavfi -i 'testsrc2=size=1280x720:rate=30' -t 5 \
  -vf format=nv12 -c:v h264_qsv -b:v 4M \
  /tmp/ffmpeg-qsv-test.mp4

"$FFMPEG_PREFIX/bin/ffprobe" -v error \
  -show_entries stream=codec_name,width,height \
  -of default=noprint_wrappers=1 /tmp/ffmpeg-qsv-test.mp4
```

This is a **hardware-encode** test, not proof that decoding every media format on your GPU is supported. Verify hardware decoding separately using representative source files. On UHD 630, for example, AV1 needs software decode; some unusual H.264 formats will also fall back.

If QSV fails, check the actual runtime, render-node access, `vainfo`, and FFmpeg's verbose error. Seeing `h264_qsv` in `-encoders` proves only that it was compiled in.

## 4. Clone and build the project

```bash
cd "$HOME/src"
git clone --branch experimental https://github.com/ftpud/media-server.git
cd media-server

# With the FFMPEG_* and LD_LIBRARY_PATH exports from section 3 still active:
cd mediad
cargo build --release
cargo test
ldd target/release/mediad | grep -E 'libav(codec|format|filter|util)|libsw(scale|resample)'

cd ../overlay
cargo build --release
cargo test

cd ..
mvn -DskipTests -pl media-service,stream-service,chat-service,live-service,dashboard-service -am package
```

Expected artifacts include:

```text
mediad/target/release/mediad
overlay/target/release/overlay
media-service/target/media-service.jar
stream-service/target/stream-service.jar
chat-service/target/chat-service.jar
live-service/target/live-service.jar
dashboard-service/target/dashboard-service.jar
```

**Important:** `ldd` should resolve `libavcodec`, `libavformat`, `libavfilter`, etc. to **your FFmpeg 7.1.x prefix**, not silently to an older system installation. If it resolves to the wrong version, fix `LD_LIBRARY_PATH` (or install an appropriate loader configuration) and run `ldd` again. Keep the same setup in the production service's runtime environment.

The Java Maven reactor includes the **legacy** `balance-service` and `publisher-service`, but they are not required for the new QSV data path. `-pl ... -am` builds the selected services plus needed shared modules without invoking the full legacy path.

## 5. First run: standalone smoke test

**Do this before configuring any live RTMP stream or starting the Java services.** It works without Intel GPU, nginx, credentials, media database, or overlay.

```bash
# Starting from the repository root, with FFmpeg environment variables active:
mkdir -p /tmp/media-server-smoke
"$FFMPEG_PREFIX/bin/ffmpeg" -hide_banner -v warning -y \
  -f lavfi -i 'testsrc2=size=640x360:rate=30' \
  -f lavfi -i 'sine=frequency=440:sample_rate=44100' \
  -t 6 -c:v libx264 -pix_fmt yuv420p -c:a aac \
  /tmp/media-server-smoke/input.mp4

cd mediad
MEDIAD_W=1280 MEDIAD_H=720 \
  timeout --signal=INT 15s ./target/release/mediad \
  --out /tmp/media-server-smoke/output.ts \
  --encoder x264 --hwdec off --port 18995 \
  --play /tmp/media-server-smoke/input.mp4 || test "$?" -eq 124
cd ..

"$FFMPEG_PREFIX/bin/ffprobe" -v error \
  -show_entries stream=codec_name,codec_type \
  -of default=noprint_wrappers=1 /tmp/media-server-smoke/output.ts
```

You should see video and audio streams in the output. `mediad` stays alive **after** the input ends, emitting black/silence filler; the `timeout` above ends it intentionally. The MPEG-TS container is used because it tolerates interruption of a long-running stream better than an unfinished MP4.

For a stricter regression check (requires a built release binary, `libx264`, Python 3, and FFmpeg tools):

```bash
bash mediad/tests/sync_test.sh
```

## 6. Set up a test RTMP endpoint

For a standalone demo, nginx with its RTMP module can accept the stream locally. The exact installation method depends on the distro; on Ubuntu, the RTMP module is commonly packaged separately:

```bash
sudo apt install -y nginx libnginx-mod-rtmp
```

Add an **`rtmp` block at the top level** of nginx configuration (not inside the `http` block), for example:

```nginx
rtmp {
    server {
        listen 127.0.0.1:1935;  # development-only, loopback ingest
        chunk_size 4096;
        application test {
            live on;
            record off;
        }
    }
}
```

Test the configuration and reload nginx:

```bash
sudo nginx -t
sudo systemctl reload nginx
```

**Don't reuse the production `main` application during development.** This example binds the RTMP listener to **loopback**; use `rtmp://127.0.0.1/test`, which matches the application name above. Publishing to your public platform(s) is a **separate nginx relay/output configuration** not provided by this sample.

## 7. Run `mediad` with QSV (or software encoding)

From the repository root, with the FFmpeg environment still exported:

```bash
cd mediad
MEDIAD_LIVE_FILE="/tmp/media-server-smoke/live.txt" \
MEDIAD_XFADE_MS=1500 \
./target/release/mediad \
  --out rtmp://127.0.0.1/test \
  --encoder qsv --port 8095 \
  --play /tmp/media-server-smoke/input.mp4
```

Replace `--encoder qsv` with `--encoder x264 --hwdec off` on CPU-only hosts or while debugging QSV initialization. Leave the process running in this terminal; in another terminal:

```bash
curl -fsS http://127.0.0.1:8095/health
curl -fsS http://127.0.0.1:8095/status | jq .
"$FFMPEG_PREFIX/bin/ffprobe" -v error \
  -show_entries stream=codec_type,codec_name \
  -of compact rtmp://127.0.0.1/test
```

Expected: `/health` reports `UP`; `/status` shows an output connection, current track/filler state, queue, A/V offset and late-frame counters; the RTMP ingest yields H.264 video and AAC audio. At startup or between tracks, filler output is normal.

### Optional direct control

The HTTP control API is **loopback-only and unauthenticated**. All of these are **GET** endpoints in the current implementation:

```bash
# Queue another local file (URL-encode paths containing spaces/special characters):
curl -G 'http://127.0.0.1:8095/load' \
  --data-urlencode 'path=/tmp/media-server-smoke/input.mp4' \
  --data-urlencode 'title=Test track'

curl 'http://127.0.0.1:8095/events?since=0&wait=1000'
curl 'http://127.0.0.1:8095/skip'
curl 'http://127.0.0.1:8095/seek?ms=10000'
curl 'http://127.0.0.1:8095/clear'
```

Do **not** expose port `8095` externally or publish its endpoints through an unauthenticated reverse proxy.

## 8. Add the overlay

`mediad` must be started with overlay input enabled. The overlay renderer then **connects to it** and sends raw RGBA frames, with a size handshake. The default renderer dimensions in the current source are **1000×660**.

```bash
# Restart mediad with this env var in its launch environment:
export MEDIAD_OVERLAY='127.0.0.1:8096:1000x660+20+40'
# Then launch mediad as in section 7.
```

Launch the overlay in another shell:

```bash
# From repository root:
./overlay/target/release/overlay \
  --target 127.0.0.1:8096 \
  --mediad 127.0.0.1:8095 \
  --media 127.0.0.1:8084 \
  --size 1000x660 \
  --font /usr/share/fonts/truetype/wqy/wqy-microhei.ttc
```

Adapt `--font` to a real `.ttf` or `.ttc` file on your machine; check with `fc-list`. Without `media-service` on `8084`, the overlay can still connect to `mediad`, but **upcoming-list and chat-triggered panels will not have live library data**.

The overlay draws on the CPU, not using QSV. QSV acceleration is for the video encoder and supported decoders in `mediad`.

## 9. Full Java services and configuration

The application is not configured exclusively through `mediad`. The Java services own rotation and scheduling, and `media-service` maintains a local H2 database. Unlike the standalone test, a full setup requires your library paths and service URLs to be configured correctly.

### Working directory and artifacts

The **existing deployment expects** an application directory with jars and runtime assets at its root. An example layout (adjust the paths in any checked-in launch scripts accordingly):

```text
<app>/
  media-service.jar
  stream-service.jar
  chat-service.jar
  live-service.jar
  dashboard-service.jar
  config.properties        # external overrides; keep private
  mediad.conf              # optional KEY=VALUE mediad settings
  dashboard.token          # operator credential; not checked into Git
  media_database.mv.db     # H2 database, created by media-service
  ext/
    mediad
    overlay
    live.txt
  logs/
  versions/
```

Copy the built binaries and jars from the paths in section 4. The source distribution does **not** ship a universal `app/` directory with every file above.

### Java settings

Put the relevant overrides in `<app>/config.properties`. Here is a **minimal illustrative fragment, not a complete production configuration**:

```properties
# Current / experimental player
media.mediad.enabled=true
media.mediad.url=http://127.0.0.1:8095

# media-service: local library and FFmpeg tools
media.library.path=/path/to/your/media-library
media.ffprobe.app=/home/YOUR_USER/opt/ffmpeg-7.1/bin/ffprobe
media.ffmpeg.app=/home/YOUR_USER/opt/ffmpeg-7.1/bin/ffmpeg

# live-service / stream-service must refer to the same live text file
media.live.file=/path/to/your/app/ext/live.txt
```

Review the per-service `src/main/resources/application.properties` in [`media-service`](media-service/src/main/resources/application.properties), [`stream-service`](stream-service/src/main/resources/application.properties), [`chat-service`](chat-service/src/main/resources/application.properties), and [`live-service`](live-service/src/main/resources/application.properties). They contain other required settings and defaults, some of them pointing at the original host. Configure any missing dependencies such as the volume-analysis executable (`media.volume.app`), `subsync.app` if you use subtitles, chat credentials, service-to-service URLs, and public RTMP configuration for your environment.

**Important isolation warning:** When testing alongside a production installation, **override all `media.*.uri` / `media.*.url` service destinations** in the sandbox. A development `media-service` started with a copied production config can send restart commands to a live `stream-service`. Use separate ports, H2 databases, RTMP app names, and config files for tests.

Media rotation currently depends on the project's library conventions, including `___UNDERGROUND`. An arbitrary folder of videos is sufficient for a **manual `mediad --play` test**, but it does not automatically guarantee that the full Java rotation will populate and run unchanged.

### Start service processes

The following is the **manual** way to run the Java modules once their URLs, library paths and credentials have been configured. From `<app>/`, open separate terminals (or add your own systemd service units):

```bash
java -jar media-service.jar \
  --spring.config.location=classpath:/application.properties,config.properties

java -jar stream-service.jar \
  --spring.config.location=classpath:/application.properties,config.properties

java -jar live-service.jar \
  --spring.config.location=classpath:/application.properties,config.properties

java -jar chat-service.jar \
  --spring.config.location=classpath:/application.properties,config.properties
```

Start `mediad` first with a **matching** QSV/CPU encoder, RTMP address and `MEDIAD_OVERLAY` settings; then start `media-service`, `stream-service`, `live-service`, `chat-service` and finally `overlay`/dashboard as needed. `stream-service` talks to `mediad` when `media.mediad.enabled=true`.

### Dashboard

The dashboard runs on **port 9090**. Its deployment tools are designed for the repository's original service-layout/process conventions; adapt those assumptions before using its start/stop/deploy buttons on a new host.

```bash
cd /path/to/your/app
umask 077
openssl rand -hex 32 > dashboard.token
chmod 600 dashboard.token
java -jar dashboard-service.jar \
  --spring.config.location=classpath:/application.properties,config.properties
```

Access `http://127.0.0.1:9090/` locally (or via an authenticated/TLS-protected private tunnel). The dashboard uses the `dashboard.token` credential, an HttpOnly SameSite cookie, and POST-only control actions, but this should **not** be treated as a hardened public-facing authentication system.

### Existing host's supervisor/deploy scripts

- [`mediad/run-mediad.sh`](mediad/run-mediad.sh) is the provided restart-on-exit loop. It assumes `/home/ftpud/ffmpeg-7.1` and `/home/ftpud/media-server/app/ext/mediad`. Adapt these paths before copying it to `<app>/mediad.sh`.
- [`deploy/cutover.sh`](deploy/cutover.sh) expects a **pre-existing legacy production installation**, root privileges, backups, and a staging directory containing specific files. It also terminates the old publisher/balancer. **Do not run it on a clean installation.**
- [`deploy/rollback.sh`](deploy/rollback.sh) restores binaries and configuration from a previous cutover backup; it is **not** a general-purpose uninstall command.
- Never run the legacy publisher and `mediad` against the same `main` RTMP application simultaneously.

## 10. Diagnostics and troubleshooting

| Symptom | Checks / remedies |
| --- | --- |
| `pkg-config` cannot find `libavcodec` / `libavfilter` | Confirm the FFmpeg 7.1.5 `make install` prefix and `PKG_CONFIG_PATH`; use the **same** prefix for Cargo and runtime. |
| Rust `ffmpeg-sys-next` build or bindgen error | Install Clang/libclang, `pkg-config`, FFmpeg headers; inspect the Cargo build log. On custom setups, set `LIBCLANG_PATH` if needed. |
| `error while loading shared libraries: libav*.so` | Set `LD_LIBRARY_PATH` in **the service launcher**, not only your interactive shell. Run `ldd target/release/mediad`. |
| FFmpeg lists `h264_qsv`, but encoding fails | The encoder exists at build time, but a suitable Intel GPU implementation or `/dev/dri` permission is missing. Test `vainfo`, users/groups, Intel runtimes and the QSV encode command from section 3. |
| UHD 630 works under one OS, fails on a fresh install | Check for the **legacy Media SDK runtime** rather than assuming a modern Gen12+ oneVPL GPU runtime supports Gen9.5. If unavailable, use `--encoder x264`. |
| `No such filter: drawtext` / `subtitles` / `zmq` | Rebuild FFmpeg with FreeType + HarfBuzz / libass / libzmq and verify `ffmpeg -filters`. |
| GPU decoder error for AV1 or special H.264 profiles | Unsupported hardware decode is expected on older GPUs; monitor fallback events and software decode load. |
| Black frames and silence on RTMP | Normal when idle. Check `/status` (`current`, `queue`, `filler`) and `stream-service`/`media-service` logs if it never plays tracks. |
| Overlay doesn't appear | Set `MEDIAD_OVERLAY` **before starting `mediad`**, ensure TCP port `8096` is available, start `overlay`, and verify the font path and output geometry. |
| Media files skipped, unreadable or missing | Check service user permissions, imported metadata, actual media mounts, paths and corrupt/AppleDouble `._*` entries. Avoid solving permissions by running everything as root. |
| Duplicate RTMP output or feed interruptions | Do not run `publisher-service`/`balance-service`/legacy concat pipeline concurrently with `mediad` on the same RTMP app. |
| Library unexpectedly controls a live service while testing | Sandbox has inherited production `media.*.url` / `media.*.uri` properties. Isolate all endpoints and databases before restarting. |
| Dashboard starts but can't control/redeploy services | The source contains host-specific process/layout conventions and may expect `ext/`, `logs/`, `versions/` and the original absolute paths. Adapt before relying on it. |

### Useful health checks

```bash
# Media engine
curl -s http://127.0.0.1:8095/health
curl -s http://127.0.0.1:8095/status | jq '{connected,current,queue,filler,late_frames,audio_minus_video_ms}'
curl -s 'http://127.0.0.1:8095/events?since=0&wait=0' | jq .

# Media library (when running)
curl -s http://127.0.0.1:8084/health

# Logs / ports
ss -lntp | grep -E ':8095|:8096|:8084|:8088|:9090|:1935'

# Confirm dynamically loaded FFmpeg libraries
ldd mediad/target/release/mediad | grep 'libav'
```

## 11. Next steps for a durable installation

For 24/7 operation, replace ad-hoc terminals with systemd services and configure a restart policy, working directory, environment file, logging/rotation, and dependency ordering. Grant only the permissions required for media-library reads and the GPU render node. Keep `config.properties`, `dashboard.token`, and stream keys out of version control. Monitor disk health, RTMP reconnects, latency, A/V offset and resource use; test a **separate** RTMP ingest before cutting over production.

For the project's historical cutover notes, existing-host scripts and known issues, consult [`TODO.md`](TODO.md). If the original deployment is still in use, **back it up first**, and use its dashboard/versioned deploy process rather than replaying the clean-install commands over the running system.
