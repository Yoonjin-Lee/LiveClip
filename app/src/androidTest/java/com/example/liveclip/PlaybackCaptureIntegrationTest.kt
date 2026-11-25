package com.example.liveclip

import android.Manifest
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** 에뮬레이터의 영어 시스템 UI에서 화면 공유 동의를 거쳐 실제 AAC 트랙을 검증합니다. */
@RunWith(AndroidJUnit4::class)
class PlaybackCaptureIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun stopSavesVideoAndAudiblePlaybackInOneMp4() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val before = System.currentTimeMillis() / 1000L
        val tone = ShortArray(48_000 * 2) { index ->
            (sin(2 * PI * 440 * (index / 2) / 48_000) * 8_000).toInt().toShort()
        }
        val player = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(tone.size * 2)
            .build()
        try {
            player.write(tone, 0, tone.size)
            player.setLoopPoints(0, 48_000, -1)
            player.play()
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            clickText("녹화")
            clickText("Share one app")
            clickText("Share entire screen")
            clickText("Share screen", "Start now", "Start sharing")
            SystemClock.sleep(5_000)
            clickText("중지")

            var uri: android.net.Uri? = null
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (uri == null && SystemClock.elapsedRealtime() < deadline) {
                context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Video.Media._ID),
                    "${MediaStore.Video.Media.DATE_ADDED} >= ? AND ${MediaStore.Video.Media.IS_PENDING} = 0 AND ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
                    arrayOf(before.toString(), "LiveClip_%"), "${MediaStore.Video.Media._ID} DESC")?.use { cursor ->
                    if (cursor.moveToFirst()) uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                }
                if (uri == null) SystemClock.sleep(100)
            }
            val saved = checkNotNull(uri) { "완성된 MP4가 없습니다" }
            try {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, saved, null)
                    val mimes = (0 until extractor.trackCount).map {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    }
                    assertTrue("영상 트랙 없음: $mimes", mimes.contains(MediaFormat.MIMETYPE_VIDEO_AVC))
                    assertTrue("오디오 트랙 없음: $mimes", mimes.contains(MediaFormat.MIMETYPE_AUDIO_AAC))
                    val audioTrack = mimes.indexOf(MediaFormat.MIMETYPE_AUDIO_AAC)
                    extractor.selectTrack(audioTrack)
                    assertTrue("오디오 시작 시각이 영상과 크게 어긋남", extractor.sampleTime in 0..1_000_000)
                    assertAudible(extractor, extractor.getTrackFormat(audioTrack))
                } finally {
                    extractor.release()
                }
            } finally {
                // 검증용으로 생성한 영상만 삭제합니다.
                context.contentResolver.delete(saved, null, null)
            }
        } finally {
            context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
            player.stop()
            player.release()
        }
    }

    private fun clickText(vararg labels: String) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            for (label in labels) {
                var node = findText(root, label)
                while (node != null) {
                    if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                    node = node.parent
                }
            }
            SystemClock.sleep(100)
        }
        error("버튼을 찾지 못했습니다: ${labels.toList()}")
    }

    private fun findText(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label) return node
        for (index in 0 until node.childCount) {
            findText(node.getChild(index), label)?.let { return it }
        }
        return null
    }

    private fun assertAudible(extractor: MediaExtractor, format: MediaFormat) {
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            decoder.configure(format, null, null, 0)
            decoder.start()
            var inputEnded = false
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = checkNotNull(decoder.getInputBuffer(index))
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    var audible = false
                    try {
                        val output = checkNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.nativeOrder())
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        while (output.remaining() >= 2) {
                            if (kotlin.math.abs(output.short.toInt()) > 500) audible = true
                        }
                    } finally {
                        decoder.releaseOutputBuffer(index, false)
                    }
                    if (audible) return
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
            fail("AAC 트랙이 무음입니다")
        } finally {
            decoder.release()
        }
    }
}
