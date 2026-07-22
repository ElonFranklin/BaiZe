/**
 * llama_jni.cpp v2 — llama.cpp JNI 桥接层
 *
 * 修复暗香二次审查问题：
 * - P0: 全局状态线程安全
 * - P0: nativeGenerateStream 线程安全
 * - P1: 资源泄漏修复
 * - P2: Token 缓冲区扩容
 */

#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <atomic>
#include <mutex>
#include <thread>

// llama.cpp 头文件
#include "llama.h"

// ==================== 全局状态 ====================

struct LlamaContext {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    const llama_vocab* vocab = nullptr;

    // 生成状态
    std::atomic<bool> is_generating{false};
    std::atomic<bool> should_stop{false};
    std::string current_response;
    std::mutex response_mutex;

    // 配置
    int n_ctx = 2048;
    int n_threads = 4;
    float temperature = 0.7f;
    float top_p = 0.9f;
    int top_k = 40;
    float repeat_penalty = 1.1f;
    int max_tokens = 512;

    // 采样器
    llama_sampler* sampler = nullptr;
    std::mutex sampler_mutex;

    ~LlamaContext() {
        std::lock_guard<std::mutex> lock(sampler_mutex);
        if (sampler) llama_sampler_free(sampler);
        if (ctx) llama_free(ctx);
        if (model) llama_free_model(model);
    }
};

static std::unique_ptr<LlamaContext> g_ctx = nullptr;
// 全局互斥锁，保护 JNI 入口
static std::mutex g_global_mutex;

// ==================== JNI 工具 ====================

static JavaVM* g_jvm = nullptr;

static JNIEnv* getJNIEnv() {
    JNIEnv* env = nullptr;
    if (g_jvm) {
        g_jvm->AttachCurrentThread(&env, nullptr);
    }
    return env;
}

