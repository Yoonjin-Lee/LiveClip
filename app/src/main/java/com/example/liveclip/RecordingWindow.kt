package com.example.liveclip

internal const val RECORDING_WINDOW_US = 60_000_000L

/** 프레임 개수가 아니라 PTS로 최근 60초를 유지합니다. 각 목록의 작성 코루틴에서 호출합니다. */
internal fun <T> trimToRecentWindow(
    samples: MutableList<T>,
    latestTimeUs: Long,
    timeUs: (T) -> Long
) {
    val cutoffUs = latestTimeUs - RECORDING_WINDOW_US
    val removeCount = samples.indexOfFirst { timeUs(it) >= cutoffUs }
        .let { if (it < 0) samples.size else it }
    if (removeCount > 0) samples.subList(0, removeCount).clear()
}

internal fun trimSamples(samples: MutableList<BufferData>, latestTimeUs: Long) {
    trimToRecentWindow(samples, latestTimeUs) { it.bufferInfo.presentationTimeUs }
}

/** 오디오도 영상의 첫 키 프레임부터 같은 종료 시각까지만 저장합니다. */
internal fun <T> samplesInClip(
    samples: List<T>,
    startUs: Long,
    endUs: Long,
    timeUs: (T) -> Long
): List<T> = samples.filter { timeUs(it) in startUs..endUs }
