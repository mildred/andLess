/* Copyright (C) 2008 The Android Open Source Project
 */

#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <stdint.h>
#include <pthread.h>
//#include <sys/select.h>
#include <sys/time.h>
//#include <unistd.h>
#include "../main.h"
#include <android/log.h>

struct msm_audio_stats {
    uint32_t out_bytes;
    uint32_t unused[3];
};


#define ID_RIFF 0x46464952
#define ID_WAVE 0x45564157
#define ID_FMT  0x20746d66
#define ID_DATA 0x61746164

#define FORMAT_PCM 1

struct wav_header {
	uint32_t riff_id;
	uint32_t riff_sz;
	uint32_t riff_fmt;
	uint32_t fmt_id;
	uint32_t fmt_sz;
	uint16_t audio_format;
	uint16_t num_channels;
	uint32_t sample_rate;
	uint32_t byte_rate;       /* sample_rate * num_channels * bps / 8 */
	uint16_t block_align;     /* num_channels * bps / 8 */
	uint16_t bits_per_sample;
	uint32_t data_id;
	uint32_t data_sz;
};


static int wav_hdr(int fd, unsigned *rate, unsigned *channels, unsigned *bps) {

    struct wav_header hdr;

    
    if(read(fd, &hdr, sizeof(hdr)) != sizeof(hdr)) {
		return -1;
	}
    
    if ((hdr.riff_id != ID_RIFF) ||
        (hdr.riff_fmt != ID_WAVE) ||
        (hdr.fmt_id != ID_FMT)) {

        return -1;
    }
    if ((hdr.audio_format != FORMAT_PCM) ||
        (hdr.fmt_sz != 16)) {
        return -1;
    }
    if (hdr.bits_per_sample != 16) {

        return -1;
    }
    *rate =  hdr.sample_rate;
    *channels = hdr.num_channels;
    *bps = hdr.bits_per_sample;

    return 0;
}

JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_wavPlay(JNIEnv *env, jobject obj, msm_ctx* ctx, jstring jfile, jint start) {

    const char *file = (*env)->GetStringUTFChars(env,jfile,NULL);
    int i, n;
    unsigned rate, channels, bps;
    unsigned char *buff;
//    fd_set fds;

    struct timeval tstart, tstop, ttmp; 	
    useconds_t  tminwrite;		
    int writes = 0;
    off_t fsize;
	
#ifdef DBG_TIME
        uint64_t total_tminwrite = 0, total_ttmp = 0, total_sleep = 0;
        int  fails = 0;
#endif


	if(!ctx) return LIBLOSSLESS_ERR_NOCTX;
		
	if(!file) {
		(*env)->ReleaseStringUTFChars(env,jfile,file); 	return LIBLOSSLESS_ERR_INV_PARM;
	}
	audio_stop(ctx);

	ctx->fd = open(file,O_RDONLY);
	(*env)->ReleaseStringUTFChars(env,jfile,file);

	if(ctx->fd < 0) return LIBLOSSLESS_ERR_NOFILE;

	fsize = lseek(ctx->fd,0,SEEK_END) - sizeof(wav_hdr);
	lseek(ctx->fd,0,SEEK_SET);

	if(wav_hdr(ctx->fd, &rate, &channels, &bps) != 0) return LIBLOSSLESS_ERR_FORMAT;

	if(start) {
		int start_offs = start * (bps/8) * channels * rate;
		if(lseek(ctx->fd,start_offs,SEEK_CUR) < 0) return LIBLOSSLESS_ERR_OFFSET;
	}

	i = audio_start(ctx, channels, rate);
	if(i != 0) {
	        close(ctx->fd);
	        return i;
	}
	buff = (char *) malloc(ctx->conf_size);
	if(!buff) {
		close(ctx->fd);
		return LIBLOSSLESS_ERR_NOMEM;
	}

	tminwrite = ((long long)((long long)ctx->conf_size)*1000000)/((long long)rate*channels*(bps/8));
	

#if 0
	sprintf(buff,"*******TMINWRITEEEEE = %d %d %d %d %d",tminwrite,ctx->conf_size,rate,channels,bps);
	__android_log_print(ANDROID_LOG_INFO,"liblossless",buff);
#endif
        ctx->channels = channels;
        ctx->samplerate = rate;
        ctx->bps = bps;
	ctx->written = 0;

	pthread_mutex_lock(&ctx->mutex);
	ctx->state = MSM_PLAYING;
	ctx->track_time = fsize /(rate*channels*(bps/8));
	pthread_mutex_unlock(&ctx->mutex);
        update_track_time(env,obj,ctx->track_time);


	while(ctx->state != MSM_STOPPED) {

		n = read(ctx->fd,buff, ctx->conf_size);
		if(n != ctx->conf_size) {
			if(ctx->state != MSM_STOPPED) {
			   if(ctx->state != MSM_PAUSED) pthread_mutex_lock(&ctx->mutex);
	        	   ctx->state = MSM_STOPPED;
		           pthread_mutex_unlock(&ctx->mutex);
			}
			free(buff);
	                if(ctx->fd == -1) return 0; // we were stopped from the main thread
	                close(ctx->fd); ctx->fd = -1;
	                return 	LIBLOSSLESS_ERR_IO_READ;
		}
		gettimeofday(&tstop,0);
		timersub(&tstop,&tstart,&ttmp);
		if(tminwrite > ttmp.tv_usec) { 
			usleep((tminwrite-ttmp.tv_usec)/4);		
#ifdef DBG_TIME
                        total_sleep += (tminwrite - ttmp.tv_usec)/4;
#endif
                }
#ifdef DBG_TIME
            else fails++;
            total_tminwrite += tminwrite;
            total_ttmp += ttmp.tv_usec;
#endif
		gettimeofday(&tstart,0);
		pthread_mutex_lock(&ctx->mutex);
		i = audio_write(ctx,buff,ctx->conf_size);
		if(i < ctx->conf_size) {
	            ctx->state = MSM_STOPPED;
                    pthread_mutex_unlock(&ctx->mutex);
		    free(buff); 	
                    if(ctx->fd == -1) { 
#ifdef DBG_TIME
        if(writes && (writes > fails)) {
           int x = (int) (total_tminwrite/writes);
           int y = (int) (total_ttmp/writes);
           int z = (int) (total_sleep/(writes-fails));
            __android_log_print(ANDROID_LOG_INFO,"liblossless","tminwrite %d ttmp %d sleep %d fails %d writes %d", x,y,z,fails,writes);
        } else __android_log_print(ANDROID_LOG_INFO,"liblossless","fails %d writes %d", fails,writes);
#endif

			return 0; // we were stopped from the main thread
		    }	
                    close(ctx->fd); ctx->fd = -1;
                    return LIBLOSSLESS_ERR_IO_WRITE;
		}
		pthread_mutex_unlock(&ctx->mutex);
		ctx->written += i;
	  writes++;
	}

    if(ctx->state != MSM_STOPPED) {
        if(ctx->state != MSM_PAUSED) pthread_mutex_lock(&ctx->mutex);
        if(ctx->fd != -1) {
                close(ctx->fd); ctx->fd = -1;
        }
        ctx->state = MSM_STOPPED;
        pthread_mutex_unlock(&ctx->mutex);
    }

#ifdef DBG_TIME
        if(writes && (writes > fails)) {
           int x = (int) (total_tminwrite/writes);
           int y = (int) (total_ttmp/writes);
           int z = (int) (total_sleep/(writes-fails));
            __android_log_print(ANDROID_LOG_INFO,"liblossless","tminwrite %d ttmp %d sleep %d fails %d writes %d", x,y,z,fails,writes);
        } else __android_log_print(ANDROID_LOG_INFO,"liblossless","fails %d writes %d", fails,writes);
#endif
   free(buff);
   return 0;	
}




