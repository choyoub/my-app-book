package com.netice.myapp.durumari.audio

import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.OutputStream
import java.util.Locale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

enum class UiSoundKind {
    OPEN,
    CLOSE,
    PRESS,
}

class SyntheticSoundPlayer {
    private var soundPool: SoundPool? = null
    private val soundIds = mutableMapOf<SyntheticSound, Int>()
    private val loadedSoundIds = mutableSetOf<Int>()
    private val pendingSoundIds = mutableSetOf<Int>()

    @Synchronized
    fun prepare(cacheDir: File) {
        prepare(
            cacheDir = cacheDir,
            samplesBySound = mapOf(
                SyntheticSound.PAGE_PREVIOUS to createPageTurnSoundSamples(
                    previous = true,
                    durationSeconds = PAGE_TURN_PREVIOUS_SOUND_SECONDS,
                ),
                SyntheticSound.PAGE_NEXT to createPageTurnSoundSamples(
                    previous = false,
                    durationSeconds = PAGE_TURN_NEXT_SOUND_SECONDS,
                ),
                SyntheticSound.UI_OPEN to createUiOpenSoundSamples(),
                SyntheticSound.UI_CLOSE to createUiCloseSoundSamples(),
                SyntheticSound.UI_PRESS to createUiPressSoundSamples(),
            ),
        )
    }

    @Synchronized
    fun playPageTurn(previous: Boolean) {
        play(if (previous) SyntheticSound.PAGE_PREVIOUS else SyntheticSound.PAGE_NEXT)
    }

    @Synchronized
    fun playUi(kind: UiSoundKind) {
        val sound = when (kind) {
            UiSoundKind.OPEN -> SyntheticSound.UI_OPEN
            UiSoundKind.CLOSE -> SyntheticSound.UI_CLOSE
            UiSoundKind.PRESS -> SyntheticSound.UI_PRESS
        }
        play(sound)
    }

    @Synchronized
    fun release() {
        soundPool?.release()
        soundPool = null
        soundIds.clear()
        loadedSoundIds.clear()
        pendingSoundIds.clear()
    }

