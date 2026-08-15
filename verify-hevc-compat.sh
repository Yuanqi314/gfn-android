#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD="$ROOT/build/hevc-compat-check"
rm -rf "$BUILD"
mkdir -p "$BUILD/sdp" "$BUILD/planner" "$BUILD/runtime"

ENGINE="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcEngine.kt"
RUNTIME="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnWebRtcRuntime.kt"
COMPAT="$ROOT/stream-webrtc/src/main/java/dev/gfn/webrtc/GfnHevcNegotiationCompat.kt"
SIGNALING="$ROOT/stream-signaling/src/main/kotlin/dev/gfn/signaling/GfnSignalingProtocol.kt"
CORE="$ROOT/stream-core/src/main/kotlin/dev/gfn/stream/StreamingEngine.kt"
UI="$ROOT/app/src/main/java/dev/gfn/android/ui/GfnAndroidApp.kt"

# v6.0.2 tier-only A/B static guards.
grep -Fq 'getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)' "$RUNTIME" || { echo 'ERROR: receiver capabilities are not queried' >&2; exit 1; }
grep -Fq 'transceiver.setCodecPreferences(plan.orderedCapabilities)' "$ENGINE" || { echo 'ERROR: pre-answer codec preference is not applied' >&2; exit 1; }
grep -Fq 'applyPreAnswerVideoCodecPreference(pc, eventGeneration)' "$ENGINE" || { echo 'ERROR: preference hook missing before createAnswer' >&2; exit 1; }
grep -Fq 'rewriteFirstVideoHevcMainTierFlagForAb(addressFixedOffer)' "$ENGINE" || { echo 'ERROR: HEVC tier-only Offer rewrite is not wired' >&2; exit 1; }
grep -Fq 'GfnHevcCompatLog.tierFlagAbRewrite(eventGeneration, tierAbRewrite)' "$ENGINE" || { echo 'ERROR: tier A/B rewrite result is not logged' >&2; exit 1; }
grep -Fq 'GfnHevcCompatLog.sdp(eventGeneration, "OFFER_TIER_AB", tierAbRewrite.sdp)' "$ENGINE" || { echo 'ERROR: rewritten Offer codec evidence is not logged' >&2; exit 1; }
grep -Fq 'GfnHevcCompatLog.sdp(eventGeneration, "RAW_ANSWER", rawAnswer)' "$ENGINE" || { echo 'ERROR: raw createAnswer SDP is not logged' >&2; exit 1; }
grep -Fq 'GfnHevcCompatLog.sdp(eventGeneration, "FINAL_ANSWER", bounded)' "$ENGINE" || { echo 'ERROR: final SDP is not logged' >&2; exit 1; }
grep -Fq 'const val TAG = "GfnHevcCompat"' "$COMPAT" || { echo 'ERROR: dedicated Logcat tag missing' >&2; exit 1; }
grep -Fq 'phase=OFFER_TIER_AB_REWRITE' "$COMPAT" || { echo 'ERROR: tier A/B Logcat phase missing' >&2; exit 1; }
grep -Fq '"LOCAL_RECEIVER"' "$COMPAT" || { echo 'ERROR: receiver capability Logcat phase missing' >&2; exit 1; }
grep -Fq 'phase=PREFERENCE_APPLY' "$COMPAT" || { echo 'ERROR: preference result Logcat phase missing' >&2; exit 1; }
grep -Fq 'Raw Answer HEVC Main PT' "$UI" || { echo 'ERROR: raw Answer diagnostics UI missing' >&2; exit 1; }
grep -Fq 'Logcat tag" to "GfnHevcCompat"' "$UI" || { echo 'ERROR: Logcat tag is not surfaced in diagnostics' >&2; exit 1; }

