/***************************************************************************
 *             __________               __   ___.
 *   Open      \______   \ ____   ____ |  | _\_ |__   _______  ___
 *   Source     |       _//  _ \_/ ___\|  |/ /| __ \ /  _ \  \/  /
 *   Jukebox    |    |   (  <_> )  \___|    < | \_\ (  <_> > <  <
 *   Firmware   |____|_  /\____/ \___  >__|_ \|___  /\____/__/\_ \
 *                     \/            \/     \/    \/            \/
 * $Id: alac.c 19743 2009-01-10 21:10:56Z zagor $
 *
 * Copyright (C) 2005 Dave Chapman
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

#include "m4a.h"
#include "decomp.h"
#include "../main.h"
#include <android/log.h>


int32_t outputbuffer[ALAC_MAX_CHANNELS][ALAC_BLOCKSIZE] IBSS_ATTR;

JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_alacPlay(JNIEnv *env, jobject obj, msm_ctx* ctx, jstring jfile, jint start) {

  demux_res_t demux_res;
  stream_t input_stream;
  uint32_t samplesdone;
  uint32_t sample_duration;
  uint32_t sample_byte_size;
  int samplesdecoded, k, n;
  unsigned int i;
  alac_file alac;
  int retval;
  unsigned char bb[16];
  unsigned char *p;
  int bytes_to_write;
  int sample32;   

  const char *file = (*env)->GetStringUTFChars(env,jfile,NULL);
  struct timeval tstart, tstop, ttmp; // tstart -> time of the last write.
  useconds_t  tminwrite = 0;
  int prev_written = 0;
  unsigned char *inputbuf = 0;
  int inputbuf_sz = 80*1024;
  uint32_t total_samples = 0;

        if(!ctx) return LIBLOSSLESS_ERR_NOCTX;

        if(!file) {
                (*env)->ReleaseStringUTFChars(env,jfile,file);  
		return LIBLOSSLESS_ERR_INV_PARM;
        }
        audio_stop(ctx);

        ctx->fd = open(file,O_RDONLY);
        (*env)->ReleaseStringUTFChars(env,jfile,file);

        if(ctx->fd < 0) return LIBLOSSLESS_ERR_NOFILE;

	if(read(ctx->fd,bb,10) != 10) {
		close(ctx->fd); ctx->fd = -1;
		return LIBLOSSLESS_ERR_IO_READ;
	}
	if(memcmp(bb, "ID3",3) == 0) {
           uint32_t ts = bb[6] << 21;
	        ts += bb[7] << 14;
	        ts += bb[8] << 7;
	        ts += bb[9];
	        ts += 10;
	        if(lseek(ctx->fd, ts, SEEK_SET) < 0) {
		     close(ctx->fd); ctx->fd = -1;
		     return LIBLOSSLESS_ERR_FORMAT;
	        }
	} else lseek(ctx->fd,0,SEEK_SET);

	stream_create(&input_stream,ctx);

	if (!qtmovie_read(&input_stream, &demux_res)) {
            close(ctx->fd); ctx->fd = -1;
            return LIBLOSSLESS_ERR_FORMAT;
	}

	create_alac(demux_res.sound_sample_size, demux_res.num_channels,&alac);
	alac_set_info(&alac, (char *)demux_res.codecdata);

	for(i = 0; i < demux_res.num_sample_byte_sizes; i++)
	   if (get_sample_info(&demux_res, i, &sample_duration, &samplesdone)) total_samples += sample_duration;

	sample_duration = 0;
	samplesdone = 0;
	i = 0;
	if(start) {
	    if(!alac_seek(&demux_res,&input_stream,start*demux_res.sound_sample_rate,&samplesdone,(int *)&i)) {
	        close(ctx->fd); ctx->fd = -1;
        	return LIBLOSSLESS_ERR_OFFSET;
	    }	
	}
	inputbuf = (uint8_t *) malloc(inputbuf_sz);
	if(!inputbuf) {
		close(ctx->fd); ctx->fd = -1;
		return LIBLOSSLESS_ERR_NOMEM;
	}

        ctx->channels = demux_res.num_channels;
	ctx->samplerate =  demux_res.sound_sample_rate; 
	ctx->bps = demux_res.sound_sample_size;
        ctx->written = 0;

        retval = audio_start(ctx, ctx->channels, ctx->samplerate);

        if(retval != 0) {
             close(ctx->fd); ctx->fd = -1; free(inputbuf);
             return retval;
        }

        pthread_mutex_lock(&ctx->mutex);
        ctx->state = MSM_PLAYING;
        ctx->track_time = total_samples / demux_res.sound_sample_rate;
        pthread_mutex_unlock(&ctx->mutex);
        update_track_time(env,obj,ctx->track_time);

	bytes_to_write = 0;
	gettimeofday(&tstart,0);

	///////////////////////////////////////////////
	
	while (i < demux_res.num_sample_byte_sizes && ctx->state != MSM_STOPPED) {

	    /* Lookup the length (in samples and bytes) of block i */
	    if (!get_sample_info(&demux_res, i, &sample_duration, &sample_byte_size)) {
  		retval = LIBLOSSLESS_ERR_DECODE;
		goto done;
	    }
	
	    /* Request the required number of bytes from the input buffer */
	    if(sample_byte_size > inputbuf_sz)	{
		inputbuf = (uint8_t *) realloc(inputbuf, inputbuf_sz*2);
		if(!inputbuf) {		
			retval = LIBLOSSLESS_ERR_NOMEM;
			goto done;
		}
		inputbuf_sz *= 2;
	    }		    
	
	    stream_read(&input_stream,sample_byte_size,inputbuf);
	    if(input_stream.err != 0) { 
		if(ctx->fd == -1) break;
	        retval = LIBLOSSLESS_ERR_IO_READ;
		goto done;
	    }


	    /* Decode one block - returned samples will be host-endian */
	    samplesdecoded = alac_decode_frame(&alac, inputbuf, outputbuffer);
