LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := ape

LOCAL_SRC_FILES +=  crc.c predictor.c predictor-arm.S entropy.c ape_decoder.c parser.c \
	filter_1280_15.c filter_16_11.c filter_256_13.c filter_32_10.c filter_64_11.c main.c
LOCAL_CFLAGS += -O3 -Wall -DBUILD_STANDALONE -DCPU_ARM -fPIC  -DARM_ARCH=5 \
-UDEBUG -DNDEBUG -fomit-frame-pointer -ffreestanding  
# -DDBG_TIME

# won't build with:
# -finline-functions

LOCAL_ARM_MODE := arm

include $(BUILD_STATIC_LIBRARY)

