#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include "py/compile.h"
#include "py/runtime.h"
#include "py/gc.h"
#include "py/stackctrl.h"
#include "py/mphal.h"
#include "py/builtin.h"
#include "genhdr/mpversion.h"

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

static Buf *g_buf = NULL;

// Override mp_hal_stdout_tx_strn to capture output
void mp_hal_stdout_tx_strn(const char *str, size_t len) {
    if (g_buf) buf_append(g_buf, str, len);
}

void mp_hal_stdout_tx_strn_cooked(const char *str, size_t len) {
    mp_hal_stdout_tx_strn(str, len);
}

JNIEXPORT jstring JNICALL
Java_com_fahim_myide_PythonRunner_runScript(JNIEnv *env, jclass clazz,
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
    g_buf = &out;

    // 32KB heap for the GC — tune as needed
    static char heap[512 * 1024];
    gc_init(heap, heap + sizeof(heap));

    mp_init();
    mp_obj_t ret = mp_const_none;

    nlr_buf_t nlr;
    if (nlr_push(&nlr) == 0) {
        mp_lexer_t *lex = mp_lexer_new_from_str_len(
            mp_qstr__lt_stdin_gt_, code, strlen(code), 0);
        mp_parse_tree_t parse_tree = mp_parse(lex, MP_PARSE_FILE_INPUT);
        mp_obj_t code_obj = mp_compile(&parse_tree, lex->source_name, false);
        ret = mp_call_function_0(code_obj);
        mp_lexer_free(lex);
        nlr_pop();
    } else {
        // Exception
        mp_obj_t exc = (mp_obj_t)nlr.ret_val;
        mp_obj_print_exception(&mp_plat_print, exc);
        if (g_buf) {
            buf_append(g_buf, "ERROR: exception\n", 17);
        }
    }

    mp_deinit();
    g_buf = NULL;

    (*env)->ReleaseStringUTFChars(env, jcode, code);
    (*env)->ReleaseStringUTFChars(env, jname, name);

    jstring res = (*env)->NewStringUTF(env, out.buf ? out.buf : "");
    buf_free(&out);
    return res;
}
