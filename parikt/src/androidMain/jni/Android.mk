LOCAL_PATH := $(call my-dir)

ifndef NATIVE_DEPS_DIR
$(error NATIVE_DEPS_DIR is not set - build through Gradle)
endif

include $(CLEAR_VARS)
LOCAL_MODULE := gmp
LOCAL_SRC_FILES := $(NATIVE_DEPS_DIR)/$(TARGET_ARCH_ABI)/gmp/lib/libgmp.so
LOCAL_EXPORT_C_INCLUDES := $(NATIVE_DEPS_DIR)/$(TARGET_ARCH_ABI)/gmp/include
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := pari
LOCAL_SRC_FILES := $(NATIVE_DEPS_DIR)/$(TARGET_ARCH_ABI)/pari/lib/libpari.so
LOCAL_EXPORT_C_INCLUDES := $(NATIVE_DEPS_DIR)/$(TARGET_ARCH_ABI)/pari/include
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := paridroid
LOCAL_LDLIBS := -llog
LOCAL_CFLAGS := -g -std=gnu99 -Wno-undefined-internal
# GNU make's file reader works on Windows too, without requiring host grep/sh.
ifneq ($(findstring functions_hash_MASK,$(file <$(NATIVE_DEPS_DIR)/$(TARGET_ARCH_ABI)/pari/include/pari/paripriv.h)),)
LOCAL_CFLAGS += -DPARIDROID_USE_HASH_MASK=1
endif
LOCAL_LDFLAGS := -Wl,--no-warn-mismatch -Wl,--allow-shlib-undefined
LOCAL_SRC_FILES := \
    ../../../native/paridroid/paridroid.c \
    ../../../native/paridroid/jni_bridge.c
LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/../../../native/paridroid \
    $(NATIVE_DEPS_DIR)/$(TARGET_ARCH_ABI)/pari/include
LOCAL_SHARED_LIBRARIES := pari gmp
include $(BUILD_SHARED_LIBRARY)