# Source-level variable boundary: only tier-flag is rewritten, and only before setRemoteDescription.
python3 - "$ENGINE" "$SIGNALING" <<'PY'
from pathlib import Path
import sys
engine=Path(sys.argv[1]).read_text()
signaling=Path(sys.argv[2]).read_text()
rewrite_call='rewriteFirstVideoHevcMainTierFlagForAb(addressFixedOffer)'
rewrite_i=engine.index(rewrite_call)
set_remote_i=engine.index('pc.setRemoteDescription(', rewrite_i)
assert rewrite_i < set_remote_i, 'tier rewrite must occur before setRemoteDescription'
needle='''flushRemoteIce()\n                    applyPreAnswerVideoCodecPreference(pc, eventGeneration)\n                    createAnswer(pc, fixedOffer, eventGeneration)'''
assert needle in engine, 'preference/createAnswer order changed or rewritten Offer is not carried forward'
start=signaling.index('fun rewriteFirstVideoHevcMainTierFlagForAb(')
end=signaling.index('\n    fun extractIceCredentials(', start)
body=signaling[start:end]
assert 'name = "tier-flag"' in body, 'tier-flag is not the rewritten fmtp field'
assert 'name = "level-id"' not in body, 'level-id rewrite entered the v6.0.2 experiment'
assert '.filter { it.profileId == "1" }' in body, 'rewrite is not limited to HEVC Main/profile-id=1'
assert '.filter { it.tierFlag == fromTierFlag }' in body, 'rewrite is not limited to the source tier value'
assert '103' not in body, 'dynamic GFN PT 103 was hard-coded into the rewrite'
print('V602_TIER_AB_CALL_ORDER=PASS')
print('V602_SINGLE_FIELD_SOURCE_GUARD=PASS')
PY
printf '%s\n' 'V602_HEVC_COMPAT_STATIC_GUARDS=PASS'

# First-video SDP fixture: dynamic PT discovery, Main-only, tier-only, exact invariant preservation.
cat > "$BUILD/sdp/Probe.kt" <<'KT'
import dev.gfn.signaling.GfnSdpTools

