package com.example.jaecoovoice.engine

/** JNI of libjaecoo-espeak.so (src/main/cpp). Not thread-safe: go through [EspeakPhonemizer]. */
internal object EspeakNative {
    init {
        System.loadLibrary("jaecoo-espeak")
    }

    /** espeak_Initialize with [dataDir] (an espeak-ng-data folder). > 0: sample rate; <= 0: failed. */
    external fun nativeInit(dataDir: String): Int

    /**
     * espeak-ng's clauses for [text] in [voice], each `"<terminator in decimal>|<IPA phonemes>"`.
     * Empty when the voice is unknown.
     */
    external fun nativeClauses(text: String, voice: String): Array<String>

    external fun nativeTerminate()
}
