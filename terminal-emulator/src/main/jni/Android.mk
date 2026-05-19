LOCAL_PATH:= $(call my-dir)

# Pre-built libvproc.so (coroutine-based virtual process library)
include $(CLEAR_VARS)
LOCAL_MODULE := libvproc
LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libvproc.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c
LOCAL_LDLIBS := -ldl
LOCAL_SHARED_LIBRARIES := libvproc
include $(BUILD_SHARED_LIBRARY)
