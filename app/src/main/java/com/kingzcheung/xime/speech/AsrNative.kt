package com.kingzcheung.xime.speech

/**
 * JNI bridge to the self-implemented streaming zipformer2 ASR
 * (libasr_jni.so). Reference algorithm: sherpa-onnx (Apache-2.0);
 * feature extraction: kaldi-native-fbank (Apache-2.0).
 *
 * Key2 个人精简版不再打包 asr_jni：加载失败不抛错（保持 [loaded]=false），
 * 调用方检查 [loaded] 优雅降级，避免 :asr 进程启动即崩溃。
 */
object AsrNative {
    /** 库加载成功与否；精简包为 false，调用方须先检查再调 external 方法。 */
    val loaded: Boolean = runCatching { System.loadLibrary("asr_jni") }.isSuccess

    external fun nativeCreate(
        encoder: String,
        decoder: String,
        joiner: String,
        tokens: String
    ): Long

    external fun nativeReset(handle: Long)

    external fun nativeAcceptPcm(handle: Long, pcm: ByteArray)

    external fun nativeGetPartial(handle: Long): String

    external fun nativeFinalize(handle: Long): String

    external fun nativeRelease(handle: Long)
}
