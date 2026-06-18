package com.penumbraos.hook

import android.os.SystemClock
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicLong

/**
 * Streaming replacement for the embedded Microsoft TTS engine
 */
object HumaneTtsHooks {
    private const val TAG = "PenumbraTTS"
    private const val SAMPLE_RATE_HZ = 24000
    private const val AUDIO_FORMAT_PCM_16BIT = 2
    private const val CHANNEL_COUNT_MONO = 1
    private const val TARGET_READ_BYTES = 2400 // 50ms at 24kHz 16-bit mono PCM
    private const val MAX_READ_BYTES = 16000

    private val nextRequestId = AtomicLong(1)
    private val requestIdByThread = ThreadLocal<Long?>()
    private val onSynthesizeStartByThread = ThreadLocal<Long?>()
    private val initializeStartByThread = ThreadLocal<Long?>()
    private val loadLanguageStartByThread = ThreadLocal<Long?>()

    fun install(cl: ClassLoader) {
        Log.w(TAG, "Installing Humane TTS hooks...")
        hookHumaneTtsService(cl)
        Log.w(TAG, "Humane TTS hooks installed")
    }

    private fun hookHumaneTtsService(cl: ClassLoader) {
        val serviceClass = loadClassOrNull(cl, "humane.voice.tts.HumaneTTSService") ?: return
        val synthesisRequestClass = loadClassOrNull(cl, "android.speech.tts.SynthesisRequest") ?: return
        val synthesisCallbackClass = loadClassOrNull(cl, "android.speech.tts.SynthesisCallback") ?: return

        hookMethod(serviceClass, "onSynthesizeText", synthesisRequestClass, synthesisCallbackClass,
            before = { param ->
                val requestId = nextRequestId.getAndIncrement()
                val startMs = nowMs()
                requestIdByThread.set(requestId)
                onSynthesizeStartByThread.set(startMs)

                val request = param.args.getOrNull(0)
                val callback = param.args.getOrNull(1)
                if (request != null && callback != null) {
                    val handled = tryAudioDataStreamSynthesis(param.thisObject, request, callback, requestId)
                    if (handled) {
                        param.result = null
                    }
                }
            },
            after = { param ->
                val requestId = requestIdByThread.get()
                val throwable = param.throwable
                if (throwable != null) {
                    val startMs = onSynthesizeStartByThread.get() ?: 0L
                    Log.w(
                        TAG,
                        "id=$requestId tts hookThrowable totalMs=${elapsedSince(startMs)} " +
                            "throwable=${throwable.javaClass.name}:${throwable.message}"
                    )
                }
                requestIdByThread.remove()
                onSynthesizeStartByThread.remove()
            }
        )

        hookMethod(serviceClass, "initializeSynthesizer",
            before = {
                initializeStartByThread.set(nowMs())
            },
            after = { param ->
                val startMs = initializeStartByThread.get() ?: 0L
                val synthesizer = getDeclaredField(param.thisObject, "mSynthesizer")
                Log.w(
                    TAG,
                    "id=${requestId()} initializeSynthesizer end durationMs=${elapsedSince(startMs)} " +
                        "throwable=${param.throwable?.javaClass?.name ?: "none"} synthesizer=${synthesizer?.javaClass?.name}"
                )
                initializeStartByThread.remove()
            }
        )

        hookMethod(serviceClass, "onLoadLanguage", String::class.java, String::class.java, String::class.java,
            before = {
                loadLanguageStartByThread.set(nowMs())
            },
            after = { param ->
                val result = param.result as? Int
                val throwable = param.throwable
                if (throwable != null || result == -2 || result == -1) {
                    val startMs = loadLanguageStartByThread.get() ?: 0L
                    Log.w(
                        TAG,
                        "id=${requestId()} tts languageLoadFailed durationMs=${elapsedSince(startMs)} " +
                            "result=$result throwable=${throwable?.javaClass?.name ?: "none"}"
                    )
                }
                loadLanguageStartByThread.remove()
            }
        )
    }

