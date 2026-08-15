package dev.gfn.webrtc

import java.nio.ByteBuffer

/** Encapsulation observed on the bytes presented to the Java H265 decoder. */
internal enum class GfnHevcNalPackaging(val logValue: String) {
    AnnexB("ANNEX_B"),
    LengthPrefixed1("LENGTH_PREFIXED_1"),
    LengthPrefixed2("LENGTH_PREFIXED_2"),
    LengthPrefixed3("LENGTH_PREFIXED_3"),
    LengthPrefixed4("LENGTH_PREFIXED_4"),
    SingleNal("SINGLE_NAL"),
    Unknown("UNKNOWN"),
}

/**
 * Minimal SPS evidence needed by v6.1.1. Values are parsed from the elementary-stream SPS itself;
 * SDP profile-id and Android MediaCodec profileLevels are deliberately not substituted here.
 */
internal data class GfnHevcSpsSnapshot(
    val packaging: GfnHevcNalPackaging,
    val generalProfileSpace: Int,
    val generalProfileIdc: Int,
    val generalProfileCompatibilityFlags: Long,
    val generalTierFlag: Int,
    val generalLevelIdc: Int,
    val chromaFormatIdc: Int,
    val codedWidth: Int,
    val codedHeight: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val bitDepthLuma: Int,
    val bitDepthChroma: Int,
) {
    val isTenBit: Boolean
        get() = bitDepthLuma == 10 && bitDepthChroma == 10
}

internal data class GfnHevcBitstreamInspection(
    val packaging: GfnHevcNalPackaging,
    val sps: GfnHevcSpsSnapshot?,
)

/**
 * Read-only HEVC access-unit inspector.
 *
 * It never changes the caller's ByteBuffer position/limit and never writes into the encoded bytes.
 * Annex-B is detected first. If no start code exists, all legal hvcC NAL-length field widths
 * (1..4 bytes) are validated before a length-prefixed interpretation is accepted.
 */
internal object GfnHevcBitstreamParser {
    private const val NAL_TYPE_SPS = 33

    fun inspect(buffer: ByteBuffer): GfnHevcBitstreamInspection {
        val data = buffer.duplicate().slice()
        if (data.remaining() < 2) {
            return GfnHevcBitstreamInspection(GfnHevcNalPackaging.Unknown, null)
        }

        val firstStartCode = findStartCode(data, 0)
        if (firstStartCode != null) {
            return inspectAnnexB(data, firstStartCode)
        }

        for (lengthSize in listOf(4, 3, 2, 1)) {
            inspectLengthPrefixed(data, lengthSize)?.let { return it }
        }

        if (isPlausibleHevcNal(data, 0, data.limit())) {
            val packaging = GfnHevcNalPackaging.SingleNal
            return GfnHevcBitstreamInspection(
                packaging = packaging,
                sps = parseSpsNal(data, 0, data.limit(), packaging),
            )
        }

        return GfnHevcBitstreamInspection(GfnHevcNalPackaging.Unknown, null)
    }

    private data class StartCode(val offset: Int, val length: Int)

    private fun inspectAnnexB(
        data: ByteBuffer,
        firstStartCode: StartCode,
    ): GfnHevcBitstreamInspection {
        var start: StartCode? = firstStartCode
        while (start != null) {
            val nalStart = start.offset + start.length
            val next = findStartCode(data, nalStart)
            val nalEnd = next?.offset ?: data.limit()
            if (isPlausibleHevcNal(data, nalStart, nalEnd)) {
                parseSpsNal(data, nalStart, nalEnd, GfnHevcNalPackaging.AnnexB)?.let { sps ->
                    return GfnHevcBitstreamInspection(GfnHevcNalPackaging.AnnexB, sps)
                }
            }
            start = next
        }
        return GfnHevcBitstreamInspection(GfnHevcNalPackaging.AnnexB, null)
    }

