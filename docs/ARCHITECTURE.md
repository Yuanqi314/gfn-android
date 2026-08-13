# Architecture

The project follows four hard boundaries:

```text
Compose / MD3E UI
        ↓
GFN protocol/session core
        ↓
WebRTC transport/signaling
        ↓
MediaCodec / SurfaceView output
```

No layer is allowed to hide another layer's state. In particular, 10-bit is not treated as HDR.
Diagnostics must keep these dimensions separate:

1. user preference;
2. requested mode;
3. server-negotiated mode;
4. decoded format;
5. final display output mode.

## Module responsibilities

- `core-model`: protocol-neutral domain models.
- `core-network`: transport contract and mandatory secret redaction.
- `gfn-auth`: OAuth/device-flow contracts and secure-token-store boundary.
- `gfn-cloudmatch`: centralized GFN headers and typed session request semantics.
- `gfn-identity`: all server-visible GFN identity values in one place.
- `gfn-session`: lifecycle state machine and CloudMatch boundary.
- `diagnostics`: structured diagnostic state.
- `stream-core`: transport/decoder-independent streaming interfaces.
- `protocol-cli`: deterministic protocol/session harness.
- `app`: Android Compose + Material 3 Expressive shell.

## Next modules

The next implementation pass should add:

- `core-storage`
- `gfn-catalog`
- `stream-webrtc`
- `stream-video-android`
- `stream-audio-android`
- `stream-input`

Real NVIDIA requests must be captured as redacted fixtures and covered by golden tests before wiring them into UI.
