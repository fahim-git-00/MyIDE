#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include "quickjs.h"

typedef struct {
    char *buf;
    size_t len;
    size_t cap;
} Buf;

static void buf_init(Buf *b) {
    b->cap = 4096;
    b->len = 0;
    b->buf = (char*)malloc(b->cap);
    if (b->buf) b->buf[0] = 0;
}

static void buf_append(Buf *b, const char *s, size_t n) {
    if (!b->buf) return;
    if (b->len + n + 1 > b->cap) {
        size_t nc = b->cap;
        while (nc < b->len + n + 1) nc *= 2;
        char *nb = (char*)realloc(b->buf, nc);
        if (!nb) return;
        b->buf = nb;
        b->cap = nc;
    }
    memcpy(b->buf + b->len, s, n);
    b->len += n;
    b->buf[b->len] = 0;
}

static void buf_free(Buf *b) {
    if (b->buf) free(b->buf);
    b->buf = NULL;
    b->len = 0;
    b->cap = 0;
}

static JSValue js_print(JSContext *ctx, JSValueConst this_val,
                        int argc, JSValueConst *argv) {
    Buf *b = (Buf*)JS_GetContextOpaque(ctx);
    if (!b) return JS_UNDEFINED;
    for (int i = 0; i < argc; i++) {
        if (i > 0) buf_append(b, " ", 1);
        const char *s = JS_ToCString(ctx, argv[i]);
        if (s) {
            buf_append(b, s, strlen(s));
            JS_FreeCString(ctx, s);
        }
    }
    buf_append(b, "\n", 1);
    return JS_UNDEFINED;
}

JNIEXPORT jstring JNICALL
Java_com_fahim_myide_JsRunner_runScript(JNIEnv *env, jclass clazz,
                                        jstring jcode, jstring jname) {
    const char *code = (*env)->GetStringUTFChars(env, jcode, NULL);
    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    if (!code || !name) {
        if (code) (*env)->ReleaseStringUTFChars(env, jcode, code);
        if (name) (*env)->ReleaseStringUTFChars(env, jname, name);
        return (*env)->NewStringUTF(env, "ERROR: null input");
    }

    Buf out;
    buf_init(&out);

    JSRuntime *rt = JS_NewRuntime();
    if (!rt) {
        buf_append(&out, "ERROR: JS_NewRuntime failed\n", 28);
        (*env)->ReleaseStringUTFChars(env, jcode, code);
        (*env)->ReleaseStringUTFChars(env, jname, name);
        jstring r = (*env)->NewStringUTF(env, out.buf ? out.buf : "ERROR");
        buf_free(&out);
        return r;
    }
    JS_SetMemoryLimit(rt, 64 * 1024 * 1024);
    JS_SetMaxStackSize(rt, 1024 * 1024);

    JSContext *ctx = JS_NewContext(rt);
    if (!ctx) {
        buf_append(&out, "ERROR: JS_NewContext failed\n", 28);
        JS_FreeRuntime(rt);
        (*env)->ReleaseStringUTFChars(env, jcode, code);
        (*env)->ReleaseStringUTFChars(env, jname, name);
        jstring r = (*env)->NewStringUTF(env, out.buf ? out.buf : "ERROR");
        buf_free(&out);
        return r;
    }

    JS_SetContextOpaque(ctx, &out);

    JSValue global = JS_GetGlobalObject(ctx);
    JSValue console = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, console, "log",
        JS_NewCFunction(ctx, js_print, "log", 1));
    JS_SetPropertyStr(ctx, global, "console", console);
    JS_SetPropertyStr(ctx, global, "print",
        JS_NewCFunction(ctx, js_print, "print", 1));
    JS_FreeValue(ctx, global);

    JSValue result = JS_Eval(ctx, code, strlen(code), name,
                             JS_EVAL_TYPE_GLOBAL);

    if (JS_IsException(result)) {
        JSValue exc = JS_GetException(ctx);
        const char *e = JS_ToCString(ctx, exc);
        buf_append(&out, "ERROR: ", 7);
        if (e) { buf_append(&out, e, strlen(e)); JS_FreeCString(ctx, e); }
        buf_append(&out, "\n", 1);
        JS_FreeValue(ctx, exc);
    } else if (!JS_IsUndefined(result)) {
        const char *r = JS_ToCString(ctx, result);
        if (r) {
            buf_append(&out, r, strlen(r));
            buf_append(&out, "\n", 1);
            JS_FreeCString(ctx, r);
        }
    }

    JS_FreeValue(ctx, result);
    JS_FreeContext(ctx);
    JS_FreeRuntime(rt);

    (*env)->ReleaseStringUTFChars(env, jcode, code);
    (*env)->ReleaseStringUTFChars(env, jname, name);

    jstring res = (*env)->NewStringUTF(env, out.buf ? out.buf : "");
    buf_free(&out);
    return res;
}
