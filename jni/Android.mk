include $(call all-subdir-makefiles)

# Why this doesn't work here?
#LOCAL_PATH:= $(call my-dir)
LOCAL_PATH:=apps/lossless/project/jni

include $(CLEAR_VARS)

LOCAL_MODULE := lossless
LOCAL_STATIC_LIBRARIES := alac ape flac wav wv mpc
LOCAL_CFLAGS += -O2 -Wall -DBUILD_STANDALONE -DCPU_ARM -DAVSREMOTE -finline-functions -fPIC -D__ARM_EABI__=1 -DOLD_LOGDH 
# -I $(LOCAL_PATH)/Android/include

LOCAL_C_INCLUDES += $(LOCAL_PATH)/Android/include

LOCAL_SRC_FILES :=  main.c std_audio.cpp
LOCAL_ARM_MODE := arm
LOCAL_LDLIBS := -llog \
 $(LOCAL_PATH)/Android/lib/libutils.so $(LOCAL_PATH)/Android/lib/libmedia.so
# $(call host-path, $(LOCAL_PATH)/Android/lib/libutils.so) $(call host-path, $(LOCAL_PATH)/Android/lib/libmedia.so)

include $(BUILD_SHARED_LIBRARY)
