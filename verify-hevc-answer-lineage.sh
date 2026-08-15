#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$ROOT/build/hevc-answer-lineage-check"
rm -rf "$BUILD"
mkdir -p "$BUILD"
SIGNALING="$ROOT/stream-signaling/src/main/kotlin/dev/gfn/signaling/GfnSignalingProtocol.kt"
ENGINE="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt"
CORE="$ROOT/stream-core/src/main/kotlin/dev/gfn/stream/StreamingEngine.kt"
UI="$ROOT/app/src/main/java/dev/gfn/android/ui/GfnAndroidApp.kt"
COMPAT="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnHevcNegotiationCompat.kt"

grep -Fq 'matchingAnswerHevcMainPayloadTypes(offerSdp, rawAnswer)' "$ENGINE" || { echo 'ERROR: raw Answer HEVC Main lineage is not evaluated' >&2; exit 1; }
grep -Fq 'allowedPrimaryPayloadTypes = hevcMainMatchedPayloadTypes.toSet()' "$ENGINE" || { echo 'ERROR: Answer filtering is not constrained by matched Offer Main payloads' >&2; exit 1; }
grep -Fq 'VideoCodecPreference.Hevc -> finalAnswerHevcMainMatched.isNotEmpty()' "$ENGINE" || { echo 'ERROR: final HEVC validation does not use Offer/Answer lineage' >&2; exit 1; }
grep -Fq 'hevcMainMatchedPayloadTypes: List<Int>' "$CORE" || { echo 'ERROR: lineage diagnostics field missing' >&2; exit 1; }
grep -Fq 'Raw Answer HEVC Main matched PT' "$UI" || { echo 'ERROR: raw Answer lineage diagnostics missing' >&2; exit 1; }
grep -Fq 'phase=ANSWER_HEVC_MAIN_LINEAGE' "$COMPAT" || { echo 'ERROR: HEVC Main lineage log phase missing' >&2; exit 1; }

python3 - "$SIGNALING" "$ENGINE" <<'PY'
from pathlib import Path
import sys
signaling=Path(sys.argv[1]).read_text()
engine=Path(sys.argv[2]).read_text()
start=signaling.index('fun matchingAnswerHevcMainPayloadTypes(')
end=signaling.index('\n    /**\n     * Converge the first video Answer', start)
body=signaling[start:end]
assert '.filter { it.normalizedName == "H265" && it.profileId == "1" }' in body
assert '.filter { it.normalizedName == "H265" }' in body
assert '103' not in body and '107' not in body, 'dynamic GFN payload type was hard-coded'
assert 'profileId == "2"' not in body, 'Main10 must not be accepted as HEVC Main'
select_start=engine.index('private fun selectVideoCodecInAnswer(')
select_end=engine.index('\n    private fun createAnswer(', select_start)
select_body=engine[select_start:select_end]
assert 'preferredHevcProfileId = 1' not in select_body, 'generic libwebrtc Answer would still be rejected by explicit fmtp-only filtering'
assert 'hevcMainMatchedPayloadTypes.isNotEmpty()' in select_body
print('V603_HEVC_LINEAGE_SOURCE_GUARD=PASS')
PY

cat > "$BUILD/Probe.kt" <<'KT'
import dev.gfn.signaling.GfnSdpTools

