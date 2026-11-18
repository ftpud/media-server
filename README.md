# media-server project

Set of services that streams a media library 24/7 to RTMP, controlled from chat and a web dashboard.

Created for personal usage.

![image](https://github.com/ftpud/media-server/assets/7257224/7bf40afd-95ad-4e9d-9539-a80f34ede6d9)

## Architecture

```
            chat (Twitch / GoodGame / sc2tv)
                        │ commands
                        ▼
 chat-service ──► media-service ◄──────────────── dashboard-service (web UI, :9090)
                  (library, rotation,      ▲             │ start/stop/deploy/logs
                   queue, search)          │ sync        ▼
                        ▲                  │      all services + host
                        │ now / list       │
                  stream-service ──────────┘
                        │ load / skip (HTTP)    ▲ events
                        ▼                       │
 overlay ──RGBA──►   mediad  ── one encoder ──► RTMP (nginx-rtmp `main`) ──► platforms
 (3D card)          (Rust, ffmpeg 7.1 libs)
                        ▲
 live-service ── live.txt (on-screen messages)
```

| Component | Language | Role |
|---|---|---|
| `mediad/` | Rust | Persistent player/encoder: decodes tracks on their own threads (QSV hardware decode with software fallback), crossfades, sample-accurate A/V, titles, live text, overlay input, one `h264_qsv`/AAC encoder and one RTMP connection that never restarts. Control API on `:8095` (`/load /skip /seek /clear /status /events /snapshot.jpg`). |
| `overlay/` | Rust | Renders an animated "now playing / up next" 3D card on the CPU and streams it to mediad. |
| `media-service` | Java | Library database, rotation (`___UNDERGROUND`), queue, search, tags; `/media/sync` keeps its position in step with mediad. |
| `stream-service` | Java | Drives mediad (`media.mediad.enabled=true`): queues the next track before the current one ends, reloads on seek/next. The old per-track ffmpeg mode is still there when disabled. |
| `chat-service`, `live-service` | Java | Chat commands; on-screen live messages (`live.txt`). |
| `dashboard-service` | Java + vanilla JS | Control panel: services (start/stop/restart, health, CPU/RAM), live logs, stream panel (preview, now playing, up next, next/seek/search/queue/messages), host (load, memory, disks), versioned deploys with rollback. Token login (`app/dashboard.token`). |
| `publisher-service`, `balance-service` | Java | Legacy pipeline (replaced by mediad), kept for rollback. |

## Build

```bash
# ffmpeg 7.1.5 shared libraries are installed in /home/ftpud/ffmpeg-7.1 (see TODO.md, Phase 1)
cd mediad && source ./build-env.sh && cargo build --release      # -> target/release/mediad
cd overlay && cargo build --release                              # -> target/release/overlay
mvn -DskipTests -pl media-service,stream-service,dashboard-service -am package
```

Tests: `cargo test` in `mediad/` and `overlay/`; end-to-end A/V sync: `mediad/tests/sync_test.sh`.

## Deploy

Copy a build into `/home/ftpud/media-server/deploy/` (`*.jar`, `mediad`, `overlay`) and press **Deploy** on the service card in the dashboard. The replaced version is archived in `app/versions/` and can be restored from the card's Versions list.

Runtime settings: `app/config.properties` (Java services), `app/mediad.conf` (mediad: overlay position, crossfade), `app/mediad.sh` and `app/overlay.sh` (launchers). Rollback to the legacy pipeline: `app/rollback.sh` (root).
