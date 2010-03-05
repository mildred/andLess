LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := flac

LOCAL_SRC_FILES += arm.S main.c flac_decoder.c bitstream.c tables.c 
LOCAL_CFLAGS += -O2 -Wall -DBUILD_STANDALONE -DCPU_ARM -finline-functions -fPIC
#-DDBG_TIME
LOCAL_ARM_MODE := arm

include $(BUILD_STATIC_LIBRARY)
# include $(BUILD_SHARED_LIBRARY)
