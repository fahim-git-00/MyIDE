#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"

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

static Buf *current_buf = NULL;

static int l_print(lua_State *L) {
    int n = lua_gettop(L);
    for (int i = 1; i <= n; i++) {
        if (i > 1) buf_append(current_buf, "\t", 1);
        size_t len = 0;
        const char *s = luaL_tolstring(L, i, &len);
        if (s) buf_append(current_buf, s, len);
        lua_pop(L, 1);
    }
    buf_append(current_buf, "\n", 1);
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_fahim_myide_LuaRunner_runScript(JNIEnv *env, jclass clazz,
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
    current_buf = &out;

    lua_State *L = luaL_newstate();
    if (!L) {
        buf_append(&out, "ERROR: luaL_newstate failed\n", 27);
        (*env)->ReleaseStringUTFChars(env, jcode, code);
        (*env)->ReleaseStringUTFChars(env, jname, name);
        jstring r = (*env)->NewStringUTF(env, out.buf ? out.buf : "ERROR");
        current_buf = NULL;
        buf_free(&out);
        return r;
    }

    luaL_openlibs(L);

    // Override print
    lua_pushcfunction(L, l_print);
    lua_setglobal(L, "print");

    int rc = luaL_loadbuffer(L, code, strlen(code), name);
    if (rc != LUA_OK) {
        const char *err = lua_tostring(L, -1);
        buf_append(&out, "ERROR: ", 7);
        if (err) buf_append(&out, err, strlen(err));
        buf_append(&out, "\n", 1);
        lua_pop(L, 1);
    } else {
        rc = lua_pcall(L, 0, LUA_MULTRET, 0);
        if (rc != LUA_OK) {
            const char *err = lua_tostring(L, -1);
            buf_append(&out, "ERROR: ", 7);
            if (err) buf_append(&out, err, strlen(err));
            buf_append(&out, "\n", 1);
            lua_pop(L, 1);
        } else {
            int nres = lua_gettop(L);
            for (int i = 1; i <= nres; i++) {
                if (i > 1) buf_append(&out, "\t", 1);
                size_t len = 0;
                const char *s = luaL_tolstring(L, i, &len);
                if (s) buf_append(&out, s, len);
                lua_pop(L, 1);
            }
            if (nres > 0) buf_append(&out, "\n", 1);
        }
    }

    lua_close(L);
    current_buf = NULL;

    (*env)->ReleaseStringUTFChars(env, jcode, code);
    (*env)->ReleaseStringUTFChars(env, jname, name);

    jstring res = (*env)->NewStringUTF(env, out.buf ? out.buf : "");
    buf_free(&out);
    return res;
}
