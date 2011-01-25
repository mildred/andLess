include $(call all-subdir-makefiles)


#CODECS := alac ape flac wav wv mpc
#codec-makefiles =  $(patsubst %,$(call my-dir)/%/Android.mk,$(CODECS)) 
#include $(call codec-makefiles)

ifneq ($(NDK_ROOT),)
LOCAL_PATH:=$(NDK_ROOT)/apps/lossless/project/jni
else
LOCAL_PATH:=apps/lossless/project/jni
endif

# library for Android < 9
include $(CLEAR_VARS)
LOCAL_MODULE := atrack8 
LOCAL_CFLAGS += -O2 -Wall -DBUILD_STANDALONE -DCPU_ARM -DAVSREMOTE -finline-functions -fPIC -D__ARM_EABI__=1 -DOLD_LOGDH 
LOCAL_C_INCLUDES += $(LOCAL_PATH)/Android/include
LOCAL_SRC_FILES := std_audio.cpp
LOCAL_ARM_MODE := arm
LOCAL_LDLIBS := -llog \
 $(LOCAL_PATH)/Android/lib/libutils.so $(LOCAL_PATH)/Android/lib/libmedia.so
include $(BUILD_SHARED_LIBRARY)

# library for Android api >= 9
include $(CLEAR_VARS)
LOCAL_MODULE := atrack9
LOCAL_CFLAGS += -O2 -Wall -DBUILD_STANDALONE -DCPU_ARM -DAVSREMOTE -finline-functions -fPIC -D__ARM_EABI__=1 -DOLD_LOGDH
LOCAL_CFLAGS += -DBUILD_GINGER
LOCAL_C_INCLUDES += $(LOCAL_PATH)/Android/include
LOCAL_SRC_FILES := std_audio.cpp
LOCAL_ARM_MODE := arm
LOCAL_LDLIBS := -llog \
 $(LOCAL_PATH)/Android/lib/gingerbread/libutils.so $(LOCAL_PATH)/Android/lib/gingerbread/libmedia.so
include $(BUILD_SHARED_LIBRARY)

# common codecs & startup library
include $(CLEAR_VARS)
LOCAL_MODULE := lossless
LOCAL_STATIC_LIBRARIES := alac ape flac wav wv mpc
LOCAL_CFLAGS += -O2 -Wall -DBUILD_STANDALONE -DCPU_ARM -DAVSREMOTE -finline-functions -fPIC -D__ARM_EABI__=1 -DOLD_LOGDH
LOCAL_SRC_FILES := main.c
LOCAL_ARM_MODE := arm
LOCAL_LDLIBS := -llog -ldl
include $(BUILD_SHARED_LIBRARY)


