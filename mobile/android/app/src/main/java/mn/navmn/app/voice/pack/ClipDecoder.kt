package mn.navmn.app.voice.pack

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.nio.ByteOrder

/** Decoded PCM, 16-bit, interleaved when [channels] > 1, at the decoder's output rate [sampleRateHz]. */
class DecodedPcm(val sampleRateHz: Int, val channels: Int, val samples: ShortArray) {
    /** Number of frames (samples per channel). */
    val frames: Int get() = if (channels <= 0) 0 else samples.size / channels
}

/** A clip could not be decoded or trimmed (pack-format §8 `DECODE_FAILED`). */
class ClipDecodeException(message: String) : Exception(message)

/** Decodes one whole clip file (ADTS AAC-LC) to PCM. Throws [ClipDecodeException]. */
interface ClipDecoder {
    fun decode(bytes: ByteArray): DecodedPcm
}

/**
 * NAV-016 M7 (ADR-0010 §6): the framework decoder. `MediaExtractor` over the clip bytes (a [MediaDataSource], no file),
 * `MediaCodec` in a synchronous loop. `encoder-delay` and `encoder-padding` are set to 0 on the track format before
 * `configure()`, so no platform skip-cut applies and [ClipTrim] alone removes the priming (pack-format §5). Runs on the
 * voice output's background thread; never on the main thread.
 */
class MediaCodecClipDecoder(private val timeoutMs: Long = 2_000) : ClipDecoder {
    private class Bytes(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = data.size.toLong()

        override fun close() = Unit
    }

    override fun decode(bytes: ByteArray): DecodedPcm {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(Bytes(bytes))
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw ClipDecodeException("no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            format.setInteger(KEY_ENCODER_DELAY, 0)
            format.setInteger(KEY_ENCODER_PADDING, 0)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw ClipDecodeException("no mime type")
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            val out = ShortBuilder()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (true) {
                if (SystemClock.elapsedRealtime() > deadline) throw ClipDecodeException("decoder timeout")
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(DEQUEUE_US)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i) ?: throw ClipDecodeException("no input buffer")
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, DEQUEUE_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        val buf = c.getOutputBuffer(o)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val ordered = buf.slice().order(ByteOrder.nativeOrder())
                            if (float) {
                                val fb = ordered.asFloatBuffer()
                                while (fb.hasRemaining()) out.add((fb.get().coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
                            } else {
                                val sb = ordered.asShortBuffer()
                                while (sb.hasRemaining()) out.add(sb.get())
                            }
                        }
                        c.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            if (rate <= 0 || channels <= 0) throw ClipDecodeException("bad output format")
            return DecodedPcm(rate, channels, out.toArray())
        } catch (e: ClipDecodeException) {
            throw e
        } catch (e: Exception) {
            throw ClipDecodeException("decoder error: ${e.javaClass.simpleName}")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private class ShortBuilder {
        private var data = ShortArray(32_768)
        private var size = 0

        fun add(v: Short) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun toArray(): ShortArray = data.copyOf(size)
    }

    private companion object {
        const val DEQUEUE_US = 10_000L
        // MediaFormat.KEY_ENCODER_DELAY / KEY_ENCODER_PADDING (API 30 constants; the keys are honoured earlier).
        const val KEY_ENCODER_DELAY = "encoder-delay"
        const val KEY_ENCODER_PADDING = "encoder-padding"
    }
}

/**
 * NAV-016 M7: the trim rule of pack-format §5, exactly. Works for decoders that output the priming frame, decoders that
 * drop the first access unit, and implicit-SBR decoders at twice the clip rate. The result has exactly `frames` × k
 * frames of channel 0.
 */
object ClipTrim {
    fun trim(pcm: DecodedPcm, entry: PackEntry, codec: PackCodec): ShortArray {
        val rOut = pcm.sampleRateHz
        val k = when (rOut) {
            codec.sampleRateHz -> 1
            codec.sampleRateHz * 2 -> 2
            else -> throw ClipDecodeException("output rate $rOut is not 1 or 2 times ${codec.sampleRateHz}")
        }
        val p = codec.encoderDelayFrames * k
        val f = entry.frames * k
        val r = entry.encodedFrames * k
        val d = pcm.frames
        var missing = r - d
        if (missing in -2..-1) missing = 0
        if (missing < 0 || missing > p) throw ClipDecodeException("decoded length $d does not fit encodedFrames $r")
        val start = p - missing
        val out = ShortArray(f)
        val available = (d - start).coerceAtLeast(0)
        val take = minOf(f, available)
        val ch = pcm.channels
        for (i in 0 until take) out[i] = pcm.samples[(start + i) * ch]
        val shortfall = f - take
        if (shortfall > rOut / 100) throw ClipDecodeException("decoded clip is ${shortfall} frames short")
        return out // the remaining ≤ 10 ms stay zero (pack-format §5 step 4)
    }
}