    private fun inspectLengthPrefixed(
        data: ByteBuffer,
        lengthSize: Int,
    ): GfnHevcBitstreamInspection? {
        var offset = 0
        var nalCount = 0
        var parsedSps: GfnHevcSpsSnapshot? = null
        val packaging = when (lengthSize) {
            1 -> GfnHevcNalPackaging.LengthPrefixed1
            2 -> GfnHevcNalPackaging.LengthPrefixed2
            3 -> GfnHevcNalPackaging.LengthPrefixed3
            4 -> GfnHevcNalPackaging.LengthPrefixed4
            else -> return null
        }

        while (offset < data.limit()) {
            if (data.limit() - offset < lengthSize) return null
            var nalLength = 0L
            for (index in 0 until lengthSize) {
                nalLength = (nalLength shl 8) or u8(data.get(offset + index)).toLong()
            }
            if (nalLength < 2L || nalLength > Int.MAX_VALUE.toLong()) return null
            val nalStart = offset + lengthSize
            val nalEndLong = nalStart.toLong() + nalLength
            if (nalEndLong > data.limit().toLong()) return null
            val nalEnd = nalEndLong.toInt()
            if (!isPlausibleHevcNal(data, nalStart, nalEnd)) return null
            if (parsedSps == null) {
                parsedSps = parseSpsNal(data, nalStart, nalEnd, packaging)
            }
            nalCount += 1
            offset = nalEnd
        }

        return if (offset == data.limit() && nalCount > 0) {
            GfnHevcBitstreamInspection(packaging, parsedSps)
        } else {
            null
        }
    }

    private fun parseSpsNal(
        data: ByteBuffer,
        nalStart: Int,
        nalEnd: Int,
        packaging: GfnHevcNalPackaging,
    ): GfnHevcSpsSnapshot? {
        if (!isPlausibleHevcNal(data, nalStart, nalEnd)) return null
        val nalType = (u8(data.get(nalStart)) ushr 1) and 0x3F
        if (nalType != NAL_TYPE_SPS) return null

        val rbsp = ebspToRbsp(data, nalStart + 2, nalEnd)
        val reader = BitReader(rbsp)
        return runCatching {
            reader.readBitsInt(4) // sps_video_parameter_set_id
            val maxSubLayersMinus1 = reader.readBitsInt(3)
            require(maxSubLayersMinus1 in 0..6) { "invalid sps_max_sub_layers_minus1=$maxSubLayersMinus1" }
            reader.skipBits(1) // sps_temporal_id_nesting_flag

            val ptl = parseProfileTierLevel(reader, maxSubLayersMinus1)
            reader.readUnsignedExpGolomb() // sps_seq_parameter_set_id
            val chromaFormatIdc = reader.readUnsignedExpGolomb()
            require(chromaFormatIdc in 0..3) { "invalid chroma_format_idc=$chromaFormatIdc" }
            val separateColourPlane = chromaFormatIdc == 3 && reader.readBit()
            val codedWidth = reader.readUnsignedExpGolomb()
            val codedHeight = reader.readUnsignedExpGolomb()
            require(codedWidth > 0 && codedHeight > 0) { "invalid SPS dimensions ${codedWidth}x$codedHeight" }

            var leftOffset = 0
            var rightOffset = 0
            var topOffset = 0
            var bottomOffset = 0
            if (reader.readBit()) { // conformance_window_flag
                leftOffset = reader.readUnsignedExpGolomb()
                rightOffset = reader.readUnsignedExpGolomb()
                topOffset = reader.readUnsignedExpGolomb()
                bottomOffset = reader.readUnsignedExpGolomb()
            }

            val bitDepthLuma = reader.readUnsignedExpGolomb() + 8
            val bitDepthChroma = reader.readUnsignedExpGolomb() + 8
            require(bitDepthLuma in 8..16 && bitDepthChroma in 8..16) {
                "invalid SPS bit depth luma=$bitDepthLuma chroma=$bitDepthChroma"
            }

            val chromaArrayType = if (separateColourPlane) 0 else chromaFormatIdc
            val subWidthC = when (chromaArrayType) {
                1, 2 -> 2
                else -> 1
            }
            val subHeightC = when (chromaArrayType) {
                1 -> 2
                else -> 1
            }
            val displayWidth = codedWidth - (leftOffset + rightOffset) * subWidthC
            val displayHeight = codedHeight - (topOffset + bottomOffset) * subHeightC
            require(displayWidth > 0 && displayHeight > 0) {
                "invalid cropped SPS dimensions ${displayWidth}x$displayHeight"
            }

            GfnHevcSpsSnapshot(
                packaging = packaging,
                generalProfileSpace = ptl.profileSpace,
                generalProfileIdc = ptl.profileIdc,
                generalProfileCompatibilityFlags = ptl.profileCompatibilityFlags,
                generalTierFlag = ptl.tierFlag,
                generalLevelIdc = ptl.levelIdc,
                chromaFormatIdc = chromaFormatIdc,
                codedWidth = codedWidth,
                codedHeight = codedHeight,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                bitDepthLuma = bitDepthLuma,
                bitDepthChroma = bitDepthChroma,
            )
        }.getOrNull()
    }

