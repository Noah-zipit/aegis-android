package com.aegis.browser.ai

/**
 * JNI bridge to the `aegis-llama` native library (llama.cpp, CPU only).
 *
 * Streaming is real: [nativeGenerateStream] invokes [TokenCallback.onToken] from the
 * calling thread once per emitted token. Call it from a background thread.
 */
object LlamaBridge {
    init {
        System.loadLibrary("aegis-llama")
    }

    fun interface TokenCallback {
        fun onToken(token: String)
    }

    /** Load the GGUF at [modelPath] (CPU, n_gpu_layers = 0). Returns true on success. */
    external fun nativeInit(modelPath: String): Boolean

    /**
     * Generate a reply, calling [callback] per emitted token.
     * @return number of tokens generated, or -1 on error.
     */
    external fun nativeGenerateStream(prompt: String, maxTokens: Int, callback: TokenCallback): Int

    /** Ask an in-flight generation to stop at the next token boundary. */
    external fun nativeStop()

    /** Free the model and context. */
    external fun nativeUnload()
}
