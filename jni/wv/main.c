/***************************************************************************
 *             __________               __   ___.
 *   Open      \______   \ ____   ____ |  | _\_ |__   _______  ___
 *   Source     |       _//  _ \_/ ___\|  |/ /| __ \ /  _ \  \/  /
 *   Jukebox    |    |   (  <_> )  \___|    < | \_\ (  <_> > <  <
 *   Firmware   |____|_  /\____/ \___  >__|_ \|___  /\____/__/\_ \
 *                     \/            \/     \/    \/            \/
 * $Id: wavpack.c 19743 2009-01-10 21:10:56Z zagor $
 *
 * Copyright (C) 2005 David Bryant
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY
 * KIND, either express or implied.
 *
 ****************************************************************************/


#include <stdio.h>
#include "wavpack.h"

#include <string.h>
#include <stdlib.h>
#include <inttypes.h>
#include <stdbool.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/types.h>
// #include <sys/stat.h>
#include <pthread.h>
#include <sched.h>
#include <sys/select.h>
#include <sys/time.h>

#include "../main.h"
#include <android/log.h>


JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_wvPlay(JNIEnv *env, jobject obj, msm_ctx* ctx, jstring jfile, jint start) {

    const char *file = (*env)->GetStringUTFChars(env,jfile,NULL);
    int i, k;
    WavpackContext *wpc;
    char error [80];
    int bps, nchans, samplerate;
    int32_t * temp_buffer; 	
//    fd_set fds;
    struct timeval tstart, tstop, ttmp; // tstart -> time of the last write.
    useconds_t  tminwrite;
    int prev_written = 0;
    uint32_t num_samples;
#ifdef DBG_TIME
     uint64_t total_tminwrite = 0, total_ttmp = 0, total_sleep = 0;
     int writes = 0, fails = 0;
#endif


      if(!ctx) return LIBLOSSLESS_ERR_NOCTX;

        if(!file) {
                (*env)->ReleaseStringUTFChars(env,jfile,file);  return LIBLOSSLESS_ERR_INV_PARM;
        }
        audio_stop(ctx);

        ctx->fd = open(file,O_RDONLY);
        (*env)->ReleaseStringUTFChars(env,jfile,file);

        if(ctx->fd < 0) return LIBLOSSLESS_ERR_NOFILE;

        wpc = WavpackOpenFileInput(ctx,error);
	if(!wpc) return LIBLOSSLESS_ERR_FORMAT;

	bps = WavpackGetBytesPerSample(wpc);
	nchans = WavpackGetReducedChannels(wpc);
	samplerate = WavpackGetSampleRate(wpc); 
	num_samples = WavpackGetNumSamples(wpc);

	if(start) {

	    off_t     seek_offs, fsize = lseek(ctx->fd,0,SEEK_END);
	    uint32_t  j, idx = 0, need_sample = start * samplerate;

 		seek_offs = ((int64_t) need_sample*fsize)/num_samples;
	        if(lseek(ctx->fd,seek_offs,SEEK_SET) < 0) return LIBLOSSLESS_ERR_OFFSET;

                wpc = WavpackOpenFileInput(ctx,error);
		if(!wpc) return LIBLOSSLESS_ERR_FORMAT;
                idx = WavpackGetSampleIndex(wpc);

		for(j = 0; j < 5; j++) {
	            if(need_sample > idx) {
        	        int n = need_sample - idx;
	                int d = num_samples - idx;
        	        int skip = (int)((int64_t)(fsize - seek_offs) * n / d);
                	lseek(ctx->fd, seek_offs + skip, SEEK_SET);
		    } else if(need_sample < idx) {
	                int n = idx - need_sample;
	                int d = idx;
	                int skip = (int)((int64_t) seek_offs * n / d);
	                lseek(ctx->fd, seek_offs - skip, SEEK_SET);
		    } else break;
		    wpc = WavpackOpenFileInput(ctx,error);
		    idx = WavpackGetSampleIndex(wpc);
		    seek_offs = lseek(ctx->fd,0,SEEK_CUR);
		}

//    __android_log_print(ANDROID_LOG_INFO,"liblossless", "cycles=%d: needed %d, got %d, delta sec=%d\n",
//			j,need_sample, idx, ((int)idx-(int)need_sample)/samplerate);
	}

        i = audio_start(ctx, nchans, samplerate);

        if(i != 0) {
                close(ctx->fd);
                return i;
        }
	temp_buffer = (int32_t *) (ctx->wavbuf + 32*1024);

        ctx->channels = nchans;
        ctx->samplerate = samplerate;
        ctx->bps = bps*8;
	ctx->written = 0;

        pthread_mutex_lock(&ctx->mutex);
        ctx->state = MSM_PLAYING;
	ctx->track_time = num_samples/samplerate;
        pthread_mutex_unlock(&ctx->mutex);

        update_track_time(env,obj,ctx->track_time);


//        gettimeofday(&tstart,0);

    while (ctx->state != MSM_STOPPED) {
	uint32_t *p = (uint32_t *) temp_buffer;
        unsigned char *c = ctx->wavbuf;

        int32_t nsamples;

	nsamples = WavpackUnpackSamples(wpc, temp_buffer, ctx->conf_size / (2*nchans));  

        if (!nsamples) break;

	for(i = 0; i < nsamples; i++) {
	    for(k = 0; k < nchans; k++, p++, c+= 2)	{
		c[0] = 	(p[0] >> 13);
                c[1] =  (p[0] >> 21);
	    }	
	}

	if(prev_written && ctx->mode != MODE_CALLBACK) {
	   tminwrite = ((uint64_t)((uint64_t)prev_written)*1000000)/((uint64_t)(samplerate*bps*nchans));
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
           writes++;
           total_tminwrite += tminwrite;
           total_ttmp += ttmp.tv_usec;
#endif
        }

        if(ctx->mode != MODE_CALLBACK) gettimeofday(&tstart,0);
	prev_written = nsamples*2*nchans;

	pthread_mutex_lock(&ctx->mutex);
	i = audio_write(ctx,ctx->wavbuf,nsamples*2*nchans);
        if(i != nsamples*2*nchans) {
            ctx->state = MSM_STOPPED;
            pthread_mutex_unlock(&ctx->mutex);
            if(ctx->fd == -1) {
#ifdef DBG_TIME
	        if(writes && (writes > fails)) {
        	   int x = (int) (total_tminwrite/writes);
	           int y = (int) (total_ttmp/writes);
	           int z = (int) (total_sleep/(writes-fails));
	            __android_log_print(ANDROID_LOG_INFO,"liblossless",
			"tminwrite %d ttmp %d sleep %d fails %d writes %d", x,y,z,fails,writes);
	        } else __android_log_print(ANDROID_LOG_INFO,"liblossless","fails %d writes %d", fails,writes);
#endif

		return 0; // we were stopped from the main thread
	    }	
            close(ctx->fd); ctx->fd = -1;
            return LIBLOSSLESS_ERR_IO_WRITE;
        }
	ctx->written += i;
        pthread_mutex_unlock(&ctx->mutex);
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

    return 0;
}

JNIEXPORT jint JNICALL Java_com_skvalex_amplayer_wvDuration(JNIEnv *env, jobject obj, msm_ctx* ctx, jstring jfile) {
	const char *file = (*env)->GetStringUTFChars(env,jfile,NULL);
	    WavpackContext *wpc;
	    char error [80];
	    int bps, nchans, samplerate;
	//    fd_set fds;
	    uint32_t num_samples;
	#ifdef DBG_TIME
	     uint64_t total_tminwrite = 0, total_ttmp = 0, total_sleep = 0;
	     int writes = 0, fails = 0;
	#endif


	      if(!ctx) return -1;

	        if(!file) {
	                (*env)->ReleaseStringUTFChars(env,jfile,file);  return -1;
	        }
	        audio_stop(ctx);

	        ctx->fd = open(file,O_RDONLY);
	        (*env)->ReleaseStringUTFChars(env,jfile,file);

	        if(ctx->fd < 0) return -1;

	        wpc = WavpackOpenFileInput(ctx,error);
		if(!wpc) return -1;

		bps = WavpackGetBytesPerSample(wpc);
		nchans = WavpackGetReducedChannels(wpc);
		samplerate = WavpackGetSampleRate(wpc);
		num_samples = WavpackGetNumSamples(wpc);
	return num_samples/samplerate;
}
