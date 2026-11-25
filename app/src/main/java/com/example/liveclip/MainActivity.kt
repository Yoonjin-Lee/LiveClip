
package com.example.liveclip

import android.Manifest
import android.content.pm.PackageManager
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import com.example.liveclip.ui.theme.LiveClipTheme
import kotlin.jvm.java

class MainActivity : ComponentActivity() {

    private val mediaProjectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
    }

    // Activity가 STARTED 상태가 되기 전에 등록
    private val startMediaProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->

        if (result.resultCode == RESULT_OK && result.data != null) {
            Toast.makeText(
                this,
                "화면 캡처 권한을 허용했습니다.",
                Toast.LENGTH_SHORT
            ).show()

            Log.d("data", "result data: ${result.data}")
            // foregroundService에 보낼 Intent와 result data의 intent는 다르다.
            // 보낼 intent는 어떤 서비스를 실행시킬지 지정을 해줘야 한다.
            val intent = Intent(
                this,
                RecordingService::class.java
            ).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("resultData", result.data)
            }
            ContextCompat.startForegroundService(
                this,
                intent
            )

        } else {
            Toast.makeText(
                this,
                "권한 요청이 취소되었습니다.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }


    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            requestScreenCapture()
        } else {
            Toast.makeText(this, "내부 소리 녹화를 위해 오디오 권한이 필요합니다.", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestScreenCapture() {
        startMediaProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startRecordingWithAudio() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) {
            requestScreenCapture()
        } else {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LiveClipTheme {
                HomeScreen(
                    onClickStart = {
                        startRecordingWithAudio()
                    },
                    onClickStop = {
                        val intent = Intent(
                            this,
                            RecordingService::class.java
                        ).apply {
                            action = RecordingService.ACTION_STOP
                        }

                        startService(intent)
                    }
                )
            }
        }
    }
}

@Composable
fun HomeScreen(
    onClickStart: () -> Unit,
    onClickStop: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = onClickStart) {
            Text(text = "녹화")
        }

        Button(onClick = onClickStop) {
            Text(text = "중지")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    LiveClipTheme {
        HomeScreen(onClickStart = {}, onClickStop = {})
    }
}
