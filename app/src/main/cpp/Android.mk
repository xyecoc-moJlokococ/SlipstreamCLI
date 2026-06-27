LOCAL_PATH := $(call my-dir)
MY_LOCAL_PATH := $(LOCAL_PATH)

include $(MY_LOCAL_PATH)/hev-socks5-tunnel-src/Android.mk

include $(CLEAR_VARS)
LOCAL_PATH := $(MY_LOCAL_PATH)
LOCAL_MODULE := hev-tunnel-jni
LOCAL_SRC_FILES := hev-socks5-tunnel/hev_jni.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/hev-socks5-tunnel-src/include
LOCAL_SHARED_LIBRARIES := hev-socks5-tunnel
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
