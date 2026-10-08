package org.techwithkaushik.formsnap.ai

class FrameSkipGate(
    private val processEveryNthFrame: Int = 2,
) {
    init {
        require(processEveryNthFrame >= 1)
    }

    private var frameIndex = 0L

    fun shouldProcess(): Boolean {
        val process = frameIndex % processEveryNthFrame == 0L
        frameIndex++
        return process
    }

    fun reset() {
        frameIndex = 0L
    }
}
