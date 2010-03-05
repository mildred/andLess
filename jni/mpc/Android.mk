LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := mpc 

#LOCAL_SRC_FILES += synth_filter_arm.S huffsv46.c huffsv7.c idtag.c main.c mpc_decoder.c requant.c streaminfo.c synth_filter.c 
LOCAL_SRC_FILES += huffsv46.c  huffsv7.c  idtag.c  main.c  mpc_decoder.c requant.c  streaminfo.c  synth_filter.c
LOCAL_CFLAGS += -O2 -Wall -DBUILD_STANDALONE -DCPU_ARM -finline-functions -fPIC -I. -DMPC_LITTLE_ENDIAN -DMPC_FIXED_POINT 
#-DDBG_TIME 
#-DMPC_FIXED_POINT

LOCAL_ARM_MODE := arm


include $(BUILD_STATIC_LIBRARY)
# include $(BUILD_SHARED_LIBRARY)
