package com.moatazvid.core

import kotlin.math.roundToLong

@JvmInline value class TimeUs(val value: Long) : Comparable<TimeUs> {
    init { require(value >= 0) { "Time must be non-negative" } }
    override fun compareTo(other: TimeUs): Int = value.compareTo(other.value)
}

@JvmInline value class DurationUs(val value: Long) {
    init { require(value >= 0) { "Duration must be non-negative" } }
}

data class TimeRangeUs(val start: TimeUs, val endExclusive: TimeUs) {
    init { require(start < endExclusive) { "Range must be non-empty" } }
    val duration: DurationUs get() = DurationUs(endExclusive.value - start.value)
    fun overlaps(other: TimeRangeUs): Boolean = start < other.endExclusive && other.start < endExclusive
    fun contains(time: TimeUs): Boolean = time >= start && time < endExclusive
}

data class Rational(val numerator: Int, val denominator: Int) {
    init { require(numerator > 0 && denominator > 0) }
    fun asDouble(): Double = numerator.toDouble() / denominator

    companion object {
        val FPS_23_976 = Rational(24_000, 1_001)
        val FPS_24 = Rational(24, 1)
        val FPS_25 = Rational(25, 1)
        val FPS_29_97 = Rational(30_000, 1_001)
        val FPS_30 = Rational(30, 1)
        val FPS_50 = Rational(50, 1)
        val FPS_59_94 = Rational(60_000, 1_001)
        val FPS_60 = Rational(60, 1)
    }
}

fun millisecondsToUs(milliseconds: Long): TimeUs = TimeUs(Math.multiplyExact(milliseconds, 1_000L))
fun secondsToUs(seconds: Double): TimeUs = TimeUs((seconds * 1_000_000.0).roundToLong())
