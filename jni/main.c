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
        default:
           break;
    }
    return 0;
}

void audio_stop(msm_ctx *ctx) {
	
    if(!ctx) return;
    if(ctx->state != MSM_PAUSED) pthread_mutex_lock(&ctx->mutex);
    if(ctx->fd >= 0) {
	close(ctx->fd); ctx->fd = -1;
    }	
    if(ctx->mode == MODE_DIRECT) msm_stop(ctx);
    else if(ctx->mode == MODE_LIBMEDIA)	libmedia_stop(ctx);

    ctx->state = MSM_STOPPED;	
    pthread_mutex_unlock(&ctx->mutex);
}

ssize_t audio_write(msm_ctx *ctx, const void *buf, size_t count) {

    if(!ctx) return LIBLOSSLESS_ERR_NOCTX;
    switch(ctx->mode) {
        case MODE_DIRECT:
           return write(ctx->afd, buf, count);
        case MODE_LIBMEDIA:
           return libmedia_write(ctx, buf, count);
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
    if(ctx->mode == MODE_LIBMEDIA) libmedia_pause(ctx);
    return true;		
}

JNIEXPORT jboolean JNICALL Java_net_avs234_AndLessSrv_audioResume(JNIEnv *env, jobject obj, msm_ctx *ctx) {
    if(!ctx || ctx->state != MSM_PAUSED) return false;
    if(ctx->mode == MODE_LIBMEDIA) libmedia_resume(ctx);
    ctx->state = MSM_PLAYING;	
    pthread_mutex_unlock(&ctx->mutex);
    return true;	
}

JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_audioGetDuration(JNIEnv *env, jobject obj, msm_ctx *ctx) {
   if(!ctx || ctx->state != MSM_PLAYING) return 0;	
   return ctx->track_time;
}

JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_audioGetCurPosition(JNIEnv *env, jobject obj, msm_ctx *ctx) {
   if(!ctx || ctx->state != MSM_PLAYING || !ctx->channels || !ctx->samplerate || !ctx->bps) return 0;
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
    if(ctx->wavbuf) free(ctx->wavbuf);	
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


void update_track_time(JNIEnv *env, jobject obj, int time) {
     jclass cls = (*env)->GetObjectClass(env, obj);
     jmethodID mid = (*env)->GetStaticMethodID(env, cls, "updateTrackLen", "(I)V");
     if (mid == NULL) {
//	  __android_log_print(ANDROID_LOG_INFO,"liblossless","Cannot find java callback to update time");
         return; 
     }
//     __android_log_print(ANDROID_LOG_INFO,"liblossless",
//	"Updating track time in Java (env=%p obj=%p cls=%p mid=%p time=%d)",env,obj,cls,mid,time);
     (*env)->CallStaticVoidMethod(env,cls,mid,time);
}



/*
JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_setAfd(JNIEnv *env, jobject obj, msm_ctx *ctx, jobject fdobj) {
  jfieldID field = 0;
  jclass class_fdesc = 0;

    if(!ctx || ctx->afd < 0) return LIBLOSSLESS_ERR_NOCTX;	
    class_fdesc = (*env)->GetObjectClass(env, fdobj);
    if(!class_fdesc) return LIBLOSSLESS_ERR_NOCTX;	
    field = (*env)->GetFieldID(env, class_fdesc, "descriptor", "I");
    if(!field) return LIBLOSSLESS_ERR_NOCTX;
    (*env)->SetIntField(env, fdobj, field, ctx->afd);
    field = (*env)->GetFieldID(env, class_fdesc, "readOnly", "B");
    if(!field) return LIBLOSSLESS_ERR_NOCTX;
    (*env)->SetBooleanField(env, fdobj, field, true);

  return 0;
}
*/

