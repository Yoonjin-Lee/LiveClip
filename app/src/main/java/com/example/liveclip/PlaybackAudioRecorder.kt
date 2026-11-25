package com.example.liveclip

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.nio.ByteBuffer

/** 내부 소리(PCM)를 AAC로 인코딩합니다. collect() 종료 후에만 samples를 읽습니다. */
class PlaybackAudioRecorder {
    private var recorder: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var encoderStarted = false
    private var captureStartUs = 0L

    var outputFormat: MediaFormat? = null
        private set
    val samples = mutableListOf<BufferData>()

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2
        private const val BYTES_PER_FRAME = CHANNELS * 2 // PCM 16bit stereo
        private const val PCM_CHUNK_BYTES = 1024 * BYTES_PER_FRAME
    }

    @SuppressLint("MissingPermission") // 직전에 권한을 검사하며 Activity에서도 요청합니다.
    fun start(context: Context, projection: MediaProjection) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) { "내부 오디오 녹음 권한이 없습니다" }

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val minSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minSize > 0) { "지원되지 않는 오디오 입력 설정입니다: $minSize" }
        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minSize * 2, PCM_CHUNK_BYTES * 4))
            .build()
        recorder = record
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 초기화 실패" }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder = codec
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_CHUNK_BYTES)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        encoderStarted = true
        captureStartUs = System.nanoTime() / 1_000L
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "내부 소리 캡처 시작 실패" }
    }

    suspend fun collect(shouldStop: () -> Boolean) {
        val record = checkNotNull(recorder)
        val codec = checkNotNull(encoder)
        val info = MediaCodec.BufferInfo()
        val timestamp = AudioTimestamp()
        val pcm = ByteArray(PCM_CHUNK_BYTES)
        var pendingBytes = 0
        var pendingTimeUs = 0L
        var framesRead = 0L
        var lastInputTimeUs = Long.MIN_VALUE
        var nextInputTimeUs = captureStartUs
        var eosSent = false
        var stopping = false
        var deadline = Long.MAX_VALUE

        while (true) {
            if (shouldStop() && !stopping) {
                stopping = true
                record.stop()
                deadline = SystemClock.elapsedRealtime() + 5_000L
            }
            check(SystemClock.elapsedRealtime() < deadline) { "오디오 종료 출력 대기 시간 초과" }
            var progressed = false

            if (!stopping && pendingBytes == 0) {
                val size = record.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                check(size >= 0) { "내부 소리 읽기 실패: $size" }
                if (size > 0) {
                    check(size % BYTES_PER_FRAME == 0) { "PCM 프레임 크기가 올바르지 않습니다" }
                    // 비디오 Surface와 동일한 monotonic clock으로 PCM의 캡처 시각을 계산합니다.
                    val candidateUs = if (record.getTimestamp(timestamp,
                            AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        timestamp.nanoTime / 1_000L +
                            (framesRead - timestamp.framePosition) * 1_000_000L / SAMPLE_RATE
                    } else {
                        captureStartUs + framesRead * 1_000_000L / SAMPLE_RATE
                    }
                    pendingTimeUs = maxOf(candidateUs, lastInputTimeUs + 1)
                    pendingBytes = size
                    framesRead += size / BYTES_PER_FRAME
                    progressed = true
                }
            }

            if (!eosSent && (pendingBytes > 0 || stopping)) {
                val inputIndex = codec.dequeueInputBuffer(0)
                if (inputIndex >= 0) {
                    if (pendingBytes > 0) {
                        val input = checkNotNull(codec.getInputBuffer(inputIndex))
                        input.clear()
                        check(input.remaining() >= pendingBytes) { "AAC 입력 버퍼가 너무 작습니다" }
                        input.put(pcm, 0, pendingBytes)
                        codec.queueInputBuffer(inputIndex, 0, pendingBytes, pendingTimeUs, 0)
                        lastInputTimeUs = pendingTimeUs
                        nextInputTimeUs = pendingTimeUs +
                            (pendingBytes / BYTES_PER_FRAME) * 1_000_000L / SAMPLE_RATE
                        pendingBytes = 0
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, 0, nextInputTimeUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosSent = true
                    }
                    progressed = true
                }
            }

            // 입력과 출력을 같은 코루틴에서 번갈아 처리해 버퍼 고갈을 피합니다.
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 0)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputFormat = codec.outputFormat
                    progressed = true
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val output = checkNotNull(codec.getOutputBuffer(index))
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val copy = ByteBuffer.allocateDirect(info.size)
                            copy.put(output)
                            copy.flip()
                            val copiedInfo = MediaCodec.BufferInfo().apply {
                                set(0, info.size, info.presentationTimeUs, info.flags)
                            }
                            samples.add(BufferData(copy, copiedInfo))
                            trimSamples(samples, info.presentationTimeUs)
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                    progressed = true
                } else {
                    break
                }
            }
            if (!progressed) delay(5)
        }
    }

    // collect()를 join한 다음 호출합니다. 초기화 도중 실패한 경우에도 안전합니다.
    fun release() {
        val record = recorder
        recorder = null
        try {
            if (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        } finally {
            try {
                record?.release()
            } finally {
                val codec = encoder
                encoder = null
                try {
                    if (encoderStarted) codec?.stop()
                } finally {
                    encoderStarted = false
                    codec?.release()
                    samples.clear()
                    outputFormat = null
                }
            }
        }
    }
}
