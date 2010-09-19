#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <inttypes.h>
#include <stdbool.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <jni.h>
#include <pthread.h>
#include <android/log.h>
#include "main.h"
#include "msm_audio.h"
#include "std_audio.h"

#define MSM_DEVICE "/dev/msm_pcm_out"

static int msm_start(msm_ctx *ctx, int channels, int samplerate) {

    struct msm_audio_config config;
    unsigned char *buf;	
    int i;
	
	if(!ctx) return LIBLOSSLESS_ERR_NOCTX;

//  __android_log_print(ANDROID_LOG_INFO,"liblossless","msm_start chans=%d rate=%d afd=%d track=%p", 
//		channels, samplerate,ctx->afd,ctx->track);


        ctx->afd = open(MSM_DEVICE, O_RDWR);
        if(ctx->afd < 0) return LIBLOSSLESS_ERR_INIT; 

	if(ioctl(ctx->afd, AUDIO_GET_CONFIG, &config)) return LIBLOSSLESS_ERR_AU_GETCONF;

	config.channel_count = channels;
	config.sample_rate = samplerate;

	if(ioctl(ctx->afd, AUDIO_SET_CONFIG, &config)) return LIBLOSSLESS_ERR_AU_SETCONF;

	ioctl(ctx->afd, AUDIO_FLUSH, 0);

	buf = (unsigned char *) malloc(config.buffer_size);
	if(!buf) return LIBLOSSLESS_ERR_AU_BUFF;
	memset(buf,0,config.buffer_size);
	for (i = 0; i < config.buffer_count; i++) 
		if(write(ctx->afd,buf,config.buffer_size) != config.buffer_size) {
			free(buf);
			return LIBLOSSLESS_ERR_AU_SETUP;
		}

	free(buf);
	usleep(50);

	if(ioctl(ctx->afd, AUDIO_START, 0)) return LIBLOSSLESS_ERR_AU_START;
	ctx->conf_size = config.buffer_size;

	return 0;	
}

static void msm_stop(msm_ctx *ctx) {
    if(!ctx) return;	
    if(ctx->afd >= 0) {
	ioctl(ctx->afd, AUDIO_STOP, 0);
	close(ctx->afd);
        ctx->afd = -1;
    }	
}

int audio_start(msm_ctx *ctx, int channels, int samplerate) {

    if(!ctx) return LIBLOSSLESS_ERR_NOCTX;
    switch(ctx->mode) {
        case MODE_DIRECT:
           return msm_start(ctx, channels, samplerate);
        case MODE_LIBMEDIA:
           return libmedia_start(ctx, channels, samplerate);
	case MODE_CALLBACK:
           return libmediacb_start(ctx, channels, samplerate);
        default:
           break;
    }
    return 0;
}

void audio_stop(msm_ctx *ctx) {
	
    if(!ctx || ctx->state == MSM_STOPPED) return;
    if(ctx->state != MSM_PAUSED) pthread_mutex_lock(&ctx->mutex);
    if(ctx->fd >= 0) {
	close(ctx->fd); ctx->fd = -1;
    }	
    switch(ctx->mode) {
        case MODE_DIRECT:
           return msm_stop(ctx);
        case MODE_LIBMEDIA:
           return libmedia_stop(ctx);
        case MODE_CALLBACK:
           return libmediacb_stop(ctx);
        default:
           break;
    }
    ctx->state = MSM_STOPPED;	
    pthread_mutex_unlock(&ctx->mutex);
}

ssize_t java_audio_write(JNIEnv *env, jobject obj, msm_ctx *ctx, const void *buf, size_t count);

ssize_t audio_write(JNIEnv *env, jobject obj, msm_ctx *ctx, const void *buf, size_t count) {

    if(!ctx) return LIBLOSSLESS_ERR_NOCTX;
    switch(ctx->mode) {
        case MODE_DIRECT:
           return write(ctx->afd, buf, count);
        case MODE_LIBMEDIA:
           return libmedia_write(ctx, buf, count);
	case MODE_CALLBACK:
           return libmediacb_write(ctx, buf, count);
        case MODE_JAVA:
           return java_audio_write(env,obj,ctx, buf, count);
        default:
           break;
    }
    return -1;
}

