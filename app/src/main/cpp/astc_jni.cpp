#include <jni.h>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <new>
#include <thread>
#include <vector>

#include "astcenc.h"

namespace {
const char* g_msg = "unknown error";

struct Ctx {
    astcenc_config cfg;
    astcenc_context* ctx = nullptr;
    int threads = 1;
};
}

extern "C" {

JNIEXPORT jlong JNICALL Java_com_apenv_app_Astc_create(
    JNIEnv*, jclass, jint bx, jint by, jint threads, jfloat quality) {
    Ctx* c = new (std::nothrow) Ctx();
    if (!c) return 0;
    std::memset(&c->cfg, 0, sizeof(c->cfg));
    c->threads = threads < 1 ? 1 : threads;
    astcenc_error err = astcenc_config_init(ASTCENC_PRF_LDR, (unsigned)bx, (unsigned)by, 1, quality, 0, &c->cfg);
    if (err != ASTCENC_SUCCESS) {
        g_msg = astcenc_get_error_string(err);
        delete c;
        return 0;
    }
    err = astcenc_context_alloc(&c->cfg, (size_t)c->threads, &c->ctx, nullptr);
    if (err != ASTCENC_SUCCESS) {
        g_msg = astcenc_get_error_string(err);
        delete c;
        return 0;
    }
    return (jlong)(intptr_t)c;
}

JNIEXPORT jstring JNICALL Java_com_apenv_app_Astc_lastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(g_msg);
}

JNIEXPORT jbyteArray JNICALL Java_com_apenv_app_Astc_encode(
    JNIEnv* env, jclass, jlong handle, jbyteArray rgba, jint w, jint h) {
    Ctx* c = (Ctx*)(intptr_t)handle;
    if (!c || w < 1 || h < 1) return nullptr;
    size_t need = (size_t)w * (size_t)h * 4;
    if ((size_t)env->GetArrayLength(rgba) < need) return nullptr;
    try {

    std::vector<uint8_t> in(need);
    env->GetByteArrayRegion(rgba, 0, (jsize)need, (jbyte*)in.data());

    astcenc_image img;
    std::memset(&img, 0, sizeof(img));
    img.dim_x = (size_t)w;
    img.dim_y = (size_t)h;
    img.dim_z = 1;
    img.data_type = ASTCENC_TYPE_U8;
    void* slice = in.data();
    img.data = &slice;

    astcenc_swizzle swz;
    swz.r = ASTCENC_SWZ_R;
    swz.g = ASTCENC_SWZ_G;
    swz.b = ASTCENC_SWZ_B;
    swz.a = ASTCENC_SWZ_A;

    size_t bw = c->cfg.block_x, bh = c->cfg.block_y;
    size_t len = (((size_t)w + bw - 1) / bw) * (((size_t)h + bh - 1) / bh) * 16;
    std::vector<uint8_t> out(len);

    std::atomic<int> failed(0);
    auto work = [&](int t) {
        try {
            if (astcenc_compress_image(c->ctx, &img, &swz, out.data(), len, (size_t)t) != ASTCENC_SUCCESS) failed = 1;
        } catch (...) {
            failed = 1;
        }
    };
    std::vector<std::thread> pool;
    for (int t = 1; t < c->threads; t++) {
        try {
            pool.emplace_back(work, t);
        } catch (...) {
            break;
        }
    }
    work(0);
    for (auto& th : pool) th.join();
    astcenc_compress_reset(c->ctx);
    if (failed) return nullptr;

    jbyteArray res = env->NewByteArray((jsize)len);
    if (!res) return nullptr;
    env->SetByteArrayRegion(res, 0, (jsize)len, (const jbyte*)out.data());
    return res;
    } catch (...) {
        return nullptr;
    }
}

JNIEXPORT jbyteArray JNICALL Java_com_apenv_app_Astc_decode(
    JNIEnv* env, jclass, jint bx, jint by, jbyteArray data, jint w, jint h) {
    if (w < 1 || h < 1 || bx < 4 || by < 4) return nullptr;
    astcenc_context* ctx = nullptr;
    try {
        astcenc_config cfg;
        std::memset(&cfg, 0, sizeof(cfg));
        if (astcenc_config_init(ASTCENC_PRF_LDR, (unsigned)bx, (unsigned)by, 1, 0.0f,
                                ASTCENC_FLG_DECOMPRESS_ONLY, &cfg) != ASTCENC_SUCCESS) return nullptr;
        if (astcenc_context_alloc(&cfg, 1, &ctx, nullptr) != ASTCENC_SUCCESS) return nullptr;

        size_t bw = (size_t)bx, bh = (size_t)by;
        size_t len = (((size_t)w + bw - 1) / bw) * (((size_t)h + bh - 1) / bh) * 16;
        if ((size_t)env->GetArrayLength(data) < len) {
            astcenc_context_free(ctx);
            return nullptr;
        }
        std::vector<uint8_t> in(len);
        env->GetByteArrayRegion(data, 0, (jsize)len, (jbyte*)in.data());
        std::vector<uint8_t> out((size_t)w * (size_t)h * 4);

        astcenc_image img;
        std::memset(&img, 0, sizeof(img));
        img.dim_x = (size_t)w;
        img.dim_y = (size_t)h;
        img.dim_z = 1;
        img.data_type = ASTCENC_TYPE_U8;
        void* slice = out.data();
        img.data = &slice;

        astcenc_swizzle swz;
        swz.r = ASTCENC_SWZ_R;
        swz.g = ASTCENC_SWZ_G;
        swz.b = ASTCENC_SWZ_B;
        swz.a = ASTCENC_SWZ_A;

        astcenc_error err = astcenc_decompress_image(ctx, in.data(), len, &img, &swz, 0);
        astcenc_context_free(ctx);
        ctx = nullptr;
        if (err != ASTCENC_SUCCESS) return nullptr;

        jbyteArray res = env->NewByteArray((jsize)out.size());
        if (!res) return nullptr;
        env->SetByteArrayRegion(res, 0, (jsize)out.size(), (const jbyte*)out.data());
        return res;
    } catch (...) {
        if (ctx) astcenc_context_free(ctx);
        return nullptr;
    }
}

JNIEXPORT void JNICALL Java_com_apenv_app_Astc_cancel(JNIEnv*, jclass, jlong handle) {
    Ctx* c = (Ctx*)(intptr_t)handle;
    if (c && c->ctx) astcenc_compress_cancel(c->ctx);
}

JNIEXPORT void JNICALL Java_com_apenv_app_Astc_free(JNIEnv*, jclass, jlong handle) {
    Ctx* c = (Ctx*)(intptr_t)handle;
    if (!c) return;
    if (c->ctx) astcenc_context_free(c->ctx);
    delete c;
}

}
