package com.example.liveclip

import android.media.MediaCodec
import java.nio.ByteBuffer

data class BufferData(
    val buffer: ByteBuffer,
    val bufferInfo: MediaCodec.BufferInfo,
)