    private fun tryAudioDataStreamSynthesis(service: Any, request: Any, synthesisCallback: Any, requestId: Long): Boolean {
        val startMs = nowMs()
        var callbackStarted = false
        var result: Any? = null
        var audioStream: Any? = null

        return try {
            val text = callNoArg(request, "getCharSequenceText")?.toString() ?: return false

            val language = callNoArg(request, "getLanguage") as? String ?: "eng"
            val country = callNoArg(request, "getCountry") as? String ?: "USA"
            val variant = callNoArg(request, "getVariant") as? String ?: ""
            val loadResult = callDeclared(service, "onLoadLanguage", arrayOf(String::class.java, String::class.java, String::class.java), language, country, variant) as? Int
            if (loadResult == -2 || loadResult == -1) {
                Log.w(TAG, "id=$requestId stream fallback/error: onLoadLanguage result=$loadResult")
                return false
            }

            val synthesizer = getDeclaredField(service, "mSynthesizer") ?: return false
            val ssml = buildSsml(service, text)

            result = synthesizer.javaClass.getMethod("StartSpeakingSsml", String::class.java).invoke(synthesizer, ssml)
            val startSpeakingMs = elapsedSince(startMs)

            val cl = service.javaClass.classLoader ?: return false
            val audioDataStreamClass = cl.loadClass("com.microsoft.cognitiveservices.speech.AudioDataStream")
            val synthesisResultClass = cl.loadClass("com.microsoft.cognitiveservices.speech.SpeechSynthesisResult")
            audioStream = audioDataStreamClass.getMethod("fromResult", synthesisResultClass).invoke(null, result)
            val readDataMethod = audioDataStreamClass.getMethod("readData", ByteArray::class.java)

            val androidMaxBufferSize = callNoArg(synthesisCallback, "getMaxBufferSize") as? Int ?: MAX_READ_BYTES
            val readBufferSize = minOf(TARGET_READ_BYTES, androidMaxBufferSize).coerceAtLeast(1024)
            val buffer = ByteArray(readBufferSize)
            var totalBytes = 0L
            var readCount = 0
            var firstAudioMs = -1L

            while (true) {
                val read = (readDataMethod.invoke(audioStream, buffer as Any) as Number).toLong()
                if (read <= 0L) {
                    break
                }
                if (firstAudioMs < 0L) {
                    firstAudioMs = elapsedSince(startMs)
                }
                if (!callbackStarted) {
                    val startResult = synthesisCallback.javaClass.getMethod(
                        "start",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    ).invoke(synthesisCallback, SAMPLE_RATE_HZ, AUDIO_FORMAT_PCM_16BIT, CHANNEL_COUNT_MONO) as? Int
                    if (startResult != 0) {
                        throw IllegalStateException("synthesisCallback.start returned $startResult")
                    }
                    callbackStarted = true
                    Log.w(TAG, "id=$requestId tts playbackStart firstAudioMs=$firstAudioMs startSpeakingMs=$startSpeakingMs len=${text.length}")
                }

                val audioResult = synthesisCallback.javaClass.getMethod(
                    "audioAvailable",
                    ByteArray::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).invoke(synthesisCallback, buffer, 0, read.toInt()) as? Int
                if (audioResult != 0) {
                    Log.w(
                        TAG,
                        "id=$requestId tts interrupted totalMs=${elapsedSince(startMs)} firstAudioMs=$firstAudioMs " +
                            "startSpeakingMs=$startSpeakingMs bytes=$totalBytes reads=$readCount callbackResult=$audioResult"
                    )
                    safeClose(audioStream)
                    safeClose(result)
                    return true
                }

                totalBytes += read
                readCount++
            }

            val reason = callNoArg(result, "getReason")?.toString()
            if (totalBytes <= 0L) {
                Log.w(TAG, "id=$requestId stream produced no audio reason=$reason")
                callNoArg(synthesisCallback, "error")
            } else {
                val doneResult = callNoArg(synthesisCallback, "done")
                Log.w(
                    TAG,
                    "id=$requestId tts done totalMs=${elapsedSince(startMs)} firstAudioMs=$firstAudioMs " +
                        "startSpeakingMs=$startSpeakingMs bytes=$totalBytes reads=$readCount reason=$reason " +
                        "callbackResult=$doneResult ${describeSynthesisResult(result)}"
                )
            }
            safeClose(audioStream)
            safeClose(result)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "id=$requestId stream synthesis failed started=$callbackStarted", t)
            safeClose(audioStream)
            safeClose(result)
            if (callbackStarted) {
                callNoArg(synthesisCallback, "error")
                true
            } else {
                false
            }
        }
    }

    private fun buildSsml(service: Any, text: String): String {
        val isValid = callDeclared(service, "isValidSSML", arrayOf(String::class.java), text) as? Boolean ?: false
        return if (isValid) {
            text
        } else {
            "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"en-US\">$text</speak>"
        }
    }

    private fun callDeclared(target: Any?, name: String, paramTypes: Array<Class<*>>, vararg args: Any?): Any? {
        if (target == null) return null
        return try {
            val method = target.javaClass.getDeclaredMethod(name, *paramTypes)
            method.isAccessible = true
            method.invoke(target, *args)
        } catch (_: Throwable) {
            null
        }
    }

    private fun getDeclaredField(target: Any?, name: String): Any? {
        if (target == null) return null
        return try {
            val field = target.javaClass.getDeclaredField(name)
            field.isAccessible = true
            field.get(target)
        } catch (_: Throwable) {
            null
        }
    }

    private fun safeClose(target: Any?) {
        try {
            if (target is AutoCloseable) {
                target.close()
            } else {
                callNoArg(target, "close")
            }
        } catch (_: Throwable) {
        }
    }

    private fun hookMethod(
        clazz: Class<*>,
        name: String,
        vararg paramTypes: Class<*>,
        before: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
        after: ((XC_MethodHook.MethodHookParam) -> Unit)? = null,
    ) {
        try {
            val method = clazz.getDeclaredMethod(name, *paramTypes)
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    before?.invoke(param)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    after?.invoke(param)
                }
            })
            Log.w(TAG, "  Hooked ${clazz.name}.$name(${paramTypes.joinToString { it.simpleName }})")
        } catch (t: Throwable) {
            Log.w(TAG, "  Failed to hook ${clazz.name}.$name: ${t.message}")
        }
    }


    private fun loadClassOrNull(cl: ClassLoader, className: String): Class<*>? {
        return try {
            cl.loadClass(className)
        } catch (t: Throwable) {
            Log.w(TAG, "  $className not found, skipping")
            null
        }
    }

    private fun describeSynthesisResult(result: Any?): String {
        if (result == null) return "result=null"
        return try {
            val reason = callNoArg(result, "getReason")
            val resultId = callNoArg(result, "getResultId")
            val audioLength = callNoArg(result, "getAudioLength")
            val properties = callNoArg(result, "getProperties")
            val firstByteMs = getPropertyByEnumName(properties, "SpeechServiceResponse_SynthesisFirstByteLatencyMs")
            val finishMs = getPropertyByEnumName(properties, "SpeechServiceResponse_SynthesisFinishLatencyMs")
            val underrunMs = getPropertyByEnumName(properties, "SpeechServiceResponse_SynthesisUnderrunTimeMs")
            val connectionMs = getPropertyByEnumName(properties, "SpeechServiceResponse_SynthesisConnectionLatencyMs")
            val networkMs = getPropertyByEnumName(properties, "SpeechServiceResponse_SynthesisNetworkLatencyMs")
            val serviceMs = getPropertyByEnumName(properties, "SpeechServiceResponse_SynthesisServiceLatencyMs")
            val backend = getPropertyByEnumName(properties, "SpeechServiceResponse_SynthesisBackend")
            "reason=$reason resultId=$resultId audioLength=$audioLength firstByteMs=$firstByteMs " +
                "finishMs=$finishMs underrunMs=$underrunMs connectionMs=$connectionMs " +
                "networkMs=$networkMs serviceMs=$serviceMs backend=$backend"
        } catch (t: Throwable) {
            "result=${result.javaClass.name} describeError=${t.javaClass.simpleName}:${t.message}"
        }
    }

    private fun getPropertyByEnumName(properties: Any?, enumName: String): String {
        if (properties == null) return "null"
        return try {
            val classLoader = properties.javaClass.classLoader ?: return "noClassLoader"
            val propertyIdClass = classLoader.loadClass("com.microsoft.cognitiveservices.speech.PropertyId")
            @Suppress("UNCHECKED_CAST")
            val enumClass = propertyIdClass as Class<out Enum<*>>
            val propertyId = enumClass.enumConstants?.firstOrNull { it.name == enumName } ?: return "missingEnum"
            properties.javaClass.getMethod("getProperty", propertyIdClass).invoke(properties, propertyId)?.toString() ?: "null"
        } catch (t: Throwable) {
            "error:${t.javaClass.simpleName}"
        }
    }

    private fun callNoArg(target: Any?, name: String): Any? {
        if (target == null) return null
        return try {
            target.javaClass.getMethod(name).invoke(target)
        } catch (_: Throwable) {
            try {
                val method = target.javaClass.getDeclaredMethod(name)
                method.isAccessible = true
                method.invoke(target)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun requestId(): Long? = requestIdByThread.get()
    private fun nowMs(): Long = SystemClock.elapsedRealtime()
    private fun elapsedSince(startMs: Long): Long = if (startMs > 0L) nowMs() - startMs else -1L
}
