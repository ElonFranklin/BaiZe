package com.baize.ai.inference

import android.util.Log
import com.baize.ai.soul.core.PromptMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * ClusterInferenceProvider - LanCluster 分布式推理（占位）
 *
 * 完整实现在 LanCluster 项目中。
 * 此处为占位，避免 baize 依赖 lancluster-core 模块。
 * 后续可通过 AAR / Maven 依赖接入。
 */
class ClusterInferenceProvider : InferenceProvider {

    companion object {
        private const val TAG = "ClusterInference"
    }

    @Volatile
    private var isInitialized = false

    private var masterHost: String = ""
    private var masterPort: Int = 9300

    override suspend fun initialize(config: InferenceConfig): Result<Unit> = withContext(Dispatchers.IO) {
        val parts = config.modelPath.split(":")
        masterHost = parts[0]
        masterPort = if (parts.size > 1) parts[1].toIntOrNull() ?: 9300 else 9300

        Log.w(TAG, "ClusterInferenceProvider is a stub. Full implementation requires lancluster-core.")
        Result.failure(Exception("ClusterInferenceProvider not available in this build"))
    }

    override suspend fun generate(
        messages: List<PromptMessage>,
        config: GenerateConfig?
    ): Result<String> = withContext(Dispatchers.IO) {
        Result.failure(Exception("ClusterInferenceProvider not available in this build"))
    }

    override suspend fun generateStream(
        messages: List<PromptMessage>,
        config: GenerateConfig?
    ): Flow<String> = flow {
        emit("[Error: ClusterInferenceProvider not available in this build]")
    }.flowOn(Dispatchers.IO)

    override suspend fun unload(): Result<Unit> {
        isInitialized = false
        return Result.success(Unit)
    }

    override fun isInitialized(): Boolean = false

    override fun getInfo(): EngineInfo = EngineInfo(
        name = "LanCluster",
        version = "1.0",
        backend = "lancluster-grpc (stub)",
        modelLoaded = false,
        modelPath = null
    )
}
