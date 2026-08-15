#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# Kept as a compatibility entry point for older local workflows; v6.0.4 production verification
# replaces the removed v6.0.2/v6.0.3 tier-rewrite experiment.
exec sh "$ROOT/verify-hevc-production.sh"
