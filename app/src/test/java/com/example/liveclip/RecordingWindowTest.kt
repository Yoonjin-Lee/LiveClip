package com.example.liveclip

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingWindowTest {
    @Test
    fun irregularVideoFramesKeepOnlyLatestSixtySecondsIncludingBoundary() {
        val times = mutableListOf(1_000_000L, 39_999_999L, 40_000_000L, 72_000_000L, 100_000_000L)
        trimToRecentWindow(times, 100_000_000L) { it }
        assertEquals(listOf(40_000_000L, 72_000_000L, 100_000_000L), times)
    }

    @Test
    fun clipAudioUsingVideoKeyFrameAndVideoEndRatherThanItsOwnStart() {
        val audioTimes = listOf(40_000_000L, 41_990_000L, 42_010_000L, 99_990_000L, 100_010_000L)
        val videoKeyFrameUs = 42_000_000L
        val audioClip = samplesInClip(audioTimes, videoKeyFrameUs, 100_000_000L) { it }
        assertEquals(listOf(42_010_000L, 99_990_000L), audioClip)
        // 오디오 자체의 시작 대신 영상 기준을 빼서 10ms의 차이를 유지합니다.
        assertEquals(10_000L, audioClip.first() - videoKeyFrameUs)
    }

    @Test
    fun shortRecordingDoesNotDiscardSamples() {
        val times = mutableListOf(500_000L, 1_000_000L)
        trimToRecentWindow(times, 1_000_000L) { it }
        assertEquals(listOf(500_000L, 1_000_000L), times)
    }

    @Test
    fun audioOutsideVideoWindowIsNotWritten() {
        assertEquals(emptyList<Long>(), samplesInClip(listOf(1L, 200L), 50L, 100L) { it })
    }
}