    private data class ProfileTierLevel(
        val profileSpace: Int,
        val tierFlag: Int,
        val profileIdc: Int,
        val profileCompatibilityFlags: Long,
        val levelIdc: Int,
    )

    /** Mirrors the field widths consumed by the pinned WebRTC M144 H265SpsParser. */
    private fun parseProfileTierLevel(reader: BitReader, maxSubLayersMinus1: Int): ProfileTierLevel {
        val profileSpace = reader.readBitsInt(2)
        val tierFlag = reader.readBitsInt(1)
        val profileIdc = reader.readBitsInt(5)
        val profileCompatibilityFlags = reader.readBitsLong(32)
        reader.skipBits(1) // general_progressive_source_flag
        reader.skipBits(1) // general_interlaced_source_flag
        reader.skipBits(1) // general_non_packed_constraint_flag
        reader.skipBits(1) // general_frame_only_constraint_flag
        reader.skipBits(7) // general_reserved_zero_7bits
        reader.skipBits(1) // general_one_picture_only_constraint_flag
        reader.skipBits(35) // general_reserved_zero_35bits
        reader.skipBits(1) // general_inbld_flag
        val levelIdc = reader.readBitsInt(8)

        val subLayerProfilePresent = BooleanArray(maxSubLayersMinus1)
        val subLayerLevelPresent = BooleanArray(maxSubLayersMinus1)
        for (index in 0 until maxSubLayersMinus1) {
            subLayerProfilePresent[index] = reader.readBit()
            subLayerLevelPresent[index] = reader.readBit()
        }
        if (maxSubLayersMinus1 > 0) {
            for (index in maxSubLayersMinus1 until 8) {
                reader.skipBits(2)
            }
        }
        for (index in 0 until maxSubLayersMinus1) {
            if (subLayerProfilePresent[index]) {
                reader.skipBits(2) // sub_layer_profile_space
                reader.skipBits(1) // sub_layer_tier_flag
                reader.skipBits(5) // sub_layer_profile_idc
                reader.skipBits(32) // sub_layer_profile_compatibility_flags
                reader.skipBits(2) // progressive/interlaced source flags
                reader.skipBits(2) // non-packed/frame-only constraint flags
                reader.skipBits(43) // profile-specific compatibility/constraint flags
                reader.skipBits(1) // sub_layer_inbld_flag
            }
            if (subLayerLevelPresent[index]) {
                reader.skipBits(8)
            }
        }

        return ProfileTierLevel(
            profileSpace = profileSpace,
            tierFlag = tierFlag,
            profileIdc = profileIdc,
            profileCompatibilityFlags = profileCompatibilityFlags,
            levelIdc = levelIdc,
        )
    }

    private fun ebspToRbsp(data: ByteBuffer, start: Int, end: Int): ByteArray {
        val out = ByteArray((end - start).coerceAtLeast(0))
        var outSize = 0
        var zeroCount = 0
        for (index in start until end) {
            val value = u8(data.get(index))
            if (zeroCount >= 2 && value == 0x03) {
                zeroCount = 0
                continue
            }
            out[outSize++] = value.toByte()
            zeroCount = if (value == 0) zeroCount + 1 else 0
        }
        return out.copyOf(outSize)
    }

