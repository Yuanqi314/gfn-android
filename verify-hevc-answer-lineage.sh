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

grep -Fq 'matchingAnswerHevcProfilePayloadTypes(offerSdp, rawAnswer, targetProfile)' "$ENGINE" || { echo 'ERROR: raw Answer HEVC target-profile lineage is not evaluated' >&2; exit 1; }
grep -Fq 'allowedPrimaryPayloadTypes = hevcTargetMatchedPayloadTypes.toSet()' "$ENGINE" || { echo 'ERROR: Answer filtering is not constrained by target-profile lineage' >&2; exit 1; }
grep -Fq 'VideoCodecPreference.Hevc -> finalAnswerHevcTargetMatched.isNotEmpty()' "$ENGINE" || { echo 'ERROR: final HEVC validation does not use target-profile Offer/Answer lineage' >&2; exit 1; }
grep -Fq 'hevcTargetMatchedPayloadTypes: List<Int>' "$CORE" || { echo 'ERROR: target-profile lineage diagnostics field missing' >&2; exit 1; }
grep -Fq 'Raw Answer HEVC target matched PT' "$UI" || { echo 'ERROR: raw Answer target lineage diagnostics missing' >&2; exit 1; }
grep -Fq 'ANSWER_HEVC_MAIN10_LINEAGE' "$COMPAT" || { echo 'ERROR: HEVC Main10 lineage log phase missing' >&2; exit 1; }

python3 - "$SIGNALING" <<'PY'
from pathlib import Path
import sys
signaling=Path(sys.argv[1]).read_text()
start=signaling.index('fun matchingAnswerHevcProfilePayloadTypes(')
end=signaling.index('\n    fun matchingAnswerHevcMainPayloadTypes', start)
body=signaling[start:end]
assert 'it.profileId == profileId' in body
assert '.filter { it.normalizedName == "H265" }' in body
assert '103' not in body and '107' not in body, 'dynamic payload type was hard-coded'
assert 'profileId = "1"' in signaling and 'profileId = "2"' in signaling
print('V604_HEVC_LINEAGE_SOURCE_GUARD=PASS')
print('V610_MAIN10_LINEAGE_SOURCE_GUARD=PASS')
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
        a=fmtp:103 level-id=153;profile-id=1;tier-flag=1
        a=rtpmap:104 rtx/90000
        a=fmtp:104 apt=103;rtx-time=125
        a=rtpmap:101 H264/90000
        a=fmtp:101 profile-level-id=42e01f;packetization-mode=1
        a=rtpmap:102 rtx/90000
        a=fmtp:102 apt=101;rtx-time=125
        a=rtpmap:98 flexfec-03/90000
        a=fmtp:98 repair-window=10000000
    """.trimIndent()

    val genericMainAnswer = """
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

    val genericMain10Answer = genericMainAnswer
        .replace("103 104 101 102 98", "107 108 101 102 98")
        .replace("a=rtpmap:103 H265/90000", "a=rtpmap:107 H265/90000")
        .replace("a=fmtp:103 level-id=93", "a=fmtp:107 level-id=93")
        .replace("a=rtpmap:104 rtx/90000", "a=rtpmap:108 rtx/90000")
        .replace("a=fmtp:104 apt=103;rtx-time=125", "a=fmtp:108 apt=107;rtx-time=125")

    val offerSummary = GfnSdpTools.summarize(offer, isOffer = true)
    check(offerSummary.hevcMainPayloadTypes == listOf(103))
    check(offerSummary.hevcMain10PayloadTypes == listOf(107))

    val main = GfnSdpTools.matchingAnswerHevcMainPayloadTypes(offer, genericMainAnswer)
    val main10 = GfnSdpTools.matchingAnswerHevcMain10PayloadTypes(offer, genericMain10Answer)
    check(main == listOf(103)) { main }
    check(main10 == listOf(107)) { main10 }
    check(GfnSdpTools.matchingAnswerHevcMainPayloadTypes(offer, genericMain10Answer).isEmpty())
    check(GfnSdpTools.matchingAnswerHevcMain10PayloadTypes(offer, genericMainAnswer).isEmpty())

    val mainOnly = GfnSdpTools.preferVideoCodecInAnswer(
        genericMainAnswer, "H265", allowedPrimaryPayloadTypes = main.toSet(),
    )
    val main10Only = GfnSdpTools.preferVideoCodecInAnswer(
        genericMain10Answer, "H265", allowedPrimaryPayloadTypes = main10.toSet(),
    )
    check("m=video 9 UDP/TLS/RTP/SAVPF 103 104 98" in mainOnly)
    check("m=video 9 UDP/TLS/RTP/SAVPF 107 108 98" in main10Only)
    check("a=rtpmap:101 H264/90000" !in mainOnly)
    check("a=rtpmap:101 H264/90000" !in main10Only)

    println("V604_HEVC_ANSWER_LINEAGE_FIXTURE=PASS")
    println("V610_MAIN10_ANSWER_LINEAGE_FIXTURE=PASS")
    println("MATCHED_MAIN=$main MATCHED_MAIN10=$main10")
}
KT

kotlinc -J-Dfile.encoding=UTF-8 \
  "$ROOT/core-network/src/main/kotlin/dev/gfn/network/Json.kt" \
  "$SIGNALING" "$BUILD/Probe.kt" -include-runtime -d "$BUILD/probe.jar"
java -jar "$BUILD/probe.jar"

echo 'V604_HEVC_ANSWER_LINEAGE_VERIFY=PASS'
echo 'V610_MAIN10_ANSWER_LINEAGE_VERIFY=PASS'