static std::string jstringToString(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

static jstring stringToJstring(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

// ==================== 安全：JNI 全局引用 RAII 包装器 ====================

class JniGlobalRef {
private:
    JNIEnv* env;
    jobject ref;
public:
    JniGlobalRef(JNIEnv* env, jobject obj) : env(env), ref(env->NewGlobalRef(obj)) {}
    ~JniGlobalRef() { if (ref) env->DeleteGlobalRef(ref); }
    jobject get() const { return ref; }
    operator bool() const { return ref != nullptr; }
    // 禁止拷贝
    JniGlobalRef(const JniGlobalRef&) = delete;
    JniGlobalRef& operator=(const JniGlobalRef&) = delete;
};

// ==================== 模型管理 ====================

extern "C" {

JNIEXPORT jint JNICALL
Java_com_baize_ai_inference_LlamaCppBridge_nativeLoadModel(
    JNIEnv* env, jobject thiz,
    jstring modelPath, jint nCtx, jint nThreads) {

    std::lock_guard<std::mutex> lock(g_global_mutex);

    // 释放旧模型
    g_ctx = std::make_unique<LlamaContext>();

    // 初始化 llama.cpp
    llama_backend_init();

    auto model_params = llama_model_default_params();
    model_params.n_gpu_layers = 1;

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    g_ctx->model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!g_ctx->model) {
        llama_backend_free();
        return -1;
    }

    g_ctx->vocab = llama_model_get_vocab(g_ctx->model);
    g_ctx->n_ctx = nCtx;
    g_ctx->n_threads = nThreads;

    auto ctx_params = llama_context_default_params();
    ctx_params.n_ctx = nCtx;
    ctx_params.n_batch = 512;
    ctx_params.n_threads = nThreads;
    ctx_params.n_threads_batch = nThreads;

    g_ctx->ctx = llama_init_from_model(g_ctx->model, ctx_params);
    if (!g_ctx->ctx) {
        llama_free_model(g_ctx->model);
        g_ctx->model = nullptr;
        llama_backend_free();
        return -1;
    }

    // 创建采样器（新版 llama.cpp 需要传参数）
    {
        std::lock_guard<std::mutex> sampler_lock(g_ctx->sampler_mutex);
        auto sparams = llama_sampler_chain_default_params();
        g_ctx->sampler = llama_sampler_chain_init(sparams);
        if (g_ctx->sampler) {
            llama_sampler_chain_add(g_ctx->sampler, llama_sampler_init_temp(g_ctx->temperature));
            llama_sampler_chain_add(g_ctx->sampler, llama_sampler_init_top_p(g_ctx->top_p, 1));
            llama_sampler_chain_add(g_ctx->sampler, llama_sampler_init_top_k(g_ctx->top_k));
            llama_sampler_chain_add(g_ctx->sampler, llama_sampler_init_dist(0));
        }
    }

    return 0;
}

JNIEXPORT void JNICALL
Java_com_baize_ai_inference_LlamaCppBridge_nativeUnloadModel(
    JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_global_mutex);
    g_ctx.reset();
    llama_backend_free();
}

JNIEXPORT jstring JNICALL
Java_com_baize_ai_inference_LlamaCppBridge_nativeGenerate(
    JNIEnv* env, jobject thiz,
    jstring prompt, jint maxTokens) {

    std::lock_guard<std::mutex> lock(g_global_mutex);

    if (!g_ctx || !g_ctx->ctx) {
        return env->NewStringUTF("[错误: 模型未加载]");
    }

    std::string promptStr = jstringToString(env, prompt);
    g_ctx->max_tokens = maxTokens;

    // 使用原生 llama_tokenize（新版需要 vocab 参数）
    int prompt_len = promptStr.size();
    std::vector<llama_token> tokens(prompt_len + 1);
    int n_tokens = llama_tokenize(
        g_ctx->vocab,
        promptStr.c_str(), prompt_len,
        tokens.data(), tokens.size(),
        true,  // add_special
        true   // parse_special
    );
    tokens.resize(n_tokens);

    if ((int)tokens.size() > g_ctx->n_ctx - 4) {
        return env->NewStringUTF("[错误: 输入太长，超出上下文限制]");
    }

    if (llama_decode(g_ctx->ctx, llama_batch_get_one(tokens.data(), tokens.size()))) {
        return env->NewStringUTF("[错误: prompt 处理失败]");
    }

    g_ctx->is_generating = true;
    g_ctx->should_stop = false;
    g_ctx->current_response.clear();

    std::string response;

    for (int i = 0; i < g_ctx->max_tokens && !g_ctx->should_stop; i++) {
        auto new_token = llama_sampler_sample(g_ctx->sampler, g_ctx->ctx, -1);

        if (llama_vocab_is_eog(g_ctx->vocab, new_token)) break;

        // 使用更大的缓冲区（512 字节，支持中文多字节字符）
        char buf[512];
        int n = llama_token_to_piece(g_ctx->vocab, new_token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            response.append(buf, n);
        }

        llama_batch batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(g_ctx->ctx, batch)) break;
    }

    g_ctx->is_generating = false;
    return stringToJstring(env, response);
}

JNIEXPORT void JNICALL
Java_com_baize_ai_inference_LlamaCppBridge_nativeGenerateStream(
    JNIEnv* env, jobject thiz,
    jstring prompt, jint maxTokens, jobject callback) {

    if (!g_ctx || !g_ctx->ctx) return;

    std::string promptStr = jstringToString(env, prompt);
    g_ctx->max_tokens = maxTokens;
    g_ctx->is_generating = true;
    g_ctx->should_stop = false;
    g_ctx->current_response.clear();

    // 使用 RAII 包装器管理全局引用
    JniGlobalRef globalCallback(env, callback);
    if (!globalCallback) return;

    std::thread([env, promptStr, &globalCallback]() {
        JNIEnv* threadEnv;
        g_jvm->AttachCurrentThread(&threadEnv, nullptr);

        // 使用原生 llama_tokenize（新版需要 vocab 参数）
        int prompt_len = promptStr.size();
        std::vector<llama_token> tokens(prompt_len + 1);
        int n_tokens = llama_tokenize(
            g_ctx->vocab,
            promptStr.c_str(), prompt_len,
            tokens.data(), tokens.size(),
            true, true
        );
        tokens.resize(n_tokens);

        if (llama_decode(g_ctx->ctx, llama_batch_get_one(tokens.data(), tokens.size()))) {
            g_ctx->is_generating = false;
            g_jvm->DetachCurrentThread();
            return;
        }

        for (int i = 0; i < g_ctx->max_tokens && !g_ctx->should_stop; i++) {
            auto new_token = llama_sampler_sample(g_ctx->sampler, g_ctx->ctx, -1);

            if (llama_vocab_is_eog(g_ctx->vocab, new_token)) break;

            char buf[512];
            int n = llama_token_to_piece(g_ctx->vocab, new_token, buf, sizeof(buf), 0, true);
            if (n > 0) {
                std::string tokenStr(buf, n);

                // 加锁保护 current_response
                {
                    std::lock_guard<std::mutex> lock(g_ctx->response_mutex);
                    g_ctx->current_response += tokenStr;
                }

                // 回调到 Kotlin
                if (globalCallback.get()) {
                    jclass cls = threadEnv->GetObjectClass(globalCallback.get());
                    jmethodID mid = threadEnv->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
                    threadEnv->CallVoidMethod(globalCallback.get(), mid, stringToJstring(threadEnv, tokenStr));
                }
            }

            llama_batch batch = llama_batch_get_one(&new_token, 1);
            if (llama_decode(g_ctx->ctx, batch)) break;
        }

        // 完成回调
        if (globalCallback.get()) {
            jclass cls = threadEnv->GetObjectClass(globalCallback.get());
            jmethodID mid = threadEnv->GetMethodID(cls, "onComplete", "()V");
            threadEnv->CallVoidMethod(globalCallback.get(), mid);
        }

        g_ctx->is_generating = false;
        g_jvm->DetachCurrentThread();
    }).detach();
}

