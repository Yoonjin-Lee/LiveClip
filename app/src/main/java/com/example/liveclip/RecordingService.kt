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
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import android.media.MediaCodecInfo
import java.io.File

class RecordingService : Service() {
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaProjection: MediaProjection? = null
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null

    private var isRecording = false
    private var isStopping = false
    private var videoUri: Uri? = null
    private var outputPfd: ParcelFileDescriptor? = null

    companion object {
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.example.liveclip.STOP"
    }

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

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        // 여기서 Foreground Service 시작 및
        // MediaProjection 관련 작업 처리
        // 사용자에게 표시할 알림 생성

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

                    // 다음 단계:
                    // intent에서 resultCode, resultData를 꺼내
                    // MediaProjection 객체 생성
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

                    /**
                     * 기기 화면 정보 조회
                     * width / height / densityDpi
                     *
                     * MediaRecorder 설정
                     * 해상도, 코덱, 파일 경로 설정 후 prepare()
                     *
                     * Surface 획득
                     * mediaRecorder.surface
                     *
                     * VirtualDisplay 생성
                     * Surface를 출력 대상으로 연결
                     *
                     * MediaRecorder.start()
                     * 화면 프레임 인코딩 및 파일 기록 시작
                     */

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

                    // ⬆️----------------mediaCodec----------------------

//                    mediaRecorder = MediaRecorder(this)
//
//                    mediaRecorder?.apply {
//                        setVideoSource(MediaRecorder.VideoSource.SURFACE)
//                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
//
//                        setVideoEncoder(MediaRecorder.VideoEncoder.H264) // 압축 방식 설정
//                        setVideoSize(width, height)
//                        setVideoFrameRate(30) //30fps
//                        setVideoEncodingBitRate(8_000_000) // 1초 동안 인코딩된 영상에 사용하는 데이터량 = 8Mbps
//
//                        setOutputFile(pfd.fileDescriptor)
//
//                        prepare()
//                    }

                    // TODO: surface를 mediaRecoder에서 얻는 것이 아니라,
                    // mediaCodec에서 얻어야 한다.
                    // 나는 지금 전체 mp4 파일이 아니라 1초 마다 frame 정보를 가지고 있다가
                    // 그 정보로 mp4 파일을 만들어야 함.
                    val surface = mediaCodec?.createInputSurface() //mediaRecorder?.surface
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

                    //mediaRecorder?.start()
                    mediaCodec?.start()
                    isRecording = true

                } catch (e: Exception) {
                    Log.e("RecordingService", "녹화 초기화 실패", e)
                    stopRecording()
                }
            }
        }
        return START_NOT_STICKY
    }


    private fun stopRecording() {
        if (isStopping) return
        isStopping = true

        var savedSuccessfully = false

        // 1. MediaRecorder 종료
        try {
            mediaRecorder?.let { recorder ->
                if (isRecording) {
                    recorder.stop()
                    savedSuccessfully = true
                    Log.d("RecordingService", "녹화 종료 성공")
                }
            }
        } catch (e: RuntimeException) {
            Log.e("RecordingService", "녹화 파일 저장 실패", e)
        } finally {
            try {
                mediaRecorder?.release()
            } catch (e: Exception) {
                Log.e("RecordingService", "Recorder 해제 실패", e)
            }
            mediaRecorder = null
            isRecording = false
        }

        // 2. VirtualDisplay 및 인코더 해제
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.e("RecordingService", "VirtualDisplay 해제 실패", e)
        }
        virtualDisplay = null

        // configure/start 도중 실패한 코덱도 release로 해제합니다.
        val codec = mediaCodec
        mediaCodec = null
        try {
            codec?.release()
        } catch (e: Exception) {
            Log.e("RecordingService", "MediaCodec 해제 실패", e)
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