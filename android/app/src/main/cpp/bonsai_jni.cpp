#include <jni.h>
#include "llama.h"
#include <algorithm>
#include <string>
#include <vector>

struct BonsaiHandle {
    llama_model * model = nullptr;
    uint32_t context_size = 2048;
    int threads = 4;
};

static void throw_java(JNIEnv * env, const char * message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, message);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_shev_agent_model_BonsaiJni_nativeLoad(JNIEnv * env, jclass, jstring model_path, jint context_size, jint threads) {
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (!path) return 0;
    llama_backend_init();
    auto params = llama_model_default_params();
    params.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(path, params);
    env->ReleaseStringUTFChars(model_path, path);
    if (!model) {
        throw_java(env, "Не удалось загрузить веса Bonsai.");
        return 0;
    }
    auto * handle = new BonsaiHandle();
    handle->model = model;
    handle->context_size = static_cast<uint32_t>(context_size);
    handle->threads = std::max(1, static_cast<int>(threads));
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_shev_agent_model_BonsaiJni_nativeGenerate(JNIEnv * env, jclass, jlong raw, jstring prompt, jint max_tokens) {
    auto * handle = reinterpret_cast<BonsaiHandle *>(raw);
    if (!handle || !handle->model) {
        throw_java(env, "Bonsai runtime не загружен.");
        return nullptr;
    }
    const char * input = env->GetStringUTFChars(prompt, nullptr);
    if (!input) return nullptr;
    const int32_t input_size = env->GetStringUTFLength(prompt);
    const auto * vocab = llama_model_get_vocab(handle->model);
    std::vector<llama_token> tokens(static_cast<size_t>(input_size) + 32);
    int32_t token_count = llama_tokenize(vocab, input, input_size, tokens.data(),
        static_cast<int32_t>(tokens.size()), true, true);
    if (token_count < 0) {
        tokens.resize(static_cast<size_t>(-token_count));
        token_count = llama_tokenize(vocab, input, input_size, tokens.data(),
            static_cast<int32_t>(tokens.size()), true, true);
    }
    env->ReleaseStringUTFChars(prompt, input);
    if (token_count <= 0) {
        throw_java(env, "Не удалось токенизировать запрос Bonsai.");
        return nullptr;
    }

    auto context_params = llama_context_default_params();
    context_params.n_ctx = handle->context_size;
    context_params.n_batch = std::min<uint32_t>(handle->context_size, 256);
    context_params.n_ubatch = context_params.n_batch;
    context_params.n_threads = handle->threads;
    context_params.n_threads_batch = handle->threads;
    llama_context * context = llama_init_from_model(handle->model, context_params);
    if (!context) {
        throw_java(env, "Не удалось создать контекст Bonsai. Освободи память или уменьши контекст.");
        return nullptr;
    }

    std::string result;
    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(20));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.80f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    int status = llama_decode(context, llama_batch_get_one(tokens.data(), token_count));
    if (status == 0) {
        for (int i = 0; i < std::clamp(static_cast<int>(max_tokens), 1, 2048); ++i) {
            const llama_token token = llama_sampler_sample(sampler, context, -1);
            if (llama_vocab_is_eog(vocab, token)) break;
            char piece[512];
            int32_t piece_size = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
            if (piece_size < 0) {
                std::vector<char> large(static_cast<size_t>(-piece_size));
                piece_size = llama_token_to_piece(vocab, token, large.data(), static_cast<int32_t>(large.size()), 0, true);
                if (piece_size > 0) result.append(large.data(), static_cast<size_t>(piece_size));
            } else if (piece_size > 0) {
                result.append(piece, static_cast<size_t>(piece_size));
            }
            if (llama_decode(context, llama_batch_get_one(const_cast<llama_token *>(&token), 1)) != 0) break;
        }
    }
    llama_sampler_free(sampler);
    llama_free(context);
    if (status != 0) {
        throw_java(env, "Ошибка декодирования Bonsai.");
        return nullptr;
    }
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_shev_agent_model_BonsaiJni_nativeRelease(JNIEnv *, jclass, jlong raw) {
    auto * handle = reinterpret_cast<BonsaiHandle *>(raw);
    if (!handle) return;
    if (handle->model) llama_model_free(handle->model);
    delete handle;
}