JNIEXPORT jboolean JNICALL Java_net_avs234_AndLessSrv_audioStop(JNIEnv *env, jobject obj, msm_ctx *ctx) {
    if(!ctx) return false;	
    audio_stop(ctx);
    ctx->track_time = 0;	
    return true;		
}

JNIEXPORT jboolean JNICALL Java_net_avs234_AndLessSrv_audioPause(JNIEnv *env, jobject obj, msm_ctx *ctx) {
    if(!ctx || ctx->state != MSM_PLAYING) return false;
    pthread_mutex_lock(&ctx->mutex);
    ctx->state = MSM_PAUSED;
    if(ctx->mode == MODE_LIBMEDIA || ctx->mode == MODE_CALLBACK) libmedia_pause(ctx);
    return true;		
}

JNIEXPORT jboolean JNICALL Java_net_avs234_AndLessSrv_audioResume(JNIEnv *env, jobject obj, msm_ctx *ctx) {
    if(!ctx || ctx->state != MSM_PAUSED) return false;
    if(ctx->mode == MODE_LIBMEDIA || ctx->mode == MODE_CALLBACK ) libmedia_resume(ctx);
    ctx->state = MSM_PLAYING;	
    pthread_mutex_unlock(&ctx->mutex);
    return true;	
}

JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_audioGetDuration(JNIEnv *env, jobject obj, msm_ctx *ctx) {
   if(!ctx || (ctx->state != MSM_PLAYING && ctx->state != MSM_PAUSED)) return 0;	
   return ctx->track_time;
}

JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_audioGetCurPosition(JNIEnv *env, jobject obj, msm_ctx *ctx) {
   if(!ctx || (ctx->state != MSM_PLAYING && ctx->state != MSM_PAUSED) 
		|| !ctx->channels || !ctx->samplerate || !ctx->bps) return 0;
   return ctx->written/(ctx->channels * ctx->samplerate * (ctx->bps/8));
}

JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_audioInit(JNIEnv *env, jobject obj, msm_ctx *prev_ctx, jint mode) {

  msm_ctx *ctx;

    if(prev_ctx) {
	audio_stop(prev_ctx);
	ctx = prev_ctx;
    } else {
	ctx = (msm_ctx *) malloc(sizeof(msm_ctx));	 	
	if(!ctx) return 0;
	memset(ctx,0,sizeof(msm_ctx));
	ctx->wavbuf = (unsigned char *)  malloc(DEFAULT_WAV_BUFSZ);
	if(!ctx->wavbuf) {
        	free(ctx); return 0;
    	}
        ctx->afd = -1; ctx->fd = -1;
	pthread_mutex_init(&ctx->mutex,0);
	pthread_mutex_init(&ctx->cbmutex,0);
	pthread_cond_init(&ctx->cbcond,0);
    }	
    ctx->mode = mode;
    ctx->state = MSM_STOPPED;
    ctx->track_time = 0;	
    return (jint) ctx;	
}

JNIEXPORT jboolean JNICALL Java_net_avs234_AndLessSrv_audioExit(JNIEnv *env, jobject obj, msm_ctx *ctx) {
    if(!ctx) return false;
    audio_stop(ctx);
    if(ctx->fd >= 0)  close(ctx->fd);
    pthread_mutex_destroy(&ctx->mutex);
    pthread_mutex_destroy(&ctx->cbmutex);
    pthread_cond_destroy(&ctx->cbcond);
    if(ctx->wavbuf) free(ctx->wavbuf);
    if(ctx->cbbuf) free(ctx->cbbuf);		
    free(ctx);	
    return true;
}


