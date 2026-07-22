package com.baize.ai.inference

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * ModelManager v5 — 本地模型库管理器
 *
 * 支持多个 GGUF 模型：
 * 1. 扫描 models/ 和 Download/ 目录下的 .gguf 文件
 * 2. 用户可选择默认模型
 * 3. 按选中模型加载/卸载
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        private const val MODEL_DIR = "models"
        private const val LEGACY_MODEL_FILE = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
        private const val LEGACY_MODEL_URL = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
        private const val LEGACY_MODEL_SIZE_MB = 468
        private const val PREFS_NAME = "baize_local_model"
        private const val KEY_SELECTED_MODEL = "selected_model"
    }

    private val extractMutex = Mutex()
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ==================== 模型扫描 ====================

    /**
     * 扫描所有可用的本地 GGUF 模型
     * 搜索路径：内部存储 models/ → Download/
     */
    fun scanModels(): List<LocalModel> {
        val models = mutableListOf<LocalModel>()
        val seen = mutableSetOf<String>()

        // 1. 内部存储 models/ 目录
        val modelDir = getModelDir()
        if (modelDir.exists()) {
            modelDir.listFiles()?.filter { it.extension == "gguf" }?.forEach { file ->
                if (file.length() > 10 * 1024 * 1024) { // > 10MB
                    val name = file.nameWithoutExtension
                    if (seen.add(name)) {
                        models.add(
                            LocalModel(
                                name = name,
                                fileName = file.name,
                                path = file.absolutePath,
                                sizeMB = file.length() / 1024 / 1024,
                                location = "内部存储"
                            )
                        )
                    }
                }
            }
        }

        // 2. Download 目录
        val downloadPaths = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File("/sdcard/Download"),
            File("/storage/emulated/0/Download")
        )

        for (downloadDir in downloadPaths) {
            if (!downloadDir.exists()) continue
            downloadDir.listFiles()?.filter { it.extension == "gguf" }?.forEach { file ->
                if (file.length() > 10 * 1024 * 1024) {
                    val name = file.nameWithoutExtension
                    if (seen.add(name)) {
                        models.add(
                            LocalModel(
                                name = name,
                                fileName = file.name,
                                path = file.absolutePath,
                                sizeMB = file.length() / 1024 / 1024,
                                location = "Download"
                            )
                        )
                    }
                }
            }
        }

        // 按大小排序
        return models.sortedByDescending { it.sizeMB }
    }

    /**
     * 将模型从 Download 复制到内部存储
     */
    fun importModel(model: LocalModel): Result<Unit> {
        return try {
            val sourceFile = File(model.path)
            if (!sourceFile.exists()) {
                return Result.failure(Exception("源文件不存在: ${model.path}"))
            }

            val targetFile = File(getModelDir(), model.fileName)
            if (!targetFile.parentFile?.exists()!!) {
                targetFile.parentFile?.mkdirs()
            }

            sourceFile.inputStream().use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (targetFile.exists() && targetFile.length() > 10 * 1024 * 1024) {
                Log.i(TAG, "模型导入成功: ${targetFile.absolutePath} (${targetFile.length() / 1024 / 1024}MB)")
                Result.success(Unit)
            } else {
                targetFile.delete()
                Result.failure(Exception("导入后验证失败"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "模型导入失败", e)
            Result.failure(e)
        }
    }

    // ==================== 模型选择 ====================

    /**
     * 获取当前选中的模型文件名
     */
    fun getSelectedModel(): String {
        return prefs.getString(KEY_SELECTED_MODEL, LEGACY_MODEL_FILE) ?: LEGACY_MODEL_FILE
    }

    /**
     * 设置选中的模型
     */
    fun setSelectedModel(fileName: String) {
        prefs.edit().putString(KEY_SELECTED_MODEL, fileName).apply()
        Log.i(TAG, "选中模型: $fileName")
    }

    /**
     * 获取当前选中模型的完整路径
     */
    fun getSelectedModelPath(): String {
        val selected = getSelectedModel()

        // 先检查内部存储
        val internalFile = File(getModelDir(), selected)
        if (internalFile.exists() && internalFile.length() > 10 * 1024 * 1024) {
            return internalFile.absolutePath
        }

        // 再检查 Download
        val downloadPaths = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File("/sdcard/Download"),
            File("/storage/emulated/0/Download")
        )
        for (dir in downloadPaths) {
            val file = File(dir, selected)
            if (file.exists() && file.length() > 10 * 1024 * 1024) {
                return file.absolutePath
            }
        }

        // fallback 到默认模型
        return File(getModelDir(), LEGACY_MODEL_FILE).absolutePath
    }

    // ==================== 兼容旧逻辑 ====================

    fun getModelPath(): String = getSelectedModelPath()

    fun isModelReady(): Boolean {
        val modelFile = File(getSelectedModelPath())
        return modelFile.exists() && modelFile.length() > 10 * 1024 * 1024
    }

    fun getModelSize(): Long {
        val modelFile = File(getSelectedModelPath())
        return if (modelFile.exists()) modelFile.length() else 0
    }

    /**
     * 确保模型就位（兼容旧调用）
     */
    suspend fun ensureModelReady(
        onProgress: ((Int) -> Unit)? = null
    ): Result<Unit> = extractMutex.withLock {
        // 1. 内部存储已有
        if (isModelReady()) {
            Log.i(TAG, "模型已就位: ${getSelectedModel()}")
            return@withLock Result.success(Unit)
        }

        // 2. Download 目录有 → 自动复制
        val copied = copyFromDownload()
        if (copied) {
            Log.i(TAG, "模型已从 Download 目录复制到内部存储")
            return@withLock Result.success(Unit)
        }

        // 3. 从网络下载（仅对默认模型）
        val selected = getSelectedModel()
        if (selected == LEGACY_MODEL_FILE) {
            return@withLock withContext(Dispatchers.IO) {
                try {
                    Log.i(TAG, "开始从网络下载默认模型...")
                    downloadModel(onProgress)
                    Log.i(TAG, "模型下载完成: ${getModelPath()}")
                    Result.success(Unit)
                } catch (e: Exception) {
                    Log.e(TAG, "模型下载失败", e)
                    Result.failure(e)
                }
            }
        } else {
            return@withLock Result.failure(Exception("模型「$selected」未找到，请先导入"))
        }
    }

    private fun copyFromDownload(): Boolean {
        val selected = getSelectedModel()
        val downloadPaths = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File("/sdcard/Download"),
            File("/storage/emulated/0/Download")
        )

        for (downloadDir in downloadPaths) {
            if (!downloadDir.exists()) continue

            val sourceFile = File(downloadDir, selected)
            if (!sourceFile.exists() || sourceFile.length() < 10 * 1024 * 1024) continue

            Log.i(TAG, "在 Download 目录找到模型: ${sourceFile.absolutePath} (${sourceFile.length() / 1024 / 1024}MB)")

            try {
                val targetFile = File(getModelDir(), selected)
                if (!targetFile.parentFile?.exists()!!) {
                    targetFile.parentFile?.mkdirs()
                }

                sourceFile.inputStream().use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                if (targetFile.exists() && targetFile.length() > 10 * 1024 * 1024) {
                    Log.i(TAG, "复制成功: ${targetFile.absolutePath}")
                    return true
                } else {
                    targetFile.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "复制失败: ${e.message}")
            }
        }
        return false
    }

    private fun downloadModel(onProgress: ((Int) -> Unit)?) {
        val modelDir = getModelDir()
        if (!modelDir.exists()) modelDir.mkdirs()

        val targetFile = File(modelDir, LEGACY_MODEL_FILE)
        val tempFile = File(modelDir, "$LEGACY_MODEL_FILE.tmp")

        val availableSpace = modelDir.usableSpace
        val requiredSpace = LEGACY_MODEL_SIZE_MB.toLong() * 1024 * 1024
        if (availableSpace < requiredSpace * 1.2) {
            throw java.io.IOException("磁盘空间不足")
        }

        try {
            java.net.URL(LEGACY_MODEL_URL).openStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead

                        val progress = (totalBytes * 100 / requiredSpace).toInt().coerceAtMost(99)
                        if (totalBytes % (10 * 1024 * 1024) < 8192) {
                            Log.d(TAG, "下载进度: ${progress}%")
                            onProgress?.invoke(progress)
                        }
                    }
                    output.flush()
                }
            }

            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)
            onProgress?.invoke(100)

        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    fun deleteModel(): Boolean {
        val modelFile = File(getSelectedModelPath())
        return if (modelFile.exists()) { modelFile.delete(); true } else false
    }

    private fun getModelDir(): File = File(context.filesDir, MODEL_DIR)

    fun getModelInfo(): ModelInfo {
        val modelFile = File(getSelectedModelPath())
        val name = getSelectedModel()
        return ModelInfo(
            name = name,
            path = modelFile.absolutePath,
            sizeMB = if (modelFile.exists()) modelFile.length() / 1024 / 1024 else 0,
            isReady = isModelReady(),
            format = "GGUF",
            quantization = extractQuantization(name),
            parameters = extractParameters(name)
        )
    }

    private fun extractQuantization(fileName: String): String {
        val regex = Regex("(Q[\\d]+_[A-Z]+(?:_[A-Z]+)?)", RegexOption.IGNORE_CASE)
        return regex.find(fileName)?.value ?: "Unknown"
    }

    private fun extractParameters(fileName: String): String {
        val regex = Regex("(\\d+\\.?\\d*B)", RegexOption.IGNORE_CASE)
        return regex.find(fileName)?.value ?: "Unknown"
    }
}

/**
 * 本地模型信息
 */
data class LocalModel(
    val name: String,
    val fileName: String,
    val path: String,
    val sizeMB: Long,
    val location: String // "内部存储" 或 "Download"
)

data class ModelInfo(
    val name: String,
    val path: String,
    val sizeMB: Long,
    val isReady: Boolean,
    val format: String,
    val quantization: String,
    val parameters: String
)