fun main() {
    val lf = """
        v=0
        m=video 9 UDP/TLS/RTP/SAVPF 96 97 117 118 120 121 122 123 116
        a=mid:video
        a=rtpmap:96 H264/90000
        a=fmtp:96 profile-level-id=42e01f;packetization-mode=1
        a=rtpmap:97 rtx/90000
        a=fmtp:97 apt=96
        a=rtpmap:117 H265/90000
        a=fmtp:117 level-id=153; profile-id=1;tier-flag=1 ; tx-mode=SRST
        a=rtpmap:118 rtx/90000
        a=fmtp:118 apt=117
        a=rtpmap:120 H265/90000
        a=fmtp:120 profile-id=2;tier-flag=1;level-id=153;tx-mode=SRST
        a=rtpmap:121 rtx/90000
        a=fmtp:121 apt=120
        a=rtpmap:122 H265/90000
        a=fmtp:122 profile-id=1;tier-flag=0;level-id=153;tx-mode=SRST
        a=rtpmap:123 rtx/90000
        a=fmtp:123 apt=122
        a=rtpmap:116 red/90000
        a=rtpmap:119 H265/90000
        a=fmtp:119 profile-id=1;tier-flag=1;level-id=153;tx-mode=SRST
        m=audio 9 UDP/TLS/RTP/SAVPF 111
        a=rtpmap:111 opus/48000/2
        m=video 9 UDP/TLS/RTP/SAVPF 130
        a=mid:second-video
        a=rtpmap:130 H265/90000
        a=fmtp:130 profile-id=1;tier-flag=1;level-id=153;tx-mode=SRST
    """.trimIndent()

    val expectedLf = lf.replace(
        "a=fmtp:117 level-id=153; profile-id=1;tier-flag=1 ; tx-mode=SRST",
        "a=fmtp:117 level-id=153; profile-id=1;tier-flag=0 ; tx-mode=SRST",
    )
    val result = GfnSdpTools.rewriteFirstVideoHevcMainTierFlagForAb(lf)
    check(result.changed)
    check(result.candidatePayloadTypes == listOf(117)) { result.candidatePayloadTypes }
    check(result.rewrittenPayloadTypes == listOf(117)) { result.rewrittenPayloadTypes }
    check(result.sdp == expectedLf) { "unexpected non-tier SDP mutation" }

    val details = GfnSdpTools.firstVideoCodecDetails(result.sdp)
    val main = details.single { it.payloadType == 117 }
    check(main.normalizedName == "H265")
    check(main.profileId == "1")
    check(main.tierFlag == "0")
    check(main.levelId == "153")
    check(main.txMode == "SRST")
    check(main.rtxPayloadTypes == listOf(118))
    val main10 = details.single { it.payloadType == 120 }
    check(main10.profileId == "2" && main10.tierFlag == "1" && main10.levelId == "153")
    val alreadyTier0 = details.single { it.payloadType == 122 }
    check(alreadyTier0.profileId == "1" && alreadyTier0.tierFlag == "0")
    check("a=fmtp:119 profile-id=1;tier-flag=1;level-id=153;tx-mode=SRST" in result.sdp)
    check("a=fmtp:130 profile-id=1;tier-flag=1;level-id=153;tx-mode=SRST" in result.sdp)
    check(GfnSdpTools.firstVideoPayloadOrder(result.sdp) == listOf(96,97,117,118,120,121,122,123,116))
    check(GfnSdpTools.firstVideoRtxAssociations(result.sdp).map { it.payloadType to it.apt } == listOf(97 to 96, 118 to 117, 121 to 120, 123 to 122))

    val idempotent = GfnSdpTools.rewriteFirstVideoHevcMainTierFlagForAb(result.sdp)
    check(!idempotent.changed)
    check(idempotent.sdp == result.sdp)

    val crlf = lf.replace("\n", "\r\n")
    val crlfExpected = expectedLf.replace("\n", "\r\n")
    val crlfResult = GfnSdpTools.rewriteFirstVideoHevcMainTierFlagForAb(crlf)
    check(crlfResult.sdp == crlfExpected)
    check("\r\n" in crlfResult.sdp)

    val trailingCrlf = crlf + "\r\n"
    val trailingCrlfExpected = crlfExpected + "\r\n"
    val trailingCrlfResult = GfnSdpTools.rewriteFirstVideoHevcMainTierFlagForAb(trailingCrlf)
    check(trailingCrlfResult.sdp == trailingCrlfExpected) { "trailing CRLF was not preserved" }

    println("V602_HEVC_TIER_ONLY_SDP_FIXTURE=PASS")
    println("TARGET=pt=${main.payloadType},profile=${main.profileId},tier=${main.tierFlag},level=${main.levelId},tx=${main.txMode},rtx=${main.rtxPayloadTypes}")
}
KT
kotlinc -J-Dfile.encoding=UTF-8 \
  "$ROOT/core-network/src/main/kotlin/dev/gfn/network/Json.kt" \
  "$SIGNALING" "$BUILD/sdp/Probe.kt" -include-runtime -d "$BUILD/sdp/probe.jar"
java -jar "$BUILD/sdp/probe.jar"

