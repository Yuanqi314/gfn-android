# v5.2.1 Reference Adoption

## CloudNow witness

Observed behavior at the inspected reference commit:

- bounded reconnect attempts;
- old input/signaling/PeerConnection/DataChannels are torn down;
- caller callback reclaims the existing Session with `claimSession`;
- reclaimed Session information is used to establish new signaling/WebRTC;
- reconnect does not require a new CloudMatch Session.

CloudNow's retry delays are not copied as protocol constants.

## OpenNOW witness

Observed behavior at the inspected reference commit:

- recovery is limited to an already streaming state;
- transient ICE disconnect has a grace period;
- recovery queries active Sessions, selects a recovery candidate, and claims it;
- old WebRTC/signaling is disposed before connecting the claimed Session;
- input bridge state is reset for the new connection;
- recovery does not create a replacement Session.

OpenNOW currently rebuilds stream settings during recovery. gfn-android intentionally does **not** copy that behavior because this project's v5.2 Session Snapshot rule requires CREATE / CLAIM / WebRTC / Reconnect to share the same immutable `ResolvedLaunchProfile`.

## Adopted common behavior

```text
same running Session
        ↓
claim/resume
        ↓
new connection info
        ↓
new signaling + PeerConnection + DataChannels
```

## Not treated as specification

- exact retry count;
- exact retry delays;
- whether an active-session list lookup is mandatory before claim;
- reference-specific UI states;
- reference-specific settings refresh policy.

Those remain implementation choices unless NVIDIA behavior or this project's wire/true-device evidence proves otherwise.
