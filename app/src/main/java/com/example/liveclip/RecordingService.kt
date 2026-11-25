package com.example.liveclip

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import android.media.MediaCodecInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.nio.ByteBuffer

class RecordingService : Service() {
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaProjection: MediaProjection? = null
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null

    private var isRecording = false
    @Volatile private var isStopping = false
    private var drainJob: Job? = null
    private var audioJob: Job? = null
    private var audioRecorder: PlaybackAudioRecorder? = null
    private var outputFormat: MediaFormat? = null
    @Volatile private var drainFailed = false
    private var videoUri: Uri? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var rollingBuffer: MutableList<BufferData> = mutableListOf()

    companion object {
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.example.liveclip.STOP"
    }

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    override fun onCreate() {
        super.onCreate()

        // 알림 채널 생성
        val channel = NotificationChannel(
            CHANNEL_ID,
            "화면 녹화",
            NotificationManager.IMPORTANCE_LOW
        )

        val manager = getSystemService(
            NotificationManager::class.java
        )

        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        // 시스템이 서비스를 종료할 때도 캡처/코덱을 남기지 않습니다.
        stopRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
            }

            else -> {
                if (isRecording || isStopping) return START_NOT_STICKY

                try {
                    val notification = NotificationCompat.Builder(
                        this,
                        CHANNEL_ID
                    )
                        .setContentTitle("LiveClip")
                        .setContentText("화면 녹화 서비스가 실행 중입니다.")
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setOngoing(true)
                        .build()

                    // Activity에서 전달한 화면 캡처 승인 결과를 확인합니다.
                    val resultCode = intent?.getIntExtra(
                        "resultCode",
                        Activity.RESULT_CANCELED
                    )

                    // intent는 Parcelable로 구현되어있음
                    val resultData =
                        intent?.getParcelableExtra("resultData", Intent::class.java)

                    if (resultCode != Activity.RESULT_OK || resultData == null) {
                        Log.w("RecordingService", "화면 캡처 승인 데이터가 없어 서비스를 종료합니다")
                        stopRecording()
                        return START_NOT_STICKY
                    }

                    // Foreground Service로 전환
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )

                    val mediaProjectionManager =
                        getSystemService(MediaProjectionManager::class.java)

                    mediaProjection = mediaProjectionManager.getMediaProjection(
                        resultCode,
                        resultData
                    )

                    mediaProjection?.registerCallback(
                        object : MediaProjection.Callback() {
                            override fun onStop() {
                                stopRecording()
                            }
                        },
                        Handler(Looper.getMainLooper())
                    )

                    val windowManager = getSystemService(WindowManager::class.java)
                    val bounds = windowManager.maximumWindowMetrics.bounds
                    val width = bounds.width()
                    val height = bounds.height()
                    val densityDpi = resources.configuration.densityDpi

                    // 1. MediaStore에 저장할 파일 생성
                    val uri = createVideoUri()
                    videoUri = uri

                    // 2. 파일에 쓰기 위한 Descriptor 열기
                    val pfd = contentResolver.openFileDescriptor(uri, "w")
                        ?: error("동영상 파일을 열 수 없습니다")

                    outputPfd = pfd

                    mediaCodec = MediaCodec.createEncoderByType(
                        MediaFormat.MIMETYPE_VIDEO_AVC
                    )

                    val format = MediaFormat.createVideoFormat(
                        MediaFormat.MIMETYPE_VIDEO_AVC,
                        width,
                        height
                    ).apply {
                        // 입력을 ByteBuffer 대신 Surface로 받음
                        setInteger(
                            MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                        )

                        // 목표 비트레이트: 초당 8,000,000비트
                        setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)

                        // 인코더가 예상하는 초당 프레임 수
                        setInteger(MediaFormat.KEY_FRAME_RATE, 30)

                        // 키 프레임 간격: 약 1초
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    }

                    mediaCodec?.apply {
                        configure(
                            format,
                            null,
                            null,
                            MediaCodec.CONFIGURE_FLAG_ENCODE
                        )
                    }

                    val surface = mediaCodec?.createInputSurface()
                    inputSurface = surface

                    virtualDisplay = mediaProjection?.createVirtualDisplay(
                        "LiveClip",
                        width,
                        height,
                        densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface,
                        null,
                        null
                    )

                    mediaCodec?.start()
                    isRecording = true

