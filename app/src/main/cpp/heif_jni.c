// JNI-мост: декодирование HEIC/HEIF через libheif в Bitmap (ARGB_8888).
#include <jni.h>
#include <android/bitmap.h>
#include <stdlib.h>
#include <string.h>

#include <libheif/heif.h>

static jbyte *read_bytes(JNIEnv *env, jbyteArray array, jsize *out_len) {
    jsize len = (*env)->GetArrayLength(env, array);
    jbyte *buf = (jbyte *) malloc(len > 0 ? len : 1);
    if (buf == NULL) return NULL;
    (*env)->GetByteArrayRegion(env, array, 0, len, buf);
    *out_len = len;
    return buf;
}

// Возвращает int[]{width, height} или NULL при ошибке.
JNIEXPORT jintArray JNICALL
Java_com_pzarubin_tvslideshow_playback_HeifDecoderNative_getSize(
        JNIEnv *env, jclass clazz, jbyteArray data) {
    jsize len = 0;
    jbyte *buf = read_bytes(env, data, &len);
    if (buf == NULL) return NULL;

    struct heif_context *ctx = heif_context_alloc();
    jintArray result = NULL;
    struct heif_image_handle *handle = NULL;

    struct heif_error err = heif_context_read_from_memory(ctx, buf, len, NULL);
    if (err.code != heif_error_Ok) goto done;

    err = heif_context_get_primary_image_handle(ctx, &handle);
    if (err.code != heif_error_Ok) goto done;

    {
        int w = heif_image_handle_get_width(handle);
        int h = heif_image_handle_get_height(handle);
        if (w > 0 && h > 0) {
            jint dims[2] = {w, h};
            result = (*env)->NewIntArray(env, 2);
            if (result != NULL) (*env)->SetIntArrayRegion(env, result, 0, 2, dims);
        }
    }

done:
    if (handle) heif_image_handle_release(handle);
    heif_context_free(ctx);
    free(buf);
    return result;
}

// Декодирует в переданный Bitmap (ARGB_8888, размер должен совпадать с изображением).
JNIEXPORT jboolean JNICALL
Java_com_pzarubin_tvslideshow_playback_HeifDecoderNative_decodeInto(
        JNIEnv *env, jclass clazz, jbyteArray data, jobject bitmap) {
    jsize len = 0;
    jbyte *buf = read_bytes(env, data, &len);
    if (buf == NULL) return JNI_FALSE;

    struct heif_context *ctx = heif_context_alloc();
    jboolean ok = JNI_FALSE;
    struct heif_image_handle *handle = NULL;
    struct heif_image *img = NULL;
    void *pixels = NULL;

    struct heif_error err = heif_context_read_from_memory(ctx, buf, len, NULL);
    if (err.code != heif_error_Ok) goto done;

    err = heif_context_get_primary_image_handle(ctx, &handle);
    if (err.code != heif_error_Ok) goto done;

    // RGBA — прямой маппинг на ARGB_8888. irot/imir применяются автоматически.
    err = heif_decode_image(handle, &img, heif_colorspace_RGB,
                            heif_chroma_interleaved_RGBA, NULL);
    if (err.code != heif_error_Ok) goto done;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS)
        goto done;

    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
        goto done;

    {
        int out_w = heif_image_get_primary_width(img);
        int out_h = heif_image_get_primary_height(img);
        if (out_w != (int) info.width || out_h != (int) info.height) goto unlock;

        int src_stride = 0;
        uint8_t *src = heif_image_get_plane_readonly(
                img, heif_channel_interleaved, &src_stride);
        if (src == NULL) goto unlock;

        size_t row_bytes = (size_t) out_w * 4;
        uint8_t *dst = (uint8_t *) pixels;
        for (int y = 0; y < out_h; y++) {
            memcpy(dst + (size_t) y * info.stride,
                   src + (size_t) y * src_stride, row_bytes);
        }
        ok = JNI_TRUE;
    }

unlock:
    if (pixels) AndroidBitmap_unlockPixels(env, bitmap);
done:
    if (img) heif_image_release(img);
    if (handle) heif_image_handle_release(handle);
    heif_context_free(ctx);
    free(buf);
    return ok;
}