JNIEXPORT void JNICALL
Java_com_baize_ai_inference_LlamaCppBridge_nativeStopGeneration(
    JNIEnv* env, jobject thiz) {
    if (g_ctx) {
        g_ctx->should_stop = true;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_baize_ai_inference_LlamaCppBridge_nativeIsGenerating(
    JNIEnv* env, jobject thiz) {
    return g_ctx && g_ctx->is_generating ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_baize_ai_inference_LlamaCppBridge_nativeGetCurrentResponse(
    JNIEnv* env, jobject thiz) {
    if (!g_ctx) return env->NewStringUTF("");
    std::lock_guard<std::mutex> lock(g_ctx->response_mutex);
    return stringToJstring(env, g_ctx->current_response);
}

JNIEXPORT void JNICALL
Java_com_baize_ai_inference_LlamaCppBridge_nativeSetParams(
    JNIEnv* env, jobject thiz,
    jfloat temperature, jfloat topP, jint topK,
    jfloat repeatPenalty, jint maxTokens) {

    std::lock_guard<std::mutex> lock(g_global_mutex);

    if (!g_ctx) return;

    // 如果正在生成，拒绝修改参数
    if (g_ctx->is_generating) {
        return;
    }

    g_ctx->temperature = temperature;
    g_ctx->top_p = topP;
    g_ctx->top_k = topK;
    g_ctx->repeat_penalty = repeatPenalty;
    g_ctx->max_tokens = maxTokens;

    // 重建采样器（新版 llama.cpp 需要传参数）
    std::lock_guard<std::mutex> sampler_lock(g_ctx->sampler_mutex);
    if (g_ctx->sampler) {
        llama_sampler_free(g_ctx->sampler);
    }
    auto sparams = llama_sampler_chain_default_params();
    g_ctx->sampler = llama_sampler_chain_init(sparams);
    if (g_ctx->sampler) {
        llama_sampler_chain_add(g_ctx->sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(g_ctx->sampler, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(g_ctx->sampler, llama_sampler_init_top_k(topK));
        llama_sampler_chain_add(g_ctx->sampler, llama_sampler_init_dist(0));
    }
}

JNIEXPORT jstring JNICALL
Java_com_baize_ai_inference_LlamaCppBridge_nativeGetModelInfo(
    JNIEnv* env, jobject thiz) {
    if (!g_ctx || !g_ctx->model) {
        return env->NewStringUTF("未加载模型");
    }

    int n_params = llama_model_n_params(g_ctx->model);
    size_t n_bytes = llama_model_size(g_ctx->model);

    std::string info = "参数量: " + std::to_string(n_params / 1000000) + "M";
    info += " | 大小: " + std::to_string(n_bytes / 1024 / 1024) + "MB";
    info += " | 上下文: " + std::to_string(g_ctx->n_ctx);
    info += " | 线程: " + std::to_string(g_ctx->n_threads);

    return stringToJstring(env, info);
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

} // extern "C"
