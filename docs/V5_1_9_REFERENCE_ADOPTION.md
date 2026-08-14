# v5.1.9 Reference Adoption

## Evidence order

```text
NVIDIA official behavior
CloudNow + OpenNOW cross-reference
gfn-android fixture / wire
true-device A/B
```

## Adopted

- Session-level `keyboardLayout` remains a first-class launch setting.
- Default explicit layout remains `en-US` because it is the true-device verified fix for the reproduced Cyberpunk 2077 issue.
- Normal production key encoding returns to VK + Windows Set-1 scan.

## Not adopted

OpenNOW-only keyboard semantics are not carried into production because the same fault remained after the C3 full-path probe:

```text
scan=0
INPUT_LOCK_KEYS_SYNC type19
CapsLock synthetic VK_LSHIFT
```

CloudNow's absence of those behaviors independently demonstrates that they are not universal GFN protocol requirements.

## Project-specific verdict

The project does not claim CloudNow's Set-1 path is the only correct GFN implementation. It is selected as the stable baseline because:

1. it matches the original gfn-android mapping architecture;
2. it removes all investigation-only transforms;
3. the actual Cyberpunk fault was fixed by the independent Session keyboardLayout variable;
4. Set-1 packet bytes are covered by a standalone deterministic fixture.
