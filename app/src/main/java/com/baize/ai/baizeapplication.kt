package com.baize.ai

import android.app.Application
import com.baize.ai.inference.CloudInferenceProvider
import com.baize.ai.inference.ModelManager
import com.baize.ai.soul.core.SoulManager
import com.baize.ai.soul.memory.MemoryBridge
import com.baize.ai.soul.memory.MemoryManager
import com.baize.ai.network.ApiClient

/**
 * BaizeApplication v4 — 加入 MemoryBridge
 */
class BaizeApplication : Application() {

    lateinit var soulManager: SoulManager
        private set

    lateinit var memoryManager: MemoryManager
        private set

    lateinit var memoryBridge: MemoryBridge
        private set

    lateinit var modelManager: ModelManager
        private set

    lateinit var cloudProvider: CloudInferenceProvider
        private set

    override fun onCreate() {
        super.onCreate()

        // 初始化云端 API 客户端
        ApiClient.init(this)

        // 初始化灵魂管理器
        soulManager = SoulManager(this)

        // 初始化记忆管理器（旧）
        memoryManager = MemoryManager(this)

        // 初始化记忆桥接层（新）
        memoryBridge = MemoryBridge(this)

        // 初始化模型管理器
        modelManager = ModelManager(this)

        // 初始化云端推理提供者
        cloudProvider = CloudInferenceProvider(this)

        // 预创建数据库
        memoryManager.initialize()
        memoryBridge.initialize()
    }

    override fun onTerminate() {
        super.onTerminate()
        memoryManager.close()
        memoryBridge.close()
    }
}
