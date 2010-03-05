LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := wav

LOCAL_SRC_FILES += main.c
LOCAL_CFLAGS += -O2 -Wall -DBUILD_STANDALONE -DCPU_ARM -finline-functions -fPIC 
#-DDBG_TIME
LOCAL_ARM_MODE := arm

include $(BUILD_STATIC_LIBRARY)

