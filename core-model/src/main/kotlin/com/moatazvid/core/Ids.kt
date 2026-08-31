package com.moatazvid.core

import java.security.SecureRandom
import java.time.Clock

@JvmInline value class ProjectId(val value: String)
@JvmInline value class SourceId(val value: String)
@JvmInline value class StreamId(val value: String)
@JvmInline value class SequenceId(val value: String)
@JvmInline value class TrackId(val value: String)
@JvmInline value class ClipId(val value: String)
@JvmInline value class AssetId(val value: String)
@JvmInline value class TransactionId(val value: String)
@JvmInline value class EditPlanId(val value: String)
@JvmInline value class ConstraintId(val value: String)

enum class IdKind(val prefix: String) {
    PROJECT("prj"), SOURCE("src"), STREAM("stm"), SEQUENCE("seq"), TRACK("trk"),
    CLIP("clp"), ASSET("ast"), TRANSACTION("txn"), EDIT_PLAN("pln"), CONSTRAINT("cst")
}

interface IdGenerator {
    fun newId(kind: IdKind): String
}

/** Monotonic-enough, sortable ID without relying on filename, path, or list position. */
class UlidIdGenerator(
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) : IdGenerator {
    private val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    override fun newId(kind: IdKind): String {
        val bytes = ByteArray(16)
        val millis = clock.millis()
        for (i in 0 until 6) bytes[i] = (millis ushr (40 - i * 8)).toByte()
        random.nextBytes(bytes, 6, 10)
        return "${kind.prefix}_${encodeBase32(bytes)}"
    }

    private fun SecureRandom.nextBytes(target: ByteArray, offset: Int, length: Int) {
        val randomPart = ByteArray(length)
        nextBytes(randomPart)
        randomPart.copyInto(target, offset)
    }

    private fun encodeBase32(bytes: ByteArray): String {
        var buffer = 0
        var bits = 0
        val result = StringBuilder(26)
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                result.append(alphabet[(buffer shr bits) and 31])
            }
        }
        if (bits > 0) result.append(alphabet[(buffer shl (5 - bits)) and 31])
        return result.toString().padEnd(26, '0').take(26)
    }
}

