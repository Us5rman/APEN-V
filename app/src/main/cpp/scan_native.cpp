#include <jni.h>
#include <cstdint>
#include <cstring>

// Counts how often every byte value appears. Eight small tables are used in turn so repeated bytes
// (long runs of zeros are common in game data) do not wait on each other.
static void count_bytes(const uint8_t* p, size_t n, uint64_t* out) {
    uint32_t t[8][256];
    std::memset(t, 0, sizeof(t));
    size_t i = 0;
    for (; i + 8 <= n; i += 8) {
        uint64_t v;
        std::memcpy(&v, p + i, 8);
        t[0][v & 255]++;
        t[1][(v >> 8) & 255]++;
        t[2][(v >> 16) & 255]++;
        t[3][(v >> 24) & 255]++;
        t[4][(v >> 32) & 255]++;
        t[5][(v >> 40) & 255]++;
        t[6][(v >> 48) & 255]++;
        t[7][v >> 56]++;
    }
    for (; i < n; i++) t[0][p[i]]++;
    for (int k = 0; k < 256; k++) {
        uint64_t s = 0;
        for (int j = 0; j < 8; j++) s += t[j][k];
        out[k] += s;
    }
}

extern "C" {

JNIEXPORT void JNICALL Java_com_apenv_app_FastHist_bytes(
    JNIEnv* env, jclass, jbyteArray a, jint off, jint len, jlongArray h) {
    if (len <= 0) return;
    jbyte* p = (jbyte*)env->GetPrimitiveArrayCritical(a, nullptr);
    if (!p) return;
    jlong* hh = (jlong*)env->GetPrimitiveArrayCritical(h, nullptr);
    if (hh) {
        count_bytes((const uint8_t*)p + off, (size_t)len, (uint64_t*)hh);
        env->ReleasePrimitiveArrayCritical(h, hh, 0);
    }
    env->ReleasePrimitiveArrayCritical(a, p, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_com_apenv_app_FastHist_direct(
    JNIEnv* env, jclass, jobject buf, jint len, jlongArray h) {
    if (len <= 0) return;
    const uint8_t* p = (const uint8_t*)env->GetDirectBufferAddress(buf);
    if (!p) return;
    jlong* hh = (jlong*)env->GetPrimitiveArrayCritical(h, nullptr);
    if (hh) {
        count_bytes(p, (size_t)len, (uint64_t*)hh);
        env->ReleasePrimitiveArrayCritical(h, hh, 0);
    }
}

}
