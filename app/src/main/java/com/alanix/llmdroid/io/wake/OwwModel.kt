package com.alanix.llmdroid.io.wake

import org.tensorflow.lite.Interpreter
import java.io.File

// Ported from Dicio-android (github.com/Stypox/dicio-android)
class OwwModel(
    melSpectrogramPath: File,
    embeddingPath: File,
    wakeWordPath: File,
) : AutoCloseable {

    private val melInterpreter: Interpreter
    private val embInterpreter: Interpreter
    private val wakeInterpreter: Interpreter

    private var accumulatedMelOutputs: Array<Array<FloatArray>> = Array(EMB_INPUT_COUNT) { arrayOf() }
    private var accumulatedEmbOutputs: Array<FloatArray> = Array(WAKE_INPUT_COUNT) { floatArrayOf() }
    private var isClosed = false

    init {
        melInterpreter = loadModel(melSpectrogramPath, intArrayOf(1, MEL_INPUT_COUNT))
        try {
            embInterpreter = loadModel(embeddingPath)
        } catch (t: Throwable) {
            melInterpreter.close()
            throw t
        }
        try {
            wakeInterpreter = loadModel(wakeWordPath)
        } catch (t: Throwable) {
            melInterpreter.close()
            embInterpreter.close()
            throw t
        }
    }

    fun processFrame(audio: FloatArray): Float {
        synchronized(this) {
            if (isClosed) return 0f
            require(audio.size == MEL_INPUT_COUNT) {
                "OwwModel requires exactly $MEL_INPUT_COUNT samples"
            }

            val melOutput = Array(MEL_OUTPUT_COUNT) { FloatArray(MEL_FEATURE_SIZE) }
            melInterpreter.run(arrayOf(audio), arrayOf(arrayOf(melOutput)))
            for (i in 0..<EMB_INPUT_COUNT) {
                accumulatedMelOutputs[i] = if (i < EMB_INPUT_COUNT - MEL_OUTPUT_COUNT) {
                    accumulatedMelOutputs[i + MEL_OUTPUT_COUNT]
                } else {
                    melOutput[i - EMB_INPUT_COUNT + MEL_OUTPUT_COUNT]
                        .map { floatArrayOf((it / 10.0f) + 2.0f) }
                        .toTypedArray()
                }
            }
            if (accumulatedMelOutputs[0].isEmpty()) return 0f

            val embOutput = Array(EMB_OUTPUT_COUNT) { FloatArray(EMB_FEATURE_SIZE) }
            embInterpreter.run(arrayOf(accumulatedMelOutputs), arrayOf(arrayOf(embOutput)))
            for (i in 0..<WAKE_INPUT_COUNT) {
                accumulatedEmbOutputs[i] = if (i < WAKE_INPUT_COUNT - EMB_OUTPUT_COUNT) {
                    accumulatedEmbOutputs[i + EMB_OUTPUT_COUNT]
                } else {
                    embOutput[i - WAKE_INPUT_COUNT + EMB_OUTPUT_COUNT]
                }
            }
            if (accumulatedEmbOutputs[0].isEmpty()) return 0f

            val wakeOutput = FloatArray(1)
            wakeInterpreter.run(arrayOf(accumulatedEmbOutputs), arrayOf(wakeOutput))
            return wakeOutput[0]
        }
    }

    override fun close() {
        synchronized(this) {
            isClosed = true
            melInterpreter.close()
            embInterpreter.close()
            wakeInterpreter.close()
        }
    }

    companion object {
        // mel model: [1, MEL_INPUT_COUNT] -> [1, 1, MEL_OUTPUT_COUNT, 32]
        const val MEL_INPUT_COUNT = 512 + 160 * 4  // 1152 samples @ 16kHz = 72ms
        const val MEL_OUTPUT_COUNT = (MEL_INPUT_COUNT - 512) / 160 + 1
        const val MEL_FEATURE_SIZE = 32

        // embedding model: [1, 76, 32, 1] -> [1, 1, 1, 96]
        const val EMB_INPUT_COUNT = 76
        const val EMB_OUTPUT_COUNT = 1
        const val EMB_FEATURE_SIZE = 96

        // wake model: [1, 16, 96] -> [1, 1]
        const val WAKE_INPUT_COUNT = 16

        private fun loadModel(file: File, inputDims: IntArray? = null): Interpreter {
            val interp = Interpreter(file)
            if (inputDims != null) interp.resizeInput(0, inputDims)
            interp.allocateTensors()
            return interp
        }
    }
}
