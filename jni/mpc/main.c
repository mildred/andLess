/***************************************************************************
 *             __________               __   ___.
 *   Open      \______   \ ____   ____ |  | _\_ |__   _______  ___
 *   Source     |       _//  _ \_/ ___\|  |/ /| __ \ /  _ \  \/  /
 *   Jukebox    |    |   (  <_> )  \___|    < | \_\ (  <_> > <  <
 *   Firmware   |____|_  /\____/ \___  >__|_ \|___  /\____/__/\_ \
 *                     \/            \/     \/    \/            \/
 * $Id: mpc.c 20756 2009-04-20 19:16:48Z Buschel $
 *
 * Copyright (C) 2005 Thom Johansen
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
#include <stdlib.h>
#include <fcntl.h>
#include <stdint.h>
#include <pthread.h>
#include <sys/time.h>
#include <sys/stat.h>
#include <unistd.h>
#include <fcntl.h>
#include "../main.h"
#ifndef TEST
#include <android/log.h>
#endif

#include "mpcdec/mpc_types.h"
#include "mpcdec/reader.h"
#include "mpcdec/streaminfo.h"
#include "mpcdec/mpcdec.h"
#include "mpcdec/decoder.h"


static mpc_int32_t read_impl(void *data, void *ptr, mpc_int32_t size)
{
    msm_ctx *ctx = (msm_ctx *)data;
    return (mpc_int32_t) read(ctx->fd,ptr,size);
}

static mpc_bool_t seek_impl(void *data, mpc_int32_t offset)
{  
    msm_ctx *ctx = (msm_ctx *)data;
    return lseek(ctx->fd,offset,SEEK_SET) >= 0;
}

static mpc_int32_t tell_impl(void *data)
{
    msm_ctx *ctx = (msm_ctx *)data;
    return lseek(ctx->fd,0,SEEK_CUR);
}

static mpc_int32_t get_size_impl(void *data)
{
    msm_ctx *ctx = (msm_ctx *)data;
    off_t cur = lseek(ctx->fd,0,SEEK_CUR);
    off_t sz  = lseek(ctx->fd,0,SEEK_END);
    lseek(ctx->fd,cur,SEEK_SET); 		
    return sz;
}

static mpc_bool_t canseek_impl(void *data)
{
    (void)data;
    return 1;
}




#if 0
static int  shift_signed(MPC_SAMPLE_FORMAT val, int shift) {
    if (shift > 0)
        val <<= shift;
    else if (shift < 0)
        val >>= -shift;
    return (int)val;
}
mpc_bool_t WriteSamples(const MPC_SAMPLE_FORMAT * p_buffer, unsigned p_size, int m_bps) {

        unsigned n, shift;
        int clip_min = -1 << (m_bps-1), clip_max = (1 << (m_bps-1)) - 1;
	unsigned char *temp = (unsigned char *) p_buffer;

        for (n = 0; n < p_size; n++) {

	    int val = p_buffer[n] >> 14;

            if (val < clip_min)  val = clip_min;
            else if (val > clip_max) val = clip_max;

	    shift = 0;	
	    temp[0] = (unsigned char) ((unsigned int)val & 0xFF);
	    temp[1] = (unsigned char) ((((unsigned int)val) >> 8) & 0xFF); 		
	    temp += 2;
        }
//        m_data_bytes_written += p_size * (m_bps >> 3);
       return 1;
}
mpc_bool_t WriteInt(unsigned int p_val, unsigned p_width_bits) {
	        unsigned char temp;
	        unsigned shift = 0;
        do {
	            temp = (unsigned char)((p_val >> shift) & 0xFF);
	            if (!WriteRaw(&temp, 1))
	                return false;
	            shift += 8;
	        } while (shift < p_width_bits);
	        return true;
}
#endif


mpc_decoder decoder;

JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_mpcPlay(JNIEnv *env, jobject obj, msm_ctx* ctx, jstring jfile, jint start) {
    const char *file = (*env)->GetStringUTFChars(env,jfile,NULL);
    int i, n;
    struct timeval tstart, tstop, ttmp;
    useconds_t  tminwrite;

    int prev_written = 0;

#ifdef DBG_TIME
        uint64_t total_tminwrite = 0, total_ttmp = 0, total_sleep = 0;
        int writes = 0, fails = 0;
#endif


    unsigned int status;
    mpc_reader reader;
    mpc_streaminfo info;
    unsigned char *p;
    MPC_SAMPLE_FORMAT *pp; 
    int bytes_to_write = 0;	

    if(!ctx) return LIBLOSSLESS_ERR_NOCTX;

    if(!file) {
                (*env)->ReleaseStringUTFChars(env,jfile,file);  return LIBLOSSLESS_ERR_INV_PARM;
    }
    audio_stop(ctx);

    ctx->fd = open(file,O_RDONLY);
    (*env)->ReleaseStringUTFChars(env,jfile,file);

    if(ctx->fd < 0) return LIBLOSSLESS_ERR_NOFILE;

    reader.read = read_impl;
    reader.seek = seek_impl;
    reader.tell = tell_impl;
    reader.get_size = get_size_impl;
    reader.canseek = canseek_impl;
    reader.data = ctx;

    mpc_streaminfo_init(&info);
    
    if (mpc_streaminfo_read(&info, &reader) != ERROR_CODE_OK) {
	close(ctx->fd);
	return LIBLOSSLESS_ERR_FORMAT;
    }	
    if (info.channels != 2 && info.channels != 1) {
	close(ctx->fd);
	return LIBLOSSLESS_ERR_FORMAT;
    }	
    
    mpc_decoder_setup(&decoder, &reader);
    if (!mpc_decoder_initialize(&decoder, &info)) {
	close(ctx->fd);
	return LIBLOSSLESS_ERR_FORMAT;
    }	
    if (start)	{
	if(!mpc_decoder_seek_sample(&decoder,start*info.sample_freq)) {
	    close(ctx->fd);	
	    return LIBLOSSLESS_ERR_OFFSET;
	}
    }	

    i = audio_start(ctx, info.channels, info.sample_freq);
    if(i != 0) {
          close(ctx->fd);
          return i;
    }

    ctx->channels = info.channels;
    ctx->samplerate = info.sample_freq;
    ctx->bps = 16;
    ctx->written = 0;

    pthread_mutex_lock(&ctx->mutex);
    ctx->state = MSM_PLAYING;
    ctx->track_time = mpc_streaminfo_get_length_samples(&info)/info.sample_freq;	
    pthread_mutex_unlock(&ctx->mutex);

    update_track_time(env,obj,ctx->track_time);


//   gettimeofday(&tstart,0);

// MPC_DECODER_BUFFER_LENGTH = 36*32*2*4 = 9216
    while (ctx->state != MSM_STOPPED) {

        status = mpc_decoder_decode(&decoder,(MPC_SAMPLE_FORMAT *) (ctx->wavbuf+bytes_to_write), NULL, NULL);

        if (status == 0) break; /* end of file reached */

        if (status == (unsigned)(-1)) { /* decode error */
	     if(ctx->state != MSM_STOPPED) {
                        if(ctx->state != MSM_PAUSED) pthread_mutex_lock(&ctx->mutex);
                        ctx->state = MSM_STOPPED;
                        close(ctx->fd); ctx->fd = -1;
                        pthread_mutex_unlock(&ctx->mutex);
             }
             return LIBLOSSLESS_ERR_DECODE;
        } 

	pp = (MPC_SAMPLE_FORMAT *) (ctx->wavbuf+bytes_to_write);
	p = ctx->wavbuf+bytes_to_write;
        for (n = 0; n < status*2; n++, p += 2) {
#ifndef MPC_FIXED_POINT
	   int fscale = 1 << 15;
#endif
          int val;
#ifdef MPC_FIXED_POINT
	    val = (pp[n] >> 14);
#else
	    val = pp[n] * fscale;	
#endif
            if (val < -32768)  val = -32768;
            else if (val > 32767) val = 32767;
            p[0] = (unsigned char) ((unsigned int)val & 0xFF);
            p[1] = (unsigned char) ((((unsigned int)val) >> 8) & 0xFF);
	    	
        }



       n = status*4;

       if(n + bytes_to_write >= ctx->conf_size) {
            p = ctx->wavbuf; n += bytes_to_write;


            if(prev_written) {
                tminwrite = ((uint64_t)((uint64_t)(prev_written))*1000000)/((uint64_t)(info.sample_freq*4));
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
            gettimeofday(&tstart,0);
	    prev_written = 0;	
            do {
                pthread_mutex_lock(&ctx->mutex);
                i = audio_write(ctx,p,ctx->conf_size);
                if(i < ctx->conf_size) {
                    ctx->state = MSM_STOPPED;
                    pthread_mutex_unlock(&ctx->mutex);
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
                n -= ctx->conf_size;
                p += ctx->conf_size;
		prev_written += ctx->conf_size;
		ctx->written += i;
            } while(n >= ctx->conf_size);
            memmove(ctx->wavbuf,p,n);
            bytes_to_write = n;
        } else bytes_to_write += n;

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