# Preference planner + Logcat evidence fixture stays unchanged except for the new tier A/B phase.
mkdir -p "$BUILD/planner/android/util" "$BUILD/planner/org/webrtc" "$BUILD/planner/dev/gfn/stream" "$BUILD/planner/dev/gfn/webrtc"
cat > "$BUILD/planner/android/util/Log.kt" <<'KT'
package android.util
object Log { @JvmStatic fun i(tag:String,msg:String):Int { println("$tag $msg"); return 0 } }
KT
cat > "$BUILD/planner/org/webrtc/RtpCapabilities.kt" <<'KT'
package org.webrtc
class RtpCapabilities { class CodecCapability(var preferredPayloadType:Int=0,var name:String="",var clockRate:Int?=90000,var parameters:Map<String,String>?=emptyMap(),var mimeType:String?=null) }
KT
cat > "$BUILD/planner/dev/gfn/stream/VideoCodecPreference.kt" <<'KT'
package dev.gfn.stream
enum class VideoCodecPreference { H264, Hevc, Av1 }
KT
cat > "$BUILD/planner/dev/gfn/webrtc/Snapshot.kt" <<'KT'
package dev.gfn.webrtc
data class GfnVideoCodecCapabilitySnapshot(val source:String,val index:Int,val preferredPayloadType:Int?=null,val name:String,val mimeType:String?=null,val clockRate:Int?=null,val parameters:Map<String,String> = emptyMap()) { val normalizedName:String get()=if(name.equals("HEVC",true)) "H265" else name.uppercase() }
KT
cat > "$BUILD/planner/Probe.kt" <<'KT'
package dev.gfn.webrtc
import dev.gfn.signaling.HevcTierFlagRewriteResult
import dev.gfn.stream.VideoCodecPreference
import org.webrtc.RtpCapabilities
private fun c(pt:Int,n:String,p:Map<String,String> = emptyMap())=RtpCapabilities.CodecCapability(pt,n,90000,p,"video/$n")
fun main(){
 val caps=listOf(c(120,"H265",mapOf("profile-id" to "2")),c(121,"rtx",mapOf("apt" to "120")),c(101,"H264"),c(111,"H265",mapOf("profile-id" to "1")),c(112,"rtx",mapOf("apt" to "111")),c(113,"red"),c(114,"VP9"),c(115,"H265"))
 val plan=GfnHevcCodecPreferencePlanner.build(caps)
 check(plan.orderedCapabilities.map{it.name.uppercase()}==listOf("H265","H265","H264","RTX","RED")) { plan.orderedLabels }
 check(plan.orderedCapabilities.none{it.parameters.orEmpty()["profile-id"]=="2"})
 check(plan.orderedCapabilities.none{it.preferredPayloadType==121})
 GfnHevcCompatLog.sessionStart(7,VideoCodecPreference.Hevc,listOf(GfnVideoCodecCapabilitySnapshot("DefaultVideoDecoderFactory",0,name="H265",parameters=mapOf("profile-id" to "1"))),listOf(GfnVideoCodecCapabilitySnapshot("PeerConnectionFactory.receiver",0,111,"H265","video/H265",90000,mapOf("profile-id" to "1"))))
 GfnHevcCompatLog.tierFlagAbRewrite(7,HevcTierFlagRewriteResult("",listOf(117),listOf(117),"1","0"))
 GfnHevcCompatLog.preferencePlan(7,plan)
 GfnHevcCompatLog.preferenceApply(7,true,true,"video","fixture")
 println("V602_HEVC_PREFERENCE_PLANNER=PASS")
}
KT
kotlinc -J-Dfile.encoding=UTF-8 \
  "$ROOT/core-network/src/main/kotlin/dev/gfn/network/Json.kt" "$SIGNALING" \
  "$BUILD/planner/android/util/Log.kt" "$BUILD/planner/org/webrtc/RtpCapabilities.kt" \
  "$BUILD/planner/dev/gfn/stream/VideoCodecPreference.kt" "$BUILD/planner/dev/gfn/webrtc/Snapshot.kt" \
  "$COMPAT" "$BUILD/planner/Probe.kt" -include-runtime -d "$BUILD/planner/probe.jar"
java -jar "$BUILD/planner/probe.jar" | tee "$BUILD/planner/logcat-fixture.txt"
grep -Fq 'phase=LOCAL_DECODER' "$BUILD/planner/logcat-fixture.txt"
grep -Fq 'phase=LOCAL_RECEIVER' "$BUILD/planner/logcat-fixture.txt"
grep -Fq 'phase=OFFER_TIER_AB_REWRITE applied=true from=1 to=0 candidates=[117] rewritten=[117]' "$BUILD/planner/logcat-fixture.txt"
grep -Fq 'phase=PREFERENCE_PLAN' "$BUILD/planner/logcat-fixture.txt"
grep -Fq 'phase=PREFERENCE_ITEM' "$BUILD/planner/logcat-fixture.txt"
grep -Fq 'phase=PREFERENCE_APPLY attempted=true applied=true' "$BUILD/planner/logcat-fixture.txt"
echo 'V602_LOGCAT_PHASE_FIXTURE=PASS'

# The full engine API-shaped compile remains in verify-reconnect-engine.sh; this verifier guards
# the v6.0.2 codec-specific behavior deterministically.
echo 'V602_HEVC_COMPAT_VERIFY=PASS'
