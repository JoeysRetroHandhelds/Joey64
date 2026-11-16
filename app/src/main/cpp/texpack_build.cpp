// Texture packs: a PNG pack made into a .hts outside play (approved mockup texpacks4, 2026-11-15). The core's
// joey64_texpack_build runs GLideN64's own loader on it; this opens the core for just that, in the conversion process
// (TexConvertService runs in :texconv, so no game shares it), and passes the per-texture count to the Kotlin side,
// which may hold the call there while a game is played.
#include "joeylog.h"
#include <jni.h>
#include <dlfcn.h>

#define LOGE(...) joeyLog(ANDROID_LOG_ERROR, "joey64host", __VA_ARGS__)

namespace {
JNIEnv* g_env;
jobject g_listener;
jmethodID g_onTexture;

int on_texture(int n) {
    if (g_env && g_listener) g_env->CallVoidMethod(g_listener, g_onTexture, n);
    if (g_env && g_env->ExceptionCheck()) g_env->ExceptionClear();
    return 0;
}
}

// -1: the core has no joey64_texpack_build (an older core: the game makes the .hts at its start instead), -2: the core
// can't be opened; else the textures in the pack made (0: nothing made).
extern "C" JNIEXPORT jint JNICALL Java_com_joeysretro_joey64_video_TexBuild_build(JNIEnv* env, jclass, jstring corePath,
                                                                                    jstring dir, jstring rom, jobject listener) {
    const char* p = env->GetStringUTFChars(corePath, nullptr);
    void* h = dlopen(p, RTLD_NOW | RTLD_LOCAL);
    if (!h) LOGE("texture packs: dlopen %s: %s", p, dlerror());
    env->ReleaseStringUTFChars(corePath, p);
    if (!h) return -2;
    auto build = reinterpret_cast<int (*)(const char*, const char*, int (*)(int))>(dlsym(h, "joey64_texpack_build"));
    if (!build) return -1;
    g_env = env;
    g_listener = listener;
    g_onTexture = env->GetMethodID(env->GetObjectClass(listener), "onTexture", "(I)V");
    const char* d = env->GetStringUTFChars(dir, nullptr);
    const char* r = env->GetStringUTFChars(rom, nullptr);
    const int n = build(d, r, on_texture);
    env->ReleaseStringUTFChars(dir, d);
    env->ReleaseStringUTFChars(rom, r);
    g_env = nullptr;
    g_listener = nullptr;
    return n;
}

// Whether the core at corePath has the build call (without running anything): the app falls back to the game making
// the .hts at its start with an older core.
extern "C" JNIEXPORT jboolean JNICALL Java_com_joeysretro_joey64_video_TexBuild_supported(JNIEnv* env, jclass, jstring corePath) {
    const char* p = env->GetStringUTFChars(corePath, nullptr);
    void* h = dlopen(p, RTLD_NOW | RTLD_LOCAL);
    env->ReleaseStringUTFChars(corePath, p);
    return h && dlsym(h, "joey64_texpack_build") ? JNI_TRUE : JNI_FALSE;
}
