package com.example.firstapp

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

class AudioProcessor {
    var audioManager: android.media.AudioManager? = null
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var isRecording = false
    private var readLatch: java.util.concurrent.CountDownLatch? = null

    // 🌟 航天级加固 1：幽灵线程隔离器。为每一次录音分发独立的身份证 ID
    private val sessionCounter = java.util.concurrent.atomic.AtomicInteger(0)

    // 🌟 航天级加固 2：不再使用全局唯一内存池，改为每次录音动态分配专属容器，彻底断绝交叉污染
    @Volatile
    private var activePcmStream: ByteArrayOutputStream? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    @SuppressLint("MissingPermission")
    fun startRecording() {
        isRecording = true
        val currentSessionId = sessionCounter.incrementAndGet()
        val sessionStream = ByteArrayOutputStream()
        activePcmStream = sessionStream

        // 🌟 航天级修补：用一个局部变量把安全钟死死攥住，防止被主线程设为 null！
        val myLatch = java.util.concurrent.CountDownLatch(1)
        readLatch = myLatch

        try {
            audioManager?.stopBluetoothSco()
            audioManager?.isBluetoothScoOn = false
        } catch (e: Exception) {}

        kotlin.concurrent.thread {
            try {
                try {
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sampleRate, channelConfig, audioFormat, bufferSize
                    )

                    if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                        throw IllegalStateException("初次初始化失败")
                    }

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && audioManager != null) {
                        val devices = audioManager!!.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                        for (device in devices) {
                            if (device.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                                audioRecord?.preferredDevice = device
                                break
                            }
                        }
                    }

                    if (!isRecording || sessionCounter.get() != currentSessionId) {
                        throw IllegalStateException("录音任务已被取消或覆盖")
                    }

                    audioRecord?.startRecording()

                    val testBuffer = ByteArray(bufferSize)
                    val testRead = audioRecord?.read(testBuffer, 0, testBuffer.size) ?: 0
                    var isDeadStream = true
                    if (testRead > 0) {
                        for (i in 0 until testRead) {
                            if (testBuffer[i] != 0.toByte()) {
                                isDeadStream = false
                                break
                            }
                        }
                    }

                    if (testRead <= 0 || isDeadStream) {
                        throw IllegalStateException("检测到死流或被系统挂起")
                    } else {
                        sessionStream.write(testBuffer, 0, testRead)
                    }

                } catch (e: Exception) {
                    try { audioRecord?.release() } catch (ex: Exception) {}
                    try {
                        if (!isRecording || sessionCounter.get() != currentSessionId) return@thread

                        audioRecord = AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            sampleRate, channelConfig, audioFormat, bufferSize
                        )
                        audioRecord?.startRecording()
                    } catch (ex: Exception) {
                        isRecording = false
                        return@thread
                    }
                }

                // 正式进入正常的数据读取循环
                val buffer = ByteArray(bufferSize)
                val maxBytes = sampleRate * 2 * 180

                while (isRecording && sessionCounter.get() == currentSessionId) {
                    try {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (read > 0) {
                            if (sessionStream.size() < maxBytes) {
                                sessionStream.write(buffer, 0, read)
                            }
                        } else if (read < 0) {
                            Thread.sleep(10)
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            } finally {
                // 🌟 敲响专属的局部安全钟！0 毫秒完美解锁主线程！
                myLatch.countDown()
            }
        }
    }

    fun stopAndProcess(): ByteArray? {
        isRecording = false
        sessionCounter.incrementAndGet() // 🌟 航天级防线 4：强制吊销所有旧线程的身份证，让它们瞬间自杀！

        // 🌟 航天级防线 5：抓取对象快照（Snapshot Pattern），防止交接时的空指针或死锁
        val latchToWait = readLatch
        val recordToRelease = audioRecord
        val streamToRead = activePcmStream

        // 断开全局引用，让新一轮的录音可以畅通无阻地使用新变量
        readLatch = null
        audioRecord = null
        activePcmStream = null

        try {
            latchToWait?.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {}

        try {
            if (recordToRelease?.state == AudioRecord.STATE_INITIALIZED) {
                recordToRelease.stop()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            recordToRelease?.release()
        }

        // 安全提取快照中的数据
        val rawPcm = streamToRead?.toByteArray() ?: ByteArray(0)
        if (rawPcm.size < sampleRate * 2 * 0.3) return null // 防误触

        val shortBuffer = ByteBuffer.wrap(rawPcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val pcmShorts = ShortArray(shortBuffer.capacity())
        shortBuffer.get(pcmShorts)

        // ==========================================
        // 🎛️ 录音棚级 DSP (数字信号处理) 核心管线
        // ==========================================

        var lastX = 0f
        var lastY = 0f
        for (i in pcmShorts.indices) {
            val x = pcmShorts[i].toFloat()
            val y = x - lastX + 0.995f * lastY
            lastX = x
            lastY = y
            pcmShorts[i] = y.toInt().toShort()
        }

        val noiseThreshold = 150
        for (i in pcmShorts.indices) {
            if (abs(pcmShorts[i].toInt()) < noiseThreshold) {
                pcmShorts[i] = (pcmShorts[i] * 0.5f).toInt().toShort()
            }
        }

        var sumSquare = 0.0
        for (sample in pcmShorts) {
            sumSquare += (sample.toDouble() * sample.toDouble())
        }
        val rms = sqrt(sumSquare / pcmShorts.size)

        val targetRms = 3500.0
        var multiplier = if (rms > 10) targetRms / rms else 1.0
        multiplier = multiplier.coerceIn(0.5, 12.0)

        val softLimit = 28000.0
        for (i in pcmShorts.indices) {
            var amplified = pcmShorts[i] * multiplier
            if (amplified > softLimit) {
                amplified = softLimit + (amplified - softLimit) * 0.2
            } else if (amplified < -softLimit) {
                amplified = -softLimit + (amplified + softLimit) * 0.2
            }
            pcmShorts[i] = amplified.toInt().toShort()
        }

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

        var startIdx = -1
        var endIdx = -1
        val silenceThreshold = 400

        for (i in pcmShorts.indices) {
            if (abs(pcmShorts[i].toInt()) > silenceThreshold) {
                startIdx = maxOf(0, i - 8000)
                break
            }
        }
        for (i in pcmShorts.size - 1 downTo 0) {
            if (abs(pcmShorts[i].toInt()) > silenceThreshold) {
                endIdx = minOf(pcmShorts.size - 1, i + 8000)
                break
            }
        }

        if (startIdx == -1 || endIdx == -1 || startIdx >= endIdx) return null

        val validLength = endIdx - startIdx + 1
        val trimmedShorts = ShortArray(validLength)
        System.arraycopy(pcmShorts, startIdx, trimmedShorts, 0, validLength)

        val processedBytes = ByteArray(trimmedShorts.size * 2)
        ByteBuffer.wrap(processedBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(trimmedShorts)

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