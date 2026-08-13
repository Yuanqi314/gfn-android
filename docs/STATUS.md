# Current status

## Implemented in this baseline

- MD3E Android shell using `MaterialExpressiveTheme` and expressive motion.
- Central Windows/GFN-PC protocol identity.
- Secret-redacting network boundary.
- OAuth/device-flow contracts with no unverified client constants hard-coded.
- Typed CloudMatch identity/session-request model.
- Testable session readiness/orchestration state machine.
- Structured diagnostics model.
- Streaming engine boundary prepared for H.264/HEVC/Main10/HDR.
- Deterministic JVM protocol CLI fixture.

## Deliberately not implemented yet

- Real NVIDIA OAuth HTTP calls.
- Catalog/library endpoints.
- Real CloudMatch create/poll/stop HTTP transport.
- WebRTC signaling/media.
- MediaCodec decoder.
- Controller/audio/input path.
- Main10/HDR negotiation and output.

## Next implementation pass

1. Add redacted protocol fixtures from CloudNow/current official behavior.
2. Implement OAuth device flow behind `AuthApi`.
3. Persist tokens behind Android Keystore-backed `TokenStore`.
4. Add real CloudMatch HTTP adapter and golden parser tests.
5. Connect Login -> Library -> Queue UI to real repositories.
6. Begin H.264 SDR WebRTC bring-up only after session creation is deterministic.