    private fun prepare(cacheDir: File, samplesBySound: Map<SyntheticSound, ShortArray>) {
        release()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val pool = SoundPool.Builder()
            .setMaxStreams(SOUND_POOL_MAX_STREAMS)
            .setAudioAttributes(attributes)
            .build()
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                var shouldPlay = false
                synchronized(this) {
                    loadedSoundIds.add(sampleId)
                    shouldPlay = pendingSoundIds.remove(sampleId)
                }
                if (shouldPlay) {
                    pool.play(sampleId, 1f, 1f, 1, 0, 1f)
                }
            }
        }
        soundPool = pool
        samplesBySound.forEach { (sound, samples) ->
            createRuntimeWavFile(cacheDir, sound, samples)?.let { file ->
                val soundId = pool.load(file.absolutePath, 1)
                if (soundId != 0) {
                    soundIds[sound] = soundId
                }
            }
        }
    }

    private fun play(sound: SyntheticSound) {
        val pool = soundPool ?: return
        val soundId = soundIds[sound] ?: return
        if (!loadedSoundIds.contains(soundId)) {
            pendingSoundIds.add(soundId)
            return
        }
        pool.play(soundId, 1f, 1f, 1, 0, 1f)
    }

    private fun createRuntimeWavFile(cacheDir: File, sound: SyntheticSound, samples: ShortArray): File? {
        if (samples.isEmpty()) return null
        return runCatching {
            val file = File(cacheDir, "durumari-${sound.name.lowercase(Locale.ROOT)}.wav")
            file.outputStream().use { output ->
                writeWav(output, samples)
            }
            file
        }.getOrNull()
    }

    private fun writeWav(output: OutputStream, samples: ShortArray) {
        val dataSize = samples.size * Short.SIZE_BYTES
        output.writeAscii("RIFF")
        output.writeIntLe(WAV_RIFF_CHUNK_OVERHEAD_BYTES + dataSize)
        output.writeAscii("WAVE")
        output.writeAscii("fmt ")
        output.writeIntLe(16)
        output.writeShortLe(1)
        output.writeShortLe(1)
        output.writeIntLe(SOUND_SAMPLE_RATE)
        output.writeIntLe(SOUND_SAMPLE_RATE * Short.SIZE_BYTES)
        output.writeShortLe(Short.SIZE_BYTES)
        output.writeShortLe(16)
        output.writeAscii("data")
        output.writeIntLe(dataSize)
        samples.forEach { sample ->
            output.writeShortLe(sample.toInt())
        }
    }

    private fun OutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun OutputStream.writeIntLe(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    private fun OutputStream.writeShortLe(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun createUiOpenSoundSamples(): ShortArray {
        return createCyberUiSoundSamples(
            durationSeconds = UI_OPEN_SOUND_SECONDS,
            startHz = UI_OPEN_START_HZ,
            endHz = UI_OPEN_END_HZ,
            outputGain = UI_OPEN_OUTPUT_GAIN,
            shimmerGain = UI_OPEN_SHIMMER_GAIN,
            growlGain = UI_OPEN_GROWL_GAIN,
            noiseGain = UI_OPEN_NOISE_GAIN,
            decay = UI_OPEN_DECAY,
        )
    }

    private fun createUiCloseSoundSamples(): ShortArray {
        return createCyberUiSoundSamples(
            durationSeconds = UI_CLOSE_SOUND_SECONDS,
            startHz = UI_CLOSE_START_HZ,
            endHz = UI_CLOSE_END_HZ,
            outputGain = UI_CLOSE_OUTPUT_GAIN,
            shimmerGain = UI_CLOSE_SHIMMER_GAIN,
            growlGain = UI_CLOSE_GROWL_GAIN,
            noiseGain = UI_CLOSE_NOISE_GAIN,
            decay = UI_CLOSE_DECAY,
        )
    }

    private fun createUiPressSoundSamples(): ShortArray {
        return createCyberUiSoundSamples(
            durationSeconds = UI_PRESS_SOUND_SECONDS,
            startHz = UI_PRESS_START_HZ,
            endHz = UI_PRESS_END_HZ,
            outputGain = UI_PRESS_OUTPUT_GAIN,
            shimmerGain = UI_PRESS_SHIMMER_GAIN,
            growlGain = UI_PRESS_GROWL_GAIN,
            noiseGain = UI_PRESS_NOISE_GAIN,
            decay = UI_PRESS_DECAY,
        )
    }

    private fun createCyberUiSoundSamples(
        durationSeconds: Double,
        startHz: Double,
        endHz: Double,
        outputGain: Double,
        shimmerGain: Double,
        growlGain: Double,
        noiseGain: Double,
        decay: Double,
    ): ShortArray {
        val sampleCount = max(1, (SOUND_SAMPLE_RATE * durationSeconds).roundToInt())
        val samples = ShortArray(sampleCount)
        var energyPhase = 0.0
        var modulationPhase = 0.0
        var shimmerPhase = 0.0
        val frequencyRatio = endHz / startHz
        for (index in samples.indices) {
            val t = index.toDouble() / sampleCount.toDouble()
            val attack = (t / UI_SOUND_ATTACK_RATIO).coerceIn(0.0, 1.0)
            val release = ((1.0 - t) / (1.0 - UI_SOUND_ATTACK_RATIO)).coerceIn(0.0, 1.0)
            val envelope = attack * release.pow(decay)
            val modulationFrequency = UI_SOUND_MOD_START_HZ + (UI_SOUND_MOD_END_HZ - UI_SOUND_MOD_START_HZ) * t
            modulationPhase += (2.0 * PI * modulationFrequency) / SOUND_SAMPLE_RATE
            val frequency = (startHz * frequencyRatio.pow(t)) +
                sin(modulationPhase) * UI_SOUND_MOD_DEPTH_HZ * (1.0 - t)
            val shimmerFrequency = UI_SOUND_SHIMMER_START_HZ +
                (UI_SOUND_SHIMMER_END_HZ - UI_SOUND_SHIMMER_START_HZ) * t
            energyPhase += (2.0 * PI * frequency) / SOUND_SAMPLE_RATE
            shimmerPhase += (2.0 * PI * shimmerFrequency) / SOUND_SAMPLE_RATE

            val tremolo = UI_SOUND_TREMOLO_BASE + sin(modulationPhase) * UI_SOUND_TREMOLO_DEPTH
            val core = sin(energyPhase) * UI_SOUND_CORE_GAIN
            val growl = sin(energyPhase * UI_SOUND_GROWL_MULTIPLIER) * growlGain
            val shimmer = sin(shimmerPhase) * shimmerGain
            val air = ((Random.nextDouble() * 2.0) - 1.0) * noiseGain * (1.0 - t).pow(0.45)
            val output = bitCrush((core + growl + shimmer + air) * envelope * tremolo * outputGain, UI_SOUND_CRUSH_LEVELS)
            samples[index] = toPcm16(output)
        }
        return samples
    }

    private fun createPageTurnSoundSamples(previous: Boolean, durationSeconds: Double): ShortArray {
        val sampleCount = max(1, (SOUND_SAMPLE_RATE * durationSeconds).roundToInt())
        val samples = ShortArray(sampleCount)
        var low = 0.0
        var band = 0.0
        val startFrequency = if (previous) PAGE_TURN_PREVIOUS_START_HZ else PAGE_TURN_NEXT_START_HZ
        val endFrequency = if (previous) PAGE_TURN_PREVIOUS_END_HZ else PAGE_TURN_NEXT_END_HZ
        val frequencyRatio = endFrequency / startFrequency

        for (index in samples.indices) {
            val t = index.toDouble() / sampleCount.toDouble()
            val envelope = sin(PI * t) * (1.0 - t).pow(PAGE_TURN_SOUND_DECAY)
            val noise = (Random.nextDouble() * 2.0) - 1.0
            val frequency = startFrequency * frequencyRatio.pow(t)
            val filterAmount = 2.0 * sin(PI * frequency / SOUND_SAMPLE_RATE)
            val high = noise - low - (PAGE_TURN_SOUND_FILTER_DAMPING * band)
            band += filterAmount * high
            low += filterAmount * band
            val output = band * envelope * PAGE_TURN_SOUND_NOISE_GAIN * PAGE_TURN_SOUND_OUTPUT_GAIN
            samples[index] = toPcm16(output)
        }
        return samples
    }

    private fun bitCrush(value: Double, levels: Double): Double {
        return (value * levels).roundToInt() / levels
    }

    private fun toPcm16(value: Double): Short {
        return (value * Short.MAX_VALUE).roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

    private enum class SyntheticSound {
        PAGE_PREVIOUS,
        PAGE_NEXT,
        UI_OPEN,
        UI_CLOSE,
        UI_PRESS,
    }

    private companion object {
        private const val SOUND_SAMPLE_RATE = 44100
        private const val SOUND_POOL_MAX_STREAMS = 4
        private const val WAV_RIFF_CHUNK_OVERHEAD_BYTES = 36
        private const val PAGE_TURN_PREVIOUS_SOUND_SECONDS = 0.14
        private const val PAGE_TURN_NEXT_SOUND_SECONDS = 0.19
        private const val PAGE_TURN_PREVIOUS_START_HZ = 900.0
        private const val PAGE_TURN_PREVIOUS_END_HZ = 1800.0
        private const val PAGE_TURN_NEXT_START_HZ = 1600.0
        private const val PAGE_TURN_NEXT_END_HZ = 3100.0
        private const val PAGE_TURN_SOUND_NOISE_GAIN = 0.12
        private const val PAGE_TURN_SOUND_OUTPUT_GAIN = 0.72
        private const val PAGE_TURN_SOUND_DECAY = 0.8
        private const val PAGE_TURN_SOUND_FILTER_DAMPING = 0.45
        private const val UI_OPEN_SOUND_SECONDS = 0.18
        private const val UI_OPEN_START_HZ = 220.0
        private const val UI_OPEN_END_HZ = 980.0
        private const val UI_OPEN_OUTPUT_GAIN = 0.46
        private const val UI_OPEN_SHIMMER_GAIN = 0.22
        private const val UI_OPEN_GROWL_GAIN = 0.24
        private const val UI_OPEN_NOISE_GAIN = 0.052
        private const val UI_OPEN_DECAY = 0.64
        private const val UI_CLOSE_SOUND_SECONDS = 0.15
        private const val UI_CLOSE_START_HZ = 880.0
        private const val UI_CLOSE_END_HZ = 240.0
        private const val UI_CLOSE_OUTPUT_GAIN = 0.40
        private const val UI_CLOSE_SHIMMER_GAIN = 0.14
        private const val UI_CLOSE_GROWL_GAIN = 0.30
        private const val UI_CLOSE_NOISE_GAIN = 0.042
        private const val UI_CLOSE_DECAY = 1.05
        private const val UI_PRESS_SOUND_SECONDS = 0.075
        private const val UI_PRESS_START_HZ = 520.0
        private const val UI_PRESS_END_HZ = 720.0
        private const val UI_PRESS_OUTPUT_GAIN = 0.34
        private const val UI_PRESS_SHIMMER_GAIN = 0.16
        private const val UI_PRESS_GROWL_GAIN = 0.18
        private const val UI_PRESS_NOISE_GAIN = 0.035
        private const val UI_PRESS_DECAY = 1.6
        private const val UI_SOUND_ATTACK_RATIO = 0.08
        private const val UI_SOUND_MOD_START_HZ = 34.0
        private const val UI_SOUND_MOD_END_HZ = 170.0
        private const val UI_SOUND_MOD_DEPTH_HZ = 92.0
        private const val UI_SOUND_SHIMMER_START_HZ = 1800.0
        private const val UI_SOUND_SHIMMER_END_HZ = 5200.0
        private const val UI_SOUND_CORE_GAIN = 0.72
        private const val UI_SOUND_GROWL_MULTIPLIER = 2.03
        private const val UI_SOUND_TREMOLO_BASE = 0.84
        private const val UI_SOUND_TREMOLO_DEPTH = 0.16
        private const val UI_SOUND_CRUSH_LEVELS = 44.0
    }
}
