/**
 * AudioWorklet processor for system audio capture.
 *
 * Receives raw float32 PCM from MediaStreamDestination and converts it to
 * Int16Array (16-bit PCM), then posts it back to the main thread.
 *
 * This processor is loaded from the /public directory by AudioCapture.ts.
 */
class AudioCaptureProcessor extends AudioWorkletProcessor {
  process(inputs, _outputs, _parameters) {
    const input = inputs[0]
    if (!input || input.length === 0 || input[0].length === 0) {
      return true
    }

    const channelData = input[0]
    const pcm = new Int16Array(channelData.length)
    for (let i = 0; i < channelData.length; i++) {
      const s = Math.max(-1, Math.min(1, channelData[i]))
      pcm[i] = s < 0 ? s * 0x8000 : s * 0x7fff
    }

    this.port.postMessage({ pcm: pcm.buffer }, [pcm.buffer])
    return true
  }
}

registerProcessor('audio-capture-processor', AudioCaptureProcessor)