fun main() {
    val offer = """
        v=0
        m=video 9 UDP/TLS/RTP/SAVPF 107 108 103 104 101 102 98
        a=mid:1
        a=rtpmap:107 H265/90000
        a=fmtp:107 level-id=153;profile-id=2;tier-flag=1
        a=rtpmap:108 rtx/90000
        a=fmtp:108 apt=107;rtx-time=125
        a=rtpmap:103 H265/90000
        a=fmtp:103 level-id=153;profile-id=1;tier-flag=0
        a=rtpmap:104 rtx/90000
        a=fmtp:104 apt=103;rtx-time=125
        a=rtpmap:101 H264/90000
        a=fmtp:101 profile-level-id=42e01f;packetization-mode=1
        a=rtpmap:102 rtx/90000
        a=fmtp:102 apt=101;rtx-time=125
        a=rtpmap:98 flexfec-03/90000
        a=fmtp:98 repair-window=10000000
    """.trimIndent()

    val rawAnswer = """
        v=0
        m=video 9 UDP/TLS/RTP/SAVPF 103 104 101 102 98
        a=mid:1
        a=rtpmap:103 H265/90000
        a=fmtp:103 level-id=93
        a=rtpmap:104 rtx/90000
        a=fmtp:104 apt=103;rtx-time=125
        a=rtpmap:101 H264/90000
        a=fmtp:101 profile-level-id=42e01f;packetization-mode=1
        a=rtpmap:102 rtx/90000
        a=fmtp:102 apt=101;rtx-time=125
        a=rtpmap:98 flexfec-03/90000
        a=fmtp:98 repair-window=10000000
    """.trimIndent()

    val rawSummary = GfnSdpTools.summarize(rawAnswer, isOffer = false)
    check(rawSummary.hevcPayloadTypes == listOf(103))
    check(rawSummary.hevcMainPayloadTypes.isEmpty())

    val matched = GfnSdpTools.matchingAnswerHevcMainPayloadTypes(offer, rawAnswer)
    check(matched == listOf(103)) { matched }

    val hevcOnly = GfnSdpTools.preferVideoCodecInAnswer(
        rawAnswer,
        codec = "H265",
        allowedPrimaryPayloadTypes = matched.toSet(),
    )
    check("m=video 9 UDP/TLS/RTP/SAVPF 103 104 98" in hevcOnly) { hevcOnly }
    check("a=rtpmap:103 H265/90000" in hevcOnly)
    check("a=fmtp:103 level-id=93" in hevcOnly)
    check("a=rtpmap:104 rtx/90000" in hevcOnly)
    check("a=rtpmap:101 H264/90000" !in hevcOnly)
    check("a=rtpmap:102 rtx/90000" !in hevcOnly)

    val main10OnlyAnswer = rawAnswer
        .replace("103 104 101 102 98", "107 108 101 102 98")
        .replace("a=rtpmap:103 H265/90000", "a=rtpmap:107 H265/90000")
        .replace("a=fmtp:103 level-id=93", "a=fmtp:107 level-id=93")
        .replace("a=rtpmap:104 rtx/90000", "a=rtpmap:108 rtx/90000")
        .replace("a=fmtp:104 apt=103;rtx-time=125", "a=fmtp:108 apt=107;rtx-time=125")
    check(GfnSdpTools.matchingAnswerHevcMainPayloadTypes(offer, main10OnlyAnswer).isEmpty())

    val unrelatedPtAnswer = rawAnswer
        .replace("103 104 101 102 98", "117 118 101 102 98")
        .replace("a=rtpmap:103 H265/90000", "a=rtpmap:117 H265/90000")
        .replace("a=fmtp:103 level-id=93", "a=fmtp:117 level-id=93")
        .replace("a=rtpmap:104 rtx/90000", "a=rtpmap:118 rtx/90000")
        .replace("a=fmtp:104 apt=103;rtx-time=125", "a=fmtp:118 apt=117;rtx-time=125")
    check(GfnSdpTools.matchingAnswerHevcMainPayloadTypes(offer, unrelatedPtAnswer).isEmpty())

    println("V603_HEVC_ANSWER_LINEAGE_FIXTURE=PASS")
    println("MATCHED=$matched")
}
KT

kotlinc -J-Dfile.encoding=UTF-8 \
  "$ROOT/core-network/src/main/kotlin/dev/gfn/network/Json.kt" \
  "$SIGNALING" "$BUILD/Probe.kt" -include-runtime -d "$BUILD/probe.jar"
java -jar "$BUILD/probe.jar"

echo 'V603_HEVC_ANSWER_LINEAGE_VERIFY=PASS'