JNIEXPORT jboolean JNICALL Java_net_avs234_AndLessSrv_audioSetVolume(JNIEnv *env, jobject obj, msm_ctx *ctx, jint vol) {
    if(!ctx || ctx->state != MSM_PLAYING || ctx->mode != MODE_DIRECT) return false;
    pthread_mutex_lock(&ctx->mutex);
    ioctl(ctx->afd, AUDIO_SET_VOLUME, vol);
    pthread_mutex_unlock(&ctx->mutex);
    return true;	
}

static JavaVM *gvm;
static jobject giface; 

void update_track_time(JNIEnv *env, jobject obj, int time) {
//     jclass cls = (*env)->GetObjectClass(env, obj);

#ifndef AVSREMOTE
     jclass cls = (*env)->GetObjectClass(env, obj);
     jmethodID mid = (*env)->GetStaticMethodID(env, cls, "updateTrackLen", "(I)V");
     if (mid == NULL) {
	  __android_log_print(ANDROID_LOG_ERROR,"liblossless","Cannot find java callback to update time");
         return; 
     }
    (*env)->CallStaticVoidMethod(env,cls,mid,time);
#else
     jclass cls;
     jmethodID mid;
     bool attached = false;
     JNIEnv *envy;
	if((*gvm)->GetEnv(gvm, (void **)&envy, JNI_VERSION_1_4) != JNI_OK) {
            __android_log_print(ANDROID_LOG_ERROR,"liblossless","update_track_time: GetEnv FAILED");
	     if((*gvm)->AttachCurrentThread(gvm, &envy, NULL) != JNI_OK) {
            	__android_log_print(ANDROID_LOG_ERROR,"liblossless","AttachCurrentThread FAILED");
		     return;
	     }	
	     attached = true;	
	}
	cls = (*envy)->GetObjectClass(envy,giface);
	if(!cls) {
          __android_log_print(ANDROID_LOG_ERROR,"liblossless","failed to get class iface");
	  return;  	
	}
        mid = (*env)->GetStaticMethodID(envy, cls, "updateTrackLen", "(I)V");
        if(mid == NULL) {
	  __android_log_print(ANDROID_LOG_ERROR,"liblossless","Cannot find java callback to update time");
         return; 
        }
	(*envy)->CallStaticVoidMethod(envy,cls,mid,time);
	if(attached) (*gvm)->DetachCurrentThread(gvm);
#endif
}


#ifdef AVSREMOTE
static const char *classPathName = "net/avs234/AndLessSrv";

static JNINativeMethod methods[] = {
 { "audioInit", "(II)I", (void *) Java_net_avs234_AndLessSrv_audioInit },
 { "audioExit", "(I)Z", (void *) Java_net_avs234_AndLessSrv_audioExit },
 { "audioStop", "(I)Z", (void *) Java_net_avs234_AndLessSrv_audioStop },
 { "audioPause", "(I)Z", (void *) Java_net_avs234_AndLessSrv_audioPause },
 { "audioResume", "(I)Z", (void *) Java_net_avs234_AndLessSrv_audioResume },
 { "audioGetDuration", "(I)I", (void *) Java_net_avs234_AndLessSrv_audioGetDuration },
 { "audioGetCurPosition", "(I)I", (void *) Java_net_avs234_AndLessSrv_audioGetCurPosition },
 { "audioSetVolume", "(II)Z", (void *) Java_net_avs234_AndLessSrv_audioSetVolume },
 { "alacPlay", "(ILjava/lang/String;I)I", (void *) Java_net_avs234_AndLessSrv_alacPlay },
 { "flacPlay", "(ILjava/lang/String;I)I", (void *) Java_net_avs234_AndLessSrv_flacPlay },
 { "apePlay", "(ILjava/lang/String;I)I", (void *) Java_net_avs234_AndLessSrv_apePlay },
 { "wavPlay", "(ILjava/lang/String;I)I", (void *) Java_net_avs234_AndLessSrv_wavPlay },
 { "wvPlay", "(ILjava/lang/String;I)I", (void *) Java_net_avs234_AndLessSrv_wvPlay },
 { "mpcPlay", "(ILjava/lang/String;I)I", (void *) Java_net_avs234_AndLessSrv_mpcPlay },
 { "extractFlacCUE", "(Ljava/lang/String;)[I", (void *) extract_flac_cue },
};

