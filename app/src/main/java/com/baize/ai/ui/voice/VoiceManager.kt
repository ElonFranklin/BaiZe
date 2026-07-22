package com.baize.ai.ui.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class VoiceManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceManager"
        const val STT_REQUEST_CODE = 1001

        // 更宽容的静音判停（ROM 可能忽略）
        private const val SILENCE_COMPLETE_MS = 2200L
        private const val SILENCE_POSSIBLY_COMPLETE_MS = 2200L
        private const val MIN_SPEECH_LENGTH_MS = 1500L

        // 优先 Google 识别服务，避免厂商自带“灰横杠”识别弹层
        private val PREFERRED_RECOGNIZER_SERVICES = listOf(
            ComponentName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
            ),
            ComponentName(
                "com.google.android.as",
                "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"
            )
        )
    }

    // ==================== TTS ====================
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    interface TtsCallback {
        fun onTtsStart(text: String)
        fun onTtsDone(text: String)
        fun onTtsError(error: String)
    }
    private var ttsCallback: TtsCallback? = null

    fun initTts(onReady: (() -> Unit)? = null) {
        Log.i(TAG, "开始初始化 TTS..., context=${context.javaClass.simpleName}")

        val engineIntent = Intent("android.speech.tts.TextToSpeech.Engine.ACTION_CHECK_TTS_DATA")
        try {
            val resolveInfo = context.packageManager.resolveActivity(engineIntent, 0)
            Log.i(TAG, "TTS 引擎检查: ${resolveInfo?.activityInfo?.name ?: "未找到"}")
        } catch (e: Exception) {
            Log.w(TAG, "TTS 引擎检查失败: ${e.message}")
        }

        tts = TextToSpeech(context) { status ->
            Log.i(TAG, "TTS 初始化回调: status=$status")
            when (status) {
                TextToSpeech.SUCCESS -> {
                    ttsReady = true
                    val ttsPrefs = context.getSharedPreferences("baize_tts", Context.MODE_PRIVATE)
                    val savedSpeed = ttsPrefs.getFloat("tts_speed", 1.0f)
                    tts?.setPitch(1.0f)
                    tts?.setSpeechRate(savedSpeed)

                    val result = tts?.setLanguage(Locale.CHINESE)
                    Log.i(TAG, "TTS 语言设置: $result")
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "中文不支持，尝试默认语言")
                        tts?.setLanguage(Locale.getDefault())
                    }

                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) { Log.d(TAG, "TTS 开始") }
                        override fun onDone(utteranceId: String?) {
                            Log.d(TAG, "TTS 完成")
                            ttsCallback?.onTtsDone(utteranceId ?: "")
                        }
                        override fun onError(utteranceId: String?) {
                            Log.e(TAG, "TTS 播放错误")
                            ttsCallback?.onTtsError("播放失败")
                        }
                    })

                    Log.i(TAG, "TTS 初始化成功")
                    onReady?.invoke()
                }
                TextToSpeech.ERROR -> {
                    Log.e(TAG, "TTS 初始化失败: 引擎错误")
                    ttsCallback?.onTtsError("TTS 引擎错误，请安装 Google TTS 或讯飞语记")
                }
                else -> {
                    Log.e(TAG, "TTS 初始化失败: $status")
                }
            }
        }
    }

    private var ttsRetryCount = 0
    private val TTS_MAX_RETRY = 2

    fun speak(text: String, callback: TtsCallback? = null) {
        if (!ttsReady || tts == null) {
            if (ttsRetryCount >= TTS_MAX_RETRY) {
                Log.e(TAG, "TTS 重试次数超限($ttsRetryCount)，放弃")
                ttsRetryCount = 0
                callback?.onTtsError("TTS 初始化失败，请检查语音引擎")
                return
            }
            ttsRetryCount++
            Log.w(TAG, "TTS 未就绪，第${ttsRetryCount}次重试")
            initTts { speak(text, callback) }
            return
        }
        ttsRetryCount = 0
        ttsCallback = callback
        callback?.onTtsStart(text)
        val clean = text.replace(Regex("[#*_`\\[\\]()]"), "").replace(Regex("<[^>]+>"), "").trim().take(3000)
        if (clean.isEmpty()) {
            Log.w(TAG, "TTS 文本为空，跳过")
            callback?.onTtsDone("")
            return
        }
        Log.i(TAG, "TTS 播放: len=${clean.length}, hash=${clean.hashCode()}")
        val result = tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "baize_tts")
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "TTS speak 失败")
            callback?.onTtsError("语音播放失败")
        }
    }

    fun stopTts() { tts?.stop() }

    fun updateSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }
    fun isTtsReady(): Boolean = ttsReady

    // ==================== STT（App 内静默识别，尽量不弹系统 UI） ====================
    interface SttCallback {
        fun onListeningStart()
        fun onPartialResult(partial: String)
        fun onResult(result: String)
        fun onListeningEnd()
        fun onError(error: String)
    }

    private var sttCallback: SttCallback? = null
    @Volatile private var sttListening = false
    @Volatile private var manualStop = false
    @Volatile private var finalDelivered = false
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var latestPartial: String = ""
    private var stopFallbackRunnable: Runnable? = null

    fun startListening(
        launcher: androidx.activity.result.ActivityResultLauncher<Intent>? = null,
        callback: SttCallback? = null
    ) {
        // SpeechRecognizer 必须在主线程创建
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startListening(launcher, callback) }
            return
        }

        // 已在听：忽略重复开始
        if (sttListening && speechRecognizer != null) {
            Log.d(TAG, "已在录音中，忽略重复 startListening")
            return
        }

        cleanupRecognizer()
        cancelStopFallback()
        sttCallback = callback
        sttListening = true
        manualStop = false
        finalDelivered = false
        latestPartial = ""
        callback?.onListeningStart()

        try {
            val recognizer = createBestSpeechRecognizer()
            if (recognizer == null) {
                // 关键：不再默认弹出系统识别方框（灰横杠 UI）
                // 系统 Intent UI 体验差，改为明确错误，引导安装/启用 Google 语音
                Log.e(TAG, "无可用静默 SpeechRecognizer，拒绝 Intent UI 降级")
                sttListening = false
                callback?.onError("当前系统无法静默语音识别。请安装/启用 Google 应用后重试")
                callback?.onListeningEnd()
                return
            }
            speechRecognizer = recognizer

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "语音识别就绪")
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "开始说话")
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Log.d(TAG, "系统判定说话结束")
                }

                override fun onError(error: Int) {
                    if (manualStop) {
                        val fallback = latestPartial.trim()
                        Log.w(TAG, "手动停止后收到 error=$error, partial='$fallback'")
                        if (!finalDelivered) {
                            if (fallback.isNotEmpty()) {
                                deliverResult(fallback)
                            } else {
                                deliverError(mapError(error))
                            }
                        }
                        return
                    }

                    val errorMsg = mapError(error)
                    Log.e(TAG, "语音识别错误: $errorMsg ($error)")
                    if (!finalDelivered) {
                        deliverError(errorMsg)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val result = matches?.firstOrNull()?.trim().orEmpty()
                    Log.i(TAG, "语音识别结果: len=${result.length}")
                    if (result.isNotEmpty()) {
                        deliverResult(result)
                    } else if (latestPartial.isNotBlank()) {
                        deliverResult(latestPartial.trim())
                    } else {
                        deliverError("未识别到语音")
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull()?.trim().orEmpty()
                    if (partial.isNotEmpty()) {
                        latestPartial = partial
                        sttCallback?.onPartialResult(partial)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = buildRecognizeIntent()
            recognizer.startListening(intent)
            Log.i(TAG, "开始 App 内静默语音识别")
        } catch (e: Exception) {
            Log.e(TAG, "STT 启动失败: ${e.message}", e)
            sttListening = false
            cleanupRecognizer()
            sttCallback?.onError("语音识别失败: ${e.message}")
            sttCallback?.onListeningEnd()
        }
    }

    /**
     * 手动结束：请求最终结果。
     */
    fun stopListening() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stopListening() }
            return
        }

        if (!sttListening && speechRecognizer == null) {
            sttCallback?.onListeningEnd()
            return
        }

        manualStop = true
        Log.i(TAG, "手动停止识别, partial='${latestPartial}'")

        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "stopListening 异常: ${e.message}")
        }

        cancelStopFallback()
        val runnable = Runnable {
            if (!finalDelivered) {
                val fallback = latestPartial.trim()
                if (fallback.isNotEmpty()) {
                    Log.w(TAG, "停止后超时，使用 partial 兜底")
                    deliverResult(fallback)
                } else {
                    Log.w(TAG, "停止后超时且无 partial")
                    deliverError("未识别到语音")
                }
            }
        }
        stopFallbackRunnable = runnable
        mainHandler.postDelayed(runnable, 1200)
    }

    fun cancelListening() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { cancelListening() }
            return
        }
        manualStop = true
        cancelStopFallback()
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        if (!finalDelivered) {
            finalDelivered = true
            sttListening = false
            cleanupRecognizer()
            sttCallback?.onListeningEnd()
        }
    }

    fun handleSttResult(requestCode: Int, resultCode: Int, data: Intent?) {
        // 保留兼容，但默认流程不再走系统识别 Activity
        if (requestCode != STT_REQUEST_CODE) return
        sttListening = false
        if (resultCode == android.app.Activity.RESULT_OK) {
            val matches = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val result = matches?.firstOrNull()?.trim().orEmpty()
            if (result.isNotEmpty()) sttCallback?.onResult(result)
            else sttCallback?.onError("未识别到语音")
        } else {
            sttCallback?.onError("语音识别取消")
        }
        sttCallback?.onListeningEnd()
    }

    fun isSttListening(): Boolean = sttListening

    private fun createBestSpeechRecognizer(): SpeechRecognizer? {
        // 1) 优先 Google 识别服务（通常无厂商灰框 UI）
        for (component in PREFERRED_RECOGNIZER_SERVICES) {
            if (isRecognitionServiceAvailable(component)) {
                try {
                    val r = SpeechRecognizer.createSpeechRecognizer(context, component)
                    if (r != null) {
                        Log.i(TAG, "使用识别服务: ${component.packageName}/${component.className}")
                        return r
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "创建指定识别服务失败: ${component.packageName}, ${e.message}")
                }
            }
        }

        // 2) 默认 SpeechRecognizer（部分 ROM 可能仍带自有浮层，但比 Intent 方框好）
        return try {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.i(TAG, "使用系统默认 SpeechRecognizer")
                SpeechRecognizer.createSpeechRecognizer(context)
            } else {
                Log.w(TAG, "SpeechRecognizer 不可用")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建默认 SpeechRecognizer 失败: ${e.message}")
            null
        }
    }

    private fun isRecognitionServiceAvailable(component: ComponentName): Boolean {
        return try {
            val intent = Intent(RecognitionService.SERVICE_INTERFACE).setComponent(component)
            val list = context.packageManager.queryIntentServices(intent, PackageManager.MATCH_ALL)
            list.isNotEmpty()
        } catch (e: Exception) {
            // 退化：包存在就尝试
            try {
                context.packageManager.getPackageInfo(component.packageName, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun buildRecognizeIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // 尽量降低中途静音自动结束
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_COMPLETE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_POSSIBLY_COMPLETE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MIN_SPEECH_LENGTH_MS)
        }
    }

    private fun deliverResult(result: String) {
        if (finalDelivered) return
        finalDelivered = true
        sttListening = false
        cancelStopFallback()
        cleanupRecognizer()
        sttCallback?.onResult(result)
        sttCallback?.onListeningEnd()
    }

    private fun deliverError(message: String) {
        if (finalDelivered) return
        finalDelivered = true
        sttListening = false
        cancelStopFallback()
        cleanupRecognizer()
        sttCallback?.onError(message)
        sttCallback?.onListeningEnd()
    }

    private fun cancelStopFallback() {
        stopFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        stopFallbackRunnable = null
    }

    private fun cleanupRecognizer() {
        try {
            speechRecognizer?.setRecognitionListener(null)
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        } finally {
            speechRecognizer = null
        }
    }

    private fun mapError(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"
            SpeechRecognizer.ERROR_AUDIO -> "录音错误"
            SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
            SpeechRecognizer.ERROR_SERVER -> "服务端错误"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别引擎忙"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
            SpeechRecognizer.ERROR_NETWORK -> "网络错误"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
            else -> "识别错误: $error"
        }
    }

    // ==================== 清理 ====================
    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        cancelListening()
    }
}
