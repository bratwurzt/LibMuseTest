LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := liblslAndroid
LOCAL_SRC_FILES := liblslAndroid.so
include $(PREBUILT_SHARED_LIBRARY)