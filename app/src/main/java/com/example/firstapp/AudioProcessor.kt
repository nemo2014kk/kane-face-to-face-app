package com.example.firstapp

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

class AudioProcessor {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    private val pcmDataStream = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    fun startRecording() {
        pcmDataStream.reset()
        isRecording = true

        try {
            audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, channelConfig, audioFormat, bufferSize)
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                isRecording = false
                return
            }
            audioRecord?.startRecording()
        } catch (e: Exception) {
            e.printStackTrace()
            isRecording = false
            return
        }

        thread {
            val buffer = ByteArray(bufferSize)

            // 🛡️ 黄金平衡点：极限录音时长锁定为 3 分钟 (180秒)
            // 算法: 采样率(16000) * 采样位数(2 byte) * 时长(180秒) ≈ 5.76 MB
            // 完美匹配面对面交流的极限场景，彻底杜绝内存溢出
            val maxBytes = sampleRate * 2 * 180

            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    // 🛡️ 熔断机制：超过 3 分钟后开启空转保护，不再吃内存
                    if (pcmDataStream.size() < maxBytes) {
                        pcmDataStream.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    fun stopAndProcess(): ByteArray? {
        isRecording = false
        try {
            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord?.stop()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            audioRecord?.release()
            audioRecord = null
        }

        val rawPcm = pcmDataStream.toByteArray()
        if (rawPcm.size < sampleRate * 2 * 0.3) return null // 防误触

        val shortBuffer = ByteBuffer.wrap(rawPcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val pcmShorts = ShortArray(shortBuffer.capacity())
        shortBuffer.get(pcmShorts)

        // ==========================================
        // 🎛️ 录音棚级 DSP (数字信号处理) 核心管线
        // ==========================================

        // 1. 去直流偏移 (DC Offset Removal) & 过滤低频震水声
        var lastX = 0f
        var lastY = 0f
        for (i in pcmShorts.indices) {
            val x = pcmShorts[i].toFloat()
            val y = x - lastX + 0.995f * lastY // IIR 高通滤波
            lastX = x
            lastY = y
            pcmShorts[i] = y.toInt().toShort()
        }

        // 2. 噪声门 (Noise Gate) - 抹除环境白噪音
        val noiseThreshold = 150 // 低于此阈值的细碎声音会被削弱
        for (i in pcmShorts.indices) {
            if (abs(pcmShorts[i].toInt()) < noiseThreshold) {
                // 削弱 50% 噪音，保留自然感，不至于变成死寂
                pcmShorts[i] = (pcmShorts[i] * 0.5f).toInt().toShort()
            }
        }

        // 3. 动态压限 (Dynamic Range Compression / 智能增益 AGC)
        var sumSquare = 0.0
        for (sample in pcmShorts) {
            sumSquare += (sample.toDouble() * sample.toDouble())
        }
        val rms = sqrt(sumSquare / pcmShorts.size)

        val targetRms = 3500.0 // 目标黄金响度
        var multiplier = if (rms > 10) targetRms / rms else 1.0
        multiplier = multiplier.coerceIn(0.5, 12.0) // 最大放大倍数限制，防破音

        val softLimit = 28000.0 // 软限幅阈值
        for (i in pcmShorts.indices) {
            var amplified = pcmShorts[i] * multiplier

            // 软限幅 (Soft Clipping)：大声不炸麦，圆滑过渡
            if (amplified > softLimit) {
                amplified = softLimit + (amplified - softLimit) * 0.2
            } else if (amplified < -softLimit) {
                amplified = -softLimit + (amplified + softLimit) * 0.2
            }
            pcmShorts[i] = amplified.toInt().toShort()
        }

        // 4. 峰值标准化 (Peak Normalization) - Whisper 偏好满血响度
        var peak = 0
        for (sample in pcmShorts) {
            val absVal = abs(sample.toInt())
            if (absVal > peak) peak = absVal
        }
        if (peak in 1..32000) {
            val normMult = 32000f / peak.toFloat()
            for (i in pcmShorts.indices) {
                var finalSample = (pcmShorts[i] * normMult).toInt()
                finalSample = finalSample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                pcmShorts[i] = finalSample.toShort()
            }
        }

        // 转回字节流并封装标准 WAV 协议头
        val processedBytes = ByteArray(pcmShorts.size * 2)
        ByteBuffer.wrap(processedBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcmShorts)

        return addWavHeader(processedBytes)
    }

    private fun addWavHeader(pcmData: ByteArray): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * 1 * 2
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte(); header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte(); header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0 // PCM
        header[22] = 1; header[23] = 0 // Mono
        header[24] = (sampleRate and 0xff).toByte(); header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte(); header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte(); header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = 2; header[33] = 0
        header[34] = 16; header[35] = 0 // 16 bit
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (pcmData.size and 0xff).toByte(); header[41] = ((pcmData.size shr 8) and 0xff).toByte()
        header[42] = ((pcmData.size shr 16) and 0xff).toByte(); header[43] = ((pcmData.size shr 24) and 0xff).toByte()

        val wavStream = ByteArrayOutputStream()
        wavStream.write(header)
        wavStream.write(pcmData)
        return wavStream.toByteArray()
    }
}