jint JNI_OnLoad(JavaVM* vm, void* reserved) {

    jclass clazz = NULL;
    JNIEnv* env = NULL;
    jmethodID constr = NULL;
    jobject obj = NULL;

      __android_log_print(ANDROID_LOG_INFO,"liblossless","JNI_OnLoad");
      gvm = vm;	

      if((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_4) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR,"liblossless","GetEnv FAILED");
        return -1;
      }

      clazz = (*env)->FindClass(env,classPathName);
      if(!clazz) {
        __android_log_print(ANDROID_LOG_ERROR,"liblossless","Registration unable to find class '%s'", classPathName);
        return -1;
      }
      constr = (*env)->GetMethodID(env, clazz, "<init>", "()V");
      if(!constr) {
        __android_log_print(ANDROID_LOG_ERROR,"liblossless","Failed to get constructor");
	return -1;
      }
      obj = (*env)->NewObject(env, clazz, constr);
      if(!obj) {
        __android_log_print(ANDROID_LOG_ERROR,"liblossless","Failed to create an interface object");
	return -1;
      }
      giface = (*env)->NewGlobalRef(env,obj);

      if((*env)->RegisterNatives(env, clazz, methods, sizeof(methods) / sizeof(methods[0])) < 0) {
        __android_log_print(ANDROID_LOG_ERROR,"liblossless","Registration failed for '%s'", classPathName);
        return -1;
      }
    
   return JNI_VERSION_1_4;
}
#endif


//////////////////////////////////
////////// MODE_JAVA //////////////


/*
To call Java function: 
void writePCM(byte pcm_arr[]);
pcm_arr = new byte array of length "count"
*/


ssize_t java_audio_write(JNIEnv *env, jobject obj, msm_ctx *ctx, const void *buf, size_t count) {
  if(!ctx) return -1;

#ifndef AVSREMOTE
     jclass cls = (*env)->GetObjectClass(env, obj);
     jmethodID mid = (*env)->GetStaticMethodID(env, cls, "writePCM", "([B)V");
     if (mid == NULL) {
          __android_log_print(ANDROID_LOG_ERROR,"liblossless","Cannot find java callback to update time");
         return -1;
     }
     jbyteArray array = (*env)->NewByteArray(env, count);
     (*env)->SetByteArrayRegion(env, array, 0, count, (jbyte *)buf);
     (*env)->CallStaticVoidMethod(env,cls,mid,array);
     (*env)->DeleteLocalRef(env, array);
#else
     jclass cls;
     jmethodID mid;
     bool attached = false;
     JNIEnv *envy;
        if((*gvm)->GetEnv(gvm, (void **)&envy, JNI_VERSION_1_4) != JNI_OK) {
            __android_log_print(ANDROID_LOG_ERROR,"liblossless","update_track_time: GetEnv FAILED");
             if((*gvm)->AttachCurrentThread(gvm, &envy, NULL) != JNI_OK) {
                __android_log_print(ANDROID_LOG_ERROR,"liblossless","AttachCurrentThread FAILED");
                     return -1;
             }
             attached = true;
        }
        cls = (*envy)->GetObjectClass(envy,giface);
        if(!cls) {
          __android_log_print(ANDROID_LOG_ERROR,"liblossless","failed to get class iface");
          return -1;
        }
        mid = (*env)->GetStaticMethodID(envy, cls, "writePCM", "([B)V");
        if(mid == NULL) {
          __android_log_print(ANDROID_LOG_ERROR,"liblossless","Cannot find java callback to update time");
         return -1;
        }
     jbyteArray array = (*env)->NewByteArray(env, count);
     (*env)->SetByteArrayRegion(env, array, 0, count, (jbyte *)buf);
     (*env)->CallStaticVoidMethod(env,cls,mid,array);
     (*env)->DeleteLocalRef(env, array);
#endif

  return count;
}
















