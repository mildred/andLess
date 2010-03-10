LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := alac

LOCAL_SRC_FILES += alac_decoder.c demux.c m4a.c main.c
LOCAL_CFLAGS += -O2 -Wall -DBUILD_STANDALONE -DCPU_ARM -finline-functions -fPIC
#-DDBG_TIME
LOCAL_ARM_MODE := arm

include $(BUILD_STATIC_LIBRARY)
# include $(BUILD_SHARED_LIBRARY)