                    val audio = PlaybackAudioRecorder()
                    audioRecorder = audio // start 도중 실패해도 정리할 수 있도록 먼저 보관
                    audio.start(this, checkNotNull(mediaProjection))
                    audioJob = serviceScope.launch {
                        try {
                            audio.collect { isStopping }
                        } catch (e: Exception) {
                            drainFailed = true
                            Log.e("RecordingService", "내부 오디오 처리 실패", e)
                            Handler(Looper.getMainLooper()).post { stopRecording() }
                        }
                    }

                    val codec = checkNotNull(mediaCodec)
                    drainJob = serviceScope.launch {
                        try {
                            collectEncodedBuffers(codec)
                        } catch (e: Exception) {
                            drainFailed = true
                            Log.e("RecordingService", "인코더 출력 처리 실패", e)
                            Handler(Looper.getMainLooper()).post { stopRecording() }
                        }
                    }

                } catch (e: Exception) {
                    Log.e("RecordingService", "녹화 초기화 실패", e)
                    stopRecording()
                }
            }
        }
        return START_NOT_STICKY
    }


    // 녹화 중에는 이 함수만 버퍼 목록에 씁니다. 종료 시 join 후 읽고 비웁니다.
    private fun collectEncodedBuffers(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var eosSent = false
        var eosDeadline = Long.MAX_VALUE

        while (true) {
            if (isStopping && !eosSent) {
                codec.signalEndOfInputStream()
                eosSent = true
                eosDeadline = SystemClock.elapsedRealtime() + 5_000L
            }
            check(SystemClock.elapsedRealtime() < eosDeadline) {
                "인코더 종료 출력을 기다리는 시간이 초과되었습니다"
            }

            when (val index = codec.dequeueOutputBuffer(info, 10_000L)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // 입력 format 대신 SPS/PPS 등이 포함된 실제 출력 format을 저장합니다.
                    outputFormat = codec.outputFormat
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (index >= 0) {
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isEnd = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    try {
                        if (!isConfig && info.size > 0) {
                            val output = checkNotNull(codec.getOutputBuffer(index))
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val copy = ByteBuffer.allocateDirect(info.size)
                            copy.put(output)
                            copy.flip()
                            val copiedInfo = MediaCodec.BufferInfo().apply {
                                set(0, info.size, info.presentationTimeUs, info.flags)
                            }
                            rollingBuffer.add(BufferData(copy, copiedInfo))

                            trimSamples(rollingBuffer, info.presentationTimeUs)
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                    if (isEnd) return
                }
            }
        }
    }

    // 출력 수집이 끝난 뒤 호출하므로 버퍼 목록이 변경되지 않습니다.
    private fun saveBufferedVideo() {
        val format = checkNotNull(outputFormat) { "인코더 출력 포맷이 없습니다" }
        val pfd = checkNotNull(outputPfd) { "저장할 파일이 열려 있지 않습니다" }
        val firstKeyFrame = rollingBuffer.indexOfFirst {
            (it.bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        }
        check(firstKeyFrame >= 0) { "저장할 키 프레임이 없습니다" }
        val baseTimeUs = rollingBuffer[firstKeyFrame].bufferInfo.presentationTimeUs

        val endTimeUs = rollingBuffer.last().bufferInfo.presentationTimeUs
        val audio = checkNotNull(audioRecorder)
        val audioFormat = checkNotNull(audio.outputFormat) { "AAC 출력 포맷이 없습니다" }
        val audioSamples = samplesInClip(audio.samples, baseTimeUs, endTimeUs) {
            it.bufferInfo.presentationTimeUs
        }
        check(audioSamples.isNotEmpty()) { "영상 구간에 저장할 오디오 샘플이 없습니다" }

        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            // 두 트랙을 모두 추가한 다음 start해야 합니다.
            val videoTrack = muxer.addTrack(format)
            val audioTrack = muxer.addTrack(audioFormat)
            muxer.start()
            var videoIndex = firstKeyFrame
            var audioIndex = 0
            // 각 트랙의 순서를 유지하면서 PTS 순서로 기록합니다.
            while (videoIndex < rollingBuffer.size || audioIndex < audioSamples.size) {
                val video = rollingBuffer.getOrNull(videoIndex)
                val sound = audioSamples.getOrNull(audioIndex)
                if (video != null && (sound == null ||
                        video.bufferInfo.presentationTimeUs <= sound.bufferInfo.presentationTimeUs)) {
                    writeSample(muxer, videoTrack, video, baseTimeUs)
                    videoIndex++
                } else {
                    writeSample(muxer, audioTrack, checkNotNull(sound), baseTimeUs)
                    audioIndex++
                }
            }
            // stop()이 성공해야 MP4가 완성된 것으로 판단합니다.
            muxer.stop()
            Log.d("RecordingService", "MP4 저장 완료: 영상 ${rollingBuffer.size - firstKeyFrame}개, " +
                "오디오 ${audioSamples.size}개 샘플, 구간 ${(endTimeUs - baseTimeUs) / 1_000_000.0}초")
        } finally {
            muxer.release()
        }
    }

    private fun writeSample(
        muxer: MediaMuxer,
        track: Int,
        sample: BufferData,
        baseTimeUs: Long
    ) {
        val info = MediaCodec.BufferInfo().apply {
            set(0, sample.bufferInfo.size,
                sample.bufferInfo.presentationTimeUs - baseTimeUs,
                sample.bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
        }
        val data = sample.buffer.duplicate()
        data.position(0)
        data.limit(info.size)
        muxer.writeSampleData(track, data, info)
    }

    private fun stopRecording() {
        if (isStopping) return
        isStopping = true

        // Surface에 새 프레임이 들어오는 것을 중단합니다.
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.e("RecordingService", "VirtualDisplay 해제 실패", e)
        }
        virtualDisplay = null


        serviceScope.launch {
            // 코덱 해제와 dequeueOutputBuffer가 동시에 실행되지 않도록 기다립니다.
            drainJob?.join()
            audioJob?.join()
            drainJob = null
            audioJob = null
            var savedSuccessfully = false
            try {
                if (isRecording && !drainFailed) {
                    saveBufferedVideo()
                    savedSuccessfully = true
                }
            } catch (e: Exception) {
                Log.e("RecordingService", "MP4 저장 실패", e)
            } finally {
                try {
                    audioRecorder?.release()
                } catch (e: Exception) {
                    Log.e("RecordingService", "오디오 자원 해제 실패", e)
                }
                audioRecorder = null
                try {
                    if (isRecording) mediaCodec?.stop()
                } catch (e: Exception) {
                    Log.e("RecordingService", "Codec 종료 실패", e)
                }
                try {
                    mediaCodec?.release()
                } catch (e: Exception) {
                    Log.e("RecordingService", "Codec 해제 실패", e)
                }
                mediaCodec = null
                isRecording = false
                rollingBuffer.clear()
                outputFormat = null
            }

            val surface = inputSurface
            inputSurface = null
            try {
                surface?.release()
            } catch (e: Exception) {
                Log.e("RecordingService", "입력 Surface 해제 실패", e)
            }

            // 3. 파일 디스크립터 닫기
            try {
                outputPfd?.close()
            } catch (e: Exception) {
                Log.e("RecordingService", "파일 닫기 실패", e)
            }
            outputPfd = null

            // 4. MediaStore에서 영상 공개 또는 삭제
            videoUri?.let { uri ->
                try {
                    if (savedSuccessfully) {
                        val values = ContentValues().apply {
                            put(MediaStore.Video.Media.IS_PENDING, 0)
                        }

                        val updatedRows = contentResolver.update(
                            uri,
                            values,
                            null,
                            null
                        )

                        Log.d(
                            "RecordingService",
                            "갤러리 공개 결과: $updatedRows, uri=$uri"
                        )
                    } else {
                        contentResolver.delete(uri, null, null)
                        Log.w("RecordingService", "실패한 녹화 파일 삭제")
                    }
                } catch (e: Exception) {
                    Log.e("RecordingService", "MediaStore 처리 실패", e)
                }
            }
            videoUri = null

            // 5. MediaProjection 종료
            val projection = mediaProjection
            mediaProjection = null

            try {
                projection?.stop()
            } catch (e: Exception) {
                Log.e("RecordingService", "MediaProjection 종료 실패", e)
            }

            // 6. 알림 제거 및 Service 종료
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                Log.e("RecordingService", "Foreground 종료 실패", e)
            } finally {
                stopSelf()
                serviceScope.cancel()
            }
        }
    }


    private fun createVideoUri(): Uri {
        val fileName = "LiveClip_${System.currentTimeMillis()}.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/LiveClip"
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        return contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: error("동영상 저장 위치 생성 실패")
    }

}
