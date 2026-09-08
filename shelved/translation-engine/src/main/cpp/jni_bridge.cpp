// JNI bridge to bergamot-translator. Deliberately thin: all policy (which
// language pack to load, whether one is installed, download/progress/
// checksum handling) lives in Kotlin — this file only turns a model config
// file path + input string into a translated string, using the exact same
// AsyncService/TranslationModel/Response API validated against a real
// tiny-model translation this session (see app/bergamot.cpp in the upstream
// bergamot-translator repo, which this mirrors closely).
//
// One native model handle == one loaded (source, target) language pair. The
// Kotlin side owns the handle's lifetime: create once per pair (loading a
// model is the expensive part — tens of milliseconds to low hundreds), reuse
// it across calls, and explicitly destroy it when the pair falls out of the
// small LRU cache the plan calls for, so we're not holding every installed
// pack's weights in memory at once.

#include <jni.h>

#include <future>
#include <memory>
#include <string>

#include <android/log.h>

#include "translator/parser.h"
#include "translator/response.h"
#include "translator/response_options.h"
#include "translator/service.h"

#define LOG_TAG "TranslationEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using marian::bergamot::AsyncService;
using marian::bergamot::Response;
using marian::bergamot::ResponseOptions;
using marian::bergamot::TranslationModel;

namespace {

// Bundles the service and the one model it was constructed for. A JNI "long"
// handle is really just a pointer to one of these, cast back and forth —
// the standard pattern for exposing a C++ object's lifetime to Kotlin/Java
// across the JNI boundary without a full object-registry layer, appropriate
// here since Kotlin already tracks handle lifetime via its own pack cache.
struct ModelHandle {
    AsyncService service;
    std::shared_ptr<TranslationModel> model;

    explicit ModelHandle(const AsyncService::Config &serviceConfig) : service(serviceConfig) {}
};

std::string jstringToUtf8(JNIEnv *env, jstring value) {
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_net_primal_core_translation_BergamotTranslationEngine_nativeCreateModel(
    JNIEnv *env, jclass /*clazz*/, jstring configPath) {
    const std::string path = jstringToUtf8(env, configPath);

    try {
        // Single-threaded: notes are short, and running one CPU thread per
        // translation keeps memory/battery cost predictable on a phone
        // rather than marian claiming every core the device has.
        AsyncService::Config serviceConfig;
        serviceConfig.numWorkers = 1;

        auto *handle = new ModelHandle(serviceConfig);
        auto options = marian::bergamot::parseOptionsFromFilePath(path);
        handle->model = handle->service.createCompatibleModel(options);
        return reinterpret_cast<jlong>(handle);
    } catch (const std::exception &error) {
        LOGE("Failed to load translation model from %s: %s", path.c_str(), error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_net_primal_core_translation_BergamotTranslationEngine_nativeTranslate(
    JNIEnv *env, jclass /*clazz*/, jlong handlePtr, jstring text) {
    if (handlePtr == 0) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Model handle is null");
        return nullptr;
    }
    auto *handle = reinterpret_cast<ModelHandle *>(handlePtr);
    std::string input = jstringToUtf8(env, text);

    try {
        ResponseOptions responseOptions;
        std::promise<Response> promise;
        std::future<Response> future = promise.get_future();

        handle->service.translate(
            handle->model, std::move(input),
            [&promise](Response &&response) { promise.set_value(std::move(response)); }, responseOptions);

        Response response = future.get();
        return env->NewStringUTF(response.target.text.c_str());
    } catch (const std::exception &error) {
        LOGE("Translation failed: %s", error.what());
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_net_primal_core_translation_BergamotTranslationEngine_nativeDestroyModel(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handlePtr) {
    delete reinterpret_cast<ModelHandle *>(handlePtr);
}