//	    if(samplesdecoded > 100000)	{///????
//		i++; continue;
//	    }

//__android_log_print(ANDROID_LOG_ERROR,"liblossless", "decoded %d samples", samplesdecoded);

	    p = ctx->wavbuf + bytes_to_write;

	    if(ctx->bps == 8) {
                for(k = 0; k < samplesdecoded; k++) {
                    *(p++) = (outputbuffer[0][k] + 0x80) & 0xff;
                    if (ctx->channels == 2) *(p++) = (outputbuffer[1][k] + 0x80) & 0xff;
                }
	    } else {
                for (k = 0; k < samplesdecoded; k++) {
                    sample32 = outputbuffer[0][k];
                    *(p++) = sample32 >> 13; //sample16 & 0xff;
                    *(p++) = sample32 >> 21; //(sample16 >> 8) & 0xff;
                    if (ctx->channels == 2) {
                        sample32 = outputbuffer[1][k];
                        *(p++) = sample32 >> 13; //sample16 & 0xff;
                        *(p++) = sample32 >> 21; //(sample16 >> 8) & 0xff;
                    }
                }
            }

	    n = p - ctx->wavbuf;	

	    if(n >= ctx->conf_size) {
//__android_log_print(ANDROID_LOG_ERROR,"liblossless", "need write, %d >= %d, prev=%d", n, ctx->conf_size, prev_written);
		if(prev_written && ctx->mode != MODE_CALLBACK) {
                    gettimeofday(&tstop,0);
                    tminwrite = ((uint64_t)((uint64_t)prev_written)*1000000)/
                        ((uint64_t)(ctx->samplerate*ctx->channels*(ctx->bps/8)));
                    timersub(&tstop,&tstart,&ttmp);
                    if(tminwrite > ttmp.tv_usec) {
				usleep((tminwrite-ttmp.tv_usec)/4);
//__android_log_print(ANDROID_LOG_ERROR,"liblossless", "Slept %d ms, writing", (tminwrite-ttmp.tv_usec)/4000);
		    }	
            	}
		if(ctx->mode != MODE_CALLBACK) gettimeofday(&tstart,0);
		prev_written = 0;
		p = ctx->wavbuf;
		do {
                     pthread_mutex_lock(&ctx->mutex);
	             if(ctx->fd < 0) k = 0; // we were closed from outside
		     else k = audio_write(ctx,p,ctx->conf_size);
                     pthread_mutex_unlock(&ctx->mutex);
		     if(k == 0)	break;
        	     if(k < ctx->conf_size) {
			retval = LIBLOSSLESS_ERR_IO_WRITE;
		    	goto done;		
                     }
	             n -= k; p += k;
                     prev_written += k;
                     ctx->written += k;
            	} while(n >= ctx->conf_size);
	        memmove(ctx->wavbuf,p,n);
	    }
	    bytes_to_write = n;
	    samplesdone += sample_duration;
    	    i++;
//__android_log_print(ANDROID_LOG_ERROR,"liblossless", "Wrote, going to next cycle");
	}

	retval = 0;
done:
	if(ctx->state != MSM_STOPPED) {
	     if(ctx->state != MSM_PAUSED) pthread_mutex_lock(&ctx->mutex);
	     if(ctx->fd != -1) {
                close(ctx->fd); ctx->fd = -1;
             }
	     ctx->state = MSM_STOPPED;
	     pthread_mutex_unlock(&ctx->mutex);
	}
	free(inputbuf);

        audio_wait_done(ctx);

   return retval;
}
