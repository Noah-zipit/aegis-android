package com.aegis.browser.ai

/**
 * JNI bridge to the `aegis-llama` native library (llama.cpp, CPU only).
 *
 * Streaming is real: [nativeGenerateStream] invokes [GenerateCallback.onToken] from the
 * calling thread once per emitted token. Call it from a background thread.
 */
object LlamaBridge {
    init {
        System.loadLibrary("aegis-llama")
    }

    /**
     * Callbacks from the native generation thread. [onStatus] fires at stage
     * boundaries ("prefill", "generating") so the UI can show where a slow or
     * stuck generation actually is instead of a bare "…".
     */
    interface GenerateCallback {
        fun onToken(token: String)
        fun onStatus(stage: String)
    }

    /** Load the GGUF at [modelPath] (CPU, n_gpu_layers = 0). Returns true on success. */
    external fun nativeInit(modelPath: String): Boolean

    /**
     * Generate a reply, calling [callback] per emitted token.
     * @return number of tokens generated, or -1 on error.
     */
    external fun nativeGenerateStream(prompt: String, maxTokens: Int, callback: GenerateCallback): Int

    /** Ask an in-flight generation to stop at the next token boundary. */
    external fun nativeStop()

    /** Free the model and context. */
    external fun nativeUnload()
}
