package com.hoooon22.audiorecord

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 미디어 볼륨(STREAM_MUSIC)으로 재생되는 소리만 캡처해서 M4A(AAC) 파일로 저장합니다.
 *
 * AudioPlaybackCapture 는 USAGE_MEDIA / USAGE_GAME / USAGE_UNKNOWN 만 캡처할 수 있고,
 * 이 세 가지가 정확히 "미디어 볼륨"으로 조절되는 소리입니다. 마이크, 통화, 알림음, 벨소리,
 * 알람, 시스템 소리는 포함되지 않습니다.
 */
class MediaVolumeRecorder(
    private val context: Context,
    private val projection: MediaProjection,
    private val onFinished: (savedName: String?, error: Throwable?) -> Unit,
) {
    @Volatile
    var paused = false

    @Volatile
    private var running = false

    /** 인코더에 넘긴 PCM 바이트 수. 일시정지 구간은 포함되지 않습니다. */
    @Volatile
    private var pcmBytes = 0L

    val elapsedMs: Long
        get() = pcmBytes * 1000L / BYTES_PER_SECOND

    private lateinit var audioRecord: AudioRecord
    private lateinit var codec: MediaCodec
    private lateinit var muxer: MediaMuxer
    private lateinit var pfd: ParcelFileDescriptor
    private lateinit var outputUri: Uri
    private lateinit var displayName: String

    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false
    private var samplesWritten = 0

    @SuppressLint("MissingPermission") // RECORD_AUDIO 는 MainActivity 에서 확인합니다.
    fun start() {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_BYTES) * 4)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw IllegalStateException("AudioRecord 초기화 실패")
        }

        var entryCreated = false
        var codecCreated = false
        var muxerCreated = false
        try {
            createOutputEntry()
            entryCreated = true

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_BYTES * 2)
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codecCreated = true
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerCreated = true

            audioRecord.startRecording()
        } catch (t: Throwable) {
            audioRecord.release()
            if (codecCreated) runCatching { codec.release() }
            if (muxerCreated) runCatching { muxer.release() }
            if (entryCreated) discardOutput()
            throw t
        }

        running = true
        Thread(::recordLoop, "MediaVolumeRecorder").start()
    }

    /** 녹음을 끝냅니다. 파일 저장이 끝나면 onFinished 가 (녹음 스레드에서) 호출됩니다. */
    fun stop() {
        running = false
    }

    private fun createOutputEntry() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        displayName = "MediaRec_$stamp.m4a"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        outputUri = resolver.insert(collectionUri(), values)
            ?: throw IOException("저장 파일을 만들 수 없습니다")
        pfd = try {
            resolver.openFileDescriptor(outputUri, "rw")
        } catch (e: Exception) {
            resolver.delete(outputUri, null, null)
            throw e
        } ?: run {
            resolver.delete(outputUri, null, null)
            throw IOException("저장 파일을 열 수 없습니다")
        }
    }

    private fun recordLoop() {
        var error: Throwable? = null
        try {
            val pcm = ByteArray(CHUNK_BYTES)
            while (running) {
                val read = audioRecord.read(pcm, 0, pcm.size)
                if (read < 0) throw IOException("오디오 읽기 오류 ($read)")
                // 일시정지 중에는 읽은 소리를 버립니다. 타임스탬프는 쓴 양으로만 계산하므로 공백 없이 이어집니다.
                if (read == 0 || paused) continue
                feedEncoder(pcm, read)
                drainEncoder(endOfStream = false)
            }
            signalEndOfStream()
            drainEncoder(endOfStream = true)
        } catch (t: Throwable) {
            Log.e(TAG, "recording failed", t)
            error = t
        }

        runCatching { audioRecord.stop() }
        audioRecord.release()
        runCatching { codec.stop() }
        codec.release()

        val muxerOk = muxerStarted && samplesWritten > 0 && runCatching { muxer.stop() }.isSuccess
        runCatching { muxer.release() }
        runCatching { pfd.close() }

        if (error == null && muxerOk) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(outputUri, values, null, null)
            onFinished(displayName, null)
        } else {
            discardOutput()
            onFinished(null, error ?: NoAudioException())
        }
    }

    private fun feedEncoder(pcm: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drainEncoder(endOfStream = false)
                continue
            }
            val buffer = codec.getInputBuffer(index) ?: continue
            buffer.clear()
            val size = minOf(buffer.remaining(), length - offset)
            buffer.put(pcm, offset, size)
            codec.queueInputBuffer(index, 0, size, presentationTimeUs(), 0)
            pcmBytes += size
            offset += size
        }
    }

    private fun signalEndOfStream() {
        repeat(MAX_EOS_TRIES) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, presentationTimeUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
            drainEncoder(endOfStream = false)
        }
        throw IOException("인코더 종료 실패")
    }

    private fun drainEncoder(endOfStream: Boolean) {
        var waits = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) TIMEOUT_US else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++waits > MAX_EOS_TRIES) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "인코더 출력 형식이 두 번 바뀌었습니다" }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val output = codec.getOutputBuffer(index)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        // 코덱 설정 정보는 addTrack 에 넘긴 형식에 이미 들어 있습니다.
                        bufferInfo.size = 0
                    }
                    if (output != null && bufferInfo.size > 0 && muxerStarted) {
                        output.position(bufferInfo.offset)
                        output.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, output, bufferInfo)
                        samplesWritten++
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun presentationTimeUs(): Long = pcmBytes * 1_000_000L / BYTES_PER_SECOND

    private fun discardOutput() {
        runCatching { pfd.close() }
        runCatching { context.contentResolver.delete(outputUri, null, null) }
    }

    class NoAudioException : Exception("녹음된 소리가 없습니다")

    companion object {
        private const val TAG = "MediaVolumeRecorder"

        const val RELATIVE_PATH = "Music/MediaVolumeRecorder/"

        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2
        private const val BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * 2L
        private const val BIT_RATE = 192_000
        private const val CHUNK_BYTES = 4096
        private const val TIMEOUT_US = 10_000L
        private const val MAX_EOS_TRIES = 100

        fun collectionUri(): Uri =
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
}