    private fun isPlausibleHevcNal(data: ByteBuffer, start: Int, end: Int): Boolean {
        if (start < 0 || end > data.limit() || end - start < 2) return false
        val first = u8(data.get(start))
        val second = u8(data.get(start + 1))
        val forbiddenZeroBit = (first ushr 7) and 0x01
        val temporalIdPlus1 = second and 0x07
        return forbiddenZeroBit == 0 && temporalIdPlus1 != 0
    }

    private fun findStartCode(data: ByteBuffer, from: Int): StartCode? {
        var index = from.coerceAtLeast(0)
        while (index + 2 < data.limit()) {
            if (u8(data.get(index)) == 0 && u8(data.get(index + 1)) == 0) {
                if (u8(data.get(index + 2)) == 1) {
                    return StartCode(index, 3)
                }
                if (index + 3 < data.limit() &&
                    u8(data.get(index + 2)) == 0 &&
                    u8(data.get(index + 3)) == 1
                ) {
                    return StartCode(index, 4)
                }
            }
            index += 1
        }
        return null
    }

    private fun u8(value: Byte): Int = value.toInt() and 0xFF

    private class BitReader(private val data: ByteArray) {
        private var bitOffset: Int = 0

        fun readBit(): Boolean = readBitsInt(1) != 0

        fun readBitsInt(count: Int): Int = readBitsLong(count).toInt()

        fun readBitsLong(count: Int): Long {
            require(count in 0..63) { "invalid bit count=$count" }
            require(bitsRemaining() >= count) { "HEVC SPS ended with $count bits requested" }
            var value = 0L
            repeat(count) {
                val byteIndex = bitOffset ushr 3
                val bitInByte = 7 - (bitOffset and 7)
                value = (value shl 1) or ((data[byteIndex].toInt() ushr bitInByte) and 1).toLong()
                bitOffset += 1
            }
            return value
        }

        fun skipBits(count: Int) {
            require(count >= 0 && bitsRemaining() >= count) { "HEVC SPS skip beyond end: $count" }
            bitOffset += count
        }

        fun readUnsignedExpGolomb(): Int {
            var leadingZeroBits = 0
            while (!readBit()) {
                leadingZeroBits += 1
                require(leadingZeroBits <= 31) { "HEVC ue(v) too large" }
            }
            if (leadingZeroBits == 0) return 0
            val suffix = readBitsLong(leadingZeroBits)
            val value = ((1L shl leadingZeroBits) - 1L) + suffix
            require(value <= Int.MAX_VALUE.toLong()) { "HEVC ue(v) exceeds Int" }
            return value.toInt()
        }

        private fun bitsRemaining(): Int = data.size * 8 - bitOffset
    }
}

/**
 * Bounded observer. It stops inspecting after the first SPS or after [maxFrames] decoder inputs.
 * This keeps the forensic hook read-only and cheap during a long game stream.
 */
internal class GfnHevcBitstreamProbe(
    private val maxFrames: Int = 180,
) {
    data class Observation(
        val frameNumber: Int,
        val packaging: GfnHevcNalPackaging,
        val sps: GfnHevcSpsSnapshot?,
        val exhausted: Boolean,
    )

    private var completed = false
    private var framesObserved = 0
    private var lastPackaging = GfnHevcNalPackaging.Unknown

    fun reset() {
        completed = false
        framesObserved = 0
        lastPackaging = GfnHevcNalPackaging.Unknown
    }

    fun observe(buffer: ByteBuffer): Observation? {
        if (completed) return null
        framesObserved += 1
        val inspection = GfnHevcBitstreamParser.inspect(buffer)
        if (inspection.packaging != GfnHevcNalPackaging.Unknown) {
            lastPackaging = inspection.packaging
        }
        inspection.sps?.let { sps ->
            completed = true
            return Observation(
                frameNumber = framesObserved,
                packaging = inspection.packaging,
                sps = sps,
                exhausted = false,
            )
        }
        if (framesObserved >= maxFrames) {
            completed = true
            return Observation(
                frameNumber = framesObserved,
                packaging = lastPackaging,
                sps = null,
                exhausted = true,
            )
        }
        return null
    }
}
