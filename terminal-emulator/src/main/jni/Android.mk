LOCAL_PATH:= $(call my-dir)

# Pre-built libvproc.so for arm64-v8a only.
# Loaded at runtime via dlopen — no compile-time dependency.
ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
include $(CLEAR_VARS)
LOCAL_MODULE := libvproc
LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libvproc.so
include $(PREBUILT_SHARED_LIBRARY)
endif

include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c
LOCAL_LDLIBS := -ldl
include $(BUILD_SHARED_LIBRARY)
