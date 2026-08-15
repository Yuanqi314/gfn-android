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

# v6.0.1 single-variable static guards.
grep -Fq 'getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)' "$RUNTIME" || { echo 'ERROR: receiver capabilities are not queried' >&2; exit 1; }
grep -Fq 'transceiver.setCodecPreferences(plan.orderedCapabilities)' "$ENGINE" || { echo 'ERROR: pre-answer codec preference is not applied' >&2; exit 1; }
grep -Fq 'applyPreAnswerVideoCodecPreference(pc, eventGeneration)' "$ENGINE" || { echo 'ERROR: preference hook missing before createAnswer' >&2; exit 1; }
grep -Fq 'GfnHevcCompatLog.sdp(eventGeneration, "RAW_ANSWER", rawAnswer)' "$ENGINE" || { echo 'ERROR: raw createAnswer SDP is not logged' >&2; exit 1; }
grep -Fq 'GfnHevcCompatLog.sdp(eventGeneration, "FINAL_ANSWER", bounded)' "$ENGINE" || { echo 'ERROR: final SDP is not logged' >&2; exit 1; }
grep -Fq 'const val TAG = "GfnHevcCompat"' "$COMPAT" || { echo 'ERROR: dedicated Logcat tag missing' >&2; exit 1; }
grep -Fq '"LOCAL_RECEIVER"' "$COMPAT" || { echo 'ERROR: receiver capability Logcat phase missing' >&2; exit 1; }
grep -Fq 'phase=PREFERENCE_APPLY' "$COMPAT" || { echo 'ERROR: preference result Logcat phase missing' >&2; exit 1; }
grep -Fq 'Raw Answer HEVC Main PT' "$UI" || { echo 'ERROR: raw Answer diagnostics UI missing' >&2; exit 1; }
grep -Fq 'Logcat tag" to "GfnHevcCompat"' "$UI" || { echo 'ERROR: Logcat tag is not surfaced in diagnostics' >&2; exit 1; }
# No compatibility rewrite is allowed in this first experiment; tier/level are evidence only.
if grep -E 'replace.*tier-flag|replace.*level-id|rewriteH265Tier|rewriteH265Level' "$ENGINE" "$COMPAT" "$SIGNALING" >/dev/null 2>&1; then
  echo 'ERROR: v6.0.1 preference experiment must not rewrite tier/level' >&2; exit 1
fi
# Call-order guard: preference must occur before the immediate createAnswer call in setRemoteDescription success.
python3 - "$ENGINE" <<'PY'
from pathlib import Path
import sys
s=Path(sys.argv[1]).read_text()
needle='''flushRemoteIce()\n                    applyPreAnswerVideoCodecPreference(pc, eventGeneration)\n                    createAnswer(pc, fixedOffer, eventGeneration)'''
assert needle in s, 'preference/createAnswer order changed'
print('V601_PREANSWER_CALL_ORDER=PASS')
PY
printf '%s\n' 'V601_HEVC_COMPAT_STATIC_GUARDS=PASS'

# Detailed first-video SDP evidence parser. Dynamic PT values are inspected, never matched to local PT values.
cat > "$BUILD/sdp/Probe.kt" <<'KT'
import dev.gfn.signaling.GfnSdpTools
fun main() {
    val sdp = """
        v=0
        m=video 9 UDP/TLS/RTP/SAVPF 96 97 102 103 104 105 116
        a=mid:video
        a=rtpmap:96 H264/90000
        a=fmtp:96 profile-level-id=42e01f;packetization-mode=1
        a=rtpmap:97 rtx/90000
        a=fmtp:97 apt=96
        a=rtpmap:102 H265/90000
        a=fmtp:102 profile-id=1;tier-flag=0;level-id=120;tx-mode=SRST
        a=rtpmap:103 rtx/90000
        a=fmtp:103 apt=102
        a=rtpmap:104 H265/90000
        a=fmtp:104 profile-id=2;tier-flag=1;level-id=153
        a=rtpmap:105 rtx/90000
        a=fmtp:105 apt=104
        a=rtpmap:116 red/90000
        m=audio 9 UDP/TLS/RTP/SAVPF 111
        a=rtpmap:111 opus/48000/2
    """.trimIndent()
    check(GfnSdpTools.firstVideoPayloadOrder(sdp) == listOf(96,97,102,103,104,105,116))
    val details = GfnSdpTools.firstVideoCodecDetails(sdp)
    val main = details.single { it.payloadType == 102 }
    check(main.normalizedName == "H265")
    check(main.profileId == "1")
    check(main.tierFlag == "0")
    check(main.levelId == "120")
    check(main.txMode == "SRST")
    check(main.rtxPayloadTypes == listOf(103))
    val main10 = details.single { it.payloadType == 104 }
    check(main10.profileId == "2" && main10.rtxPayloadTypes == listOf(105))
    check(GfnSdpTools.firstVideoRtxAssociations(sdp).map { it.payloadType to it.apt } == listOf(97 to 96, 103 to 102, 105 to 104))
    println("V601_HEVC_SDP_DETAIL_FIXTURE=PASS")
    println("MAIN=pt=${main.payloadType},profile=${main.profileId},tier=${main.tierFlag},level=${main.levelId},tx=${main.txMode},rtx=${main.rtxPayloadTypes}")
}
KT
kotlinc -J-Dfile.encoding=UTF-8 \
  "$ROOT/core-network/src/main/kotlin/dev/gfn/network/Json.kt" \
  "$SIGNALING" "$BUILD/sdp/Probe.kt" -include-runtime -d "$BUILD/sdp/probe.jar"
java -jar "$BUILD/sdp/probe.jar"

# Preference planner + Logcat evidence fixture.
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
 GfnHevcCompatLog.preferencePlan(7,plan)
 GfnHevcCompatLog.preferenceApply(7,true,true,"video","fixture")
 println("V601_HEVC_PREFERENCE_PLANNER=PASS")
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
grep -Fq 'phase=PREFERENCE_PLAN' "$BUILD/planner/logcat-fixture.txt"
grep -Fq 'phase=PREFERENCE_ITEM' "$BUILD/planner/logcat-fixture.txt"
grep -Fq 'phase=PREFERENCE_APPLY attempted=true applied=true' "$BUILD/planner/logcat-fixture.txt"
echo 'V601_LOGCAT_PHASE_FIXTURE=PASS'

# The full engine API-shaped compile is intentionally kept in verify-reconnect-engine.sh because
# it carries the large Android/WebRTC stub surface shared by reconnect + input lifecycle checks.
# This lightweight verifier guards the v6.0.1 codec-specific behavior deterministically.
echo 'V601_HEVC_COMPAT_VERIFY=PASS'
