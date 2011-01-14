/*

demac - A Monkey's Audio decoder

$Id: demac.c 19517 2008-12-21 01:29:36Z amiconn $

Copyright (C) Dave Chapman 2007

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110, USA

*/

/* 

This example is intended to demonstrate how the decoder can be used in
embedded devices - there is no usage of dynamic memory (i.e. no
malloc/free) and small buffer sizes are chosen to minimise both the
memory usage and decoding latency.

This implementation requires the following memory and supports decoding of all APE files up to 24-bit Stereo.

32768 - data from the input stream to be presented to the decoder in one contiguous chunk.
18432 - decoding buffer (left channel)
18432 - decoding buffer (right channel)

17408+5120+2240 - buffers used for filter histories (compression levels 2000-5000)

In addition, this example uses a static 27648 byte buffer as temporary
storage for outputting the data to a WAV file but that could be
avoided by writing the decoded data one sample at a time.

*/

#include <stdio.h>
#include <inttypes.h>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/select.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <pthread.h>
#include <sched.h>
#include "demac.h"
#include "../main.h"

#include <android/log.h>


#define BLOCKS_PER_LOOP     4608
#define MAX_CHANNELS        2
#define MAX_BYTESPERSAMPLE  3

#define INPUT_CHUNKSIZE     (32*1024)

#ifndef MIN
#define MIN(a,b) ((a) < (b) ? (a) : (b))
#endif


static int ape_calc_seekpos(struct ape_ctx_t* ape_ctx,
                             uint32_t new_sample,
                             uint32_t* newframe,
                             uint32_t* filepos,
                             uint32_t* samplestoskip)
{
    uint32_t n;

    n = new_sample / ape_ctx->blocksperframe;
    if (n >= ape_ctx->numseekpoints)
    {
        /* We don't have a seekpoint for that frame */
        return 0;
    }

    *newframe = n;
    *filepos = ape_ctx->seektable[n];
    *samplestoskip = new_sample - (n * ape_ctx->blocksperframe);

    return 1;
}



JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_apePlay(JNIEnv *env, jobject obj, msm_ctx* ctx, jstring jfile, jint start) {

    int currentframe, nblocks, bytesconsumed;
    int bytesinbuffer, blockstodecode, firstbyte;
    int i = 0, n, bytes_to_write;

    int16_t  sample16;
    int32_t  sample32;

    const char *file = (*env)->GetStringUTFChars(env,jfile,NULL);

    unsigned char inbuffer[INPUT_CHUNKSIZE];
    int32_t decoded0[BLOCKS_PER_LOOP];
    int32_t decoded1[BLOCKS_PER_LOOP];

    unsigned char *p;	

    struct timeval tstart, tstop, ttmp; // tstart -> time of the last write.
    useconds_t  tminwrite;

    struct ape_ctx_t ape_ctx;
    int prev_written = 0;

    uint32_t samplestoskip;
	
#ifdef DBG_TIME
        uint64_t total_tminwrite = 0, total_ttmp = 0, total_sleep = 0;
        int writes = 0, fails = 0;
#endif


	if(!ctx) return LIBLOSSLESS_ERR_NOCTX;
		
	if(!file) {
		(*env)->ReleaseStringUTFChars(env,jfile,file); 	return LIBLOSSLESS_ERR_INV_PARM;
	}
	audio_stop(ctx);

	ctx->fd = open(file,O_RDONLY);
	(*env)->ReleaseStringUTFChars(env,jfile,file);

	if(ctx->fd < 0) return LIBLOSSLESS_ERR_NOFILE;
  
    /* Read the file headers to populate the ape_ctx struct */

	if(read(ctx->fd, ctx->wavbuf, INPUT_CHUNKSIZE) != INPUT_CHUNKSIZE) return LIBLOSSLESS_ERR_IO_READ;

	if(ape_parseheaderbuf(ctx->wavbuf,&ape_ctx) < 0) return LIBLOSSLESS_ERR_FORMAT;

	if ((ape_ctx.fileversion < APE_MIN_VERSION) || (ape_ctx.fileversion > APE_MAX_VERSION)) {
	        close(ctx->fd);
	        return LIBLOSSLESS_ERR_FORMAT;
	}

	if(start) {
	   uint32_t filepos, newframe, start_sample;

	        ape_ctx.seektable = (uint32_t *) malloc(ape_ctx.seektablelength);
	        if(!ape_ctx.seektable) {
        	        close(ctx->fd);
	                return LIBLOSSLESS_ERR_NOMEM;
	        }
        	if(lseek(ctx->fd, ape_ctx.seektablefilepos, SEEK_SET) < 0) {
                	free(ape_ctx.seektable);
	                close(ctx->fd);
        	        return LIBLOSSLESS_ERR_FORMAT;
	        }
        	if(read(ctx->fd, ape_ctx.seektable, ape_ctx.seektablelength) != ape_ctx.seektablelength) {
                	free(ape_ctx.seektable);
	                close(ctx->fd);
        	        return LIBLOSSLESS_ERR_FORMAT;
	        }

		start_sample = ape_ctx.samplerate * start;

		if(ape_calc_seekpos(&ape_ctx, start * ape_ctx.samplerate,&newframe,&filepos,&samplestoskip) == 0) {
		        free(ape_ctx.seektable);
	                close(ctx->fd);
        	        return LIBLOSSLESS_ERR_OFFSET;
    		}

//__android_log_print(ANDROID_LOG_INFO,"liblossless","found frame %d, pos %d, to skip %d\n",newframe,filepos,samplestoskip);

    		free(ape_ctx.seektable);
		ape_ctx.seektable = 0;

                firstbyte = 3 - (filepos & 3);
                filepos &= ~3;

		if(lseek(ctx->fd, filepos, SEEK_SET) < 0) {
                        close(ctx->fd);
                        return LIBLOSSLESS_ERR_FORMAT;
		}
		currentframe = newframe;
	} else {
        	if(lseek(ctx->fd, ape_ctx.firstframe, SEEK_SET) < 0) {
                        close(ctx->fd);
                        return LIBLOSSLESS_ERR_FORMAT;
                }
		samplestoskip = 0;
		currentframe = 0;
		firstbyte = 3;
	}

	i = audio_start(ctx, ape_ctx.channels, ape_ctx.samplerate);
	if(i != 0) {
	        close(ctx->fd);
	        return i;
	}

        ctx->channels = ape_ctx.channels;
        ctx->samplerate = ape_ctx.samplerate;
        ctx->bps = ape_ctx.bps;
	ctx->written = 0;

	pthread_mutex_lock(&ctx->mutex);
	ctx->state = MSM_PLAYING;
 	ctx->track_time = ape_ctx.totalsamples/ape_ctx.samplerate;
	pthread_mutex_unlock(&ctx->mutex);

        update_track_time(env,obj,ctx->track_time);


	bytes_to_write = 0;

    /* Initialise the buffer */
	bytesinbuffer = read(ctx->fd, inbuffer, INPUT_CHUNKSIZE);
//	firstbyte = 3;  /* Take account of the little-endian 32-bit byte ordering */

    /* The main decoding loop - we decode the frames a small chunk at a time */
    while (currentframe < ape_ctx.totalframes && (ctx->state != MSM_STOPPED))
    {
        /* Calculate how many blocks there are in this frame */
        if (currentframe == (ape_ctx.totalframes - 1))
            nblocks = ape_ctx.finalframeblocks;
        else
            nblocks = ape_ctx.blocksperframe;

        ape_ctx.currentframeblocks = nblocks;

        /* Initialise the frame decoder */
        init_frame_decoder(&ape_ctx, inbuffer, &firstbyte, &bytesconsumed);

        /* Update buffer */
        memmove(inbuffer,inbuffer + bytesconsumed, bytesinbuffer - bytesconsumed);
        bytesinbuffer -= bytesconsumed;

        n = read(ctx->fd, inbuffer + bytesinbuffer, INPUT_CHUNKSIZE - bytesinbuffer);
	if(n < 0) break;
        bytesinbuffer += n;


        /* Decode the frame a chunk at a time */
        while (nblocks > 0 && (ctx->state != MSM_STOPPED))
        {
            blockstodecode = MIN(BLOCKS_PER_LOOP, nblocks);

            if ((n = decode_chunk(&ape_ctx, inbuffer, &firstbyte,
			&bytesconsumed, decoded0, decoded1, blockstodecode)) < 0)  {
		if(ctx->state != MSM_STOPPED) {
			if(ctx->state != MSM_PAUSED) pthread_mutex_lock(&ctx->mutex);
			ctx->state = MSM_STOPPED;		
	                close(ctx->fd); ctx->fd = -1;
			pthread_mutex_unlock(&ctx->mutex);
                }
		return LIBLOSSLESS_ERR_DECODE;
            }
            /* Convert the output samples to WAV format and write to output file */
            p = ctx->wavbuf + bytes_to_write;

            if (ape_ctx.bps == 8) {
                for (i = 0 ; i < blockstodecode ; i++)
                {
                    /* 8 bit WAV uses unsigned samples */
                    *(p++) = (decoded0[i] + 0x80) & 0xff;

                    if (ape_ctx.channels == 2) {
                        *(p++) = (decoded1[i] + 0x80) & 0xff;
                    }
                }
            } else if (ape_ctx.bps == 16) {
                for (i = 0 ; i < blockstodecode ; i++)
                {
                    sample16 = decoded0[i];
                    *(p++) = sample16 & 0xff;
                    *(p++) = (sample16 >> 8) & 0xff;

                    if (ape_ctx.channels == 2) {
                        sample16 = decoded1[i];
                        *(p++) = sample16 & 0xff;
                        *(p++) = (sample16 >> 8) & 0xff;
                    }
                }
            } else if (ape_ctx.bps == 24) {
                for (i = 0 ; i < blockstodecode ; i++)
                {
                    sample32 = decoded0[i];
                    *(p++) = sample32 & 0xff;
                    *(p++) = (sample32 >> 8) & 0xff;
                    *(p++) = (sample32 >> 16) & 0xff;

                    if (ape_ctx.channels == 2) {
                        sample32 = decoded1[i];
                        *(p++) = sample32 & 0xff;
                        *(p++) = (sample32 >> 8) & 0xff;
                        *(p++) = (sample32 >> 16) & 0xff;
                    }
                }
            }

            if(samplestoskip) {
                uint32_t bytestoskip = 0, samples = 0;

                n = p - ctx->wavbuf;

                if (ape_ctx.bps == 8) samples = (ape_ctx.channels == 2) ? (n >> 1) : n;
                else if (ape_ctx.bps == 16) samples = (ape_ctx.channels == 2) ? (n >> 2) : (n >> 1);
                else if (ape_ctx.bps == 24) samples = (ape_ctx.channels == 2) ? (n / 6) : (n / 3);
                
                if(samplestoskip >= samples) {
                        samplestoskip -= samples;
		        memmove(inbuffer,inbuffer + bytesconsumed, bytesinbuffer - bytesconsumed);
		        bytesinbuffer -= bytesconsumed;
		        n = read(ctx->fd, inbuffer + bytesinbuffer, INPUT_CHUNKSIZE - bytesinbuffer);
		        if(n < 0) {
                	   if(ctx->state != MSM_STOPPED) {
		               if(ctx->state != MSM_PAUSED) pthread_mutex_lock(&ctx->mutex);
		               ctx->state = MSM_STOPPED;
		               pthread_mutex_unlock(&ctx->mutex);
		           }
		           if(ctx->fd == -1) return 0; // we were stopped from the main thread
		           close(ctx->fd); ctx->fd = -1;
		           return LIBLOSSLESS_ERR_IO_READ;
	                }
	                bytesinbuffer += n;
		        nblocks -= blockstodecode;
                        continue;
                }

                if(ape_ctx.bps == 8) bytestoskip = (samples - samplestoskip) * ape_ctx.channels;
                else if (ape_ctx.bps == 16) bytestoskip = (samples - samplestoskip) * ape_ctx.channels * 2;
                else if (ape_ctx.bps == 24) bytestoskip = (samples - samplestoskip) * ape_ctx.channels * 3;

//__android_log_print(ANDROID_LOG_INFO,"liblossless", "samplestoskip %d, samples %d, bytestoskip %d sz %d\n", 
//		samplestoskip, samples, bytestoskip,n);
                
                samplestoskip = 0;
                memmove(ctx->wavbuf, ctx->wavbuf + bytestoskip, n - bytestoskip);
                p = ctx->wavbuf + (n - bytestoskip);
            }

	    n = p - ctx->wavbuf;

	if(n >= ctx->conf_size) {
	    if(prev_written && ctx->mode != MODE_CALLBACK) {	
		    gettimeofday(&tstop,0);
		    tminwrite = ((uint64_t)((uint64_t)prev_written)*1000000)/
			((uint64_t)(ape_ctx.samplerate*ape_ctx.channels*(ape_ctx.bps/8)));
	            timersub(&tstop,&tstart,&ttmp);
	            if(tminwrite > ttmp.tv_usec){
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
	    prev_written = 0;	
	    p = ctx->wavbuf;
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
			//sched_yield();
			n -= ctx->conf_size;
			p += ctx->conf_size;
			prev_written += ctx->conf_size;	
			ctx->written += i;
		    } while(n >= ctx->conf_size);
	    memmove(ctx->wavbuf,p,n);
	}

	    bytes_to_write = n;

            /* Update the buffer */
            memmove(inbuffer,inbuffer + bytesconsumed, bytesinbuffer - bytesconsumed);
            bytesinbuffer -= bytesconsumed;

            n = read(ctx->fd, inbuffer + bytesinbuffer, INPUT_CHUNKSIZE - bytesinbuffer);

       	    if(n < 0) {
		if(ctx->state != MSM_STOPPED) {
                    if(ctx->state != MSM_PAUSED) pthread_mutex_lock(&ctx->mutex);
                    ctx->state = MSM_STOPPED;
                    pthread_mutex_unlock(&ctx->mutex);
		}
                if(ctx->fd == -1) return 0; // we were stopped from the main thread
                close(ctx->fd); ctx->fd = -1;
                return LIBLOSSLESS_ERR_IO_READ;
	    }
            bytesinbuffer += n;

            /* Decrement the block count */
            nblocks -= blockstodecode;
        }
        currentframe++;
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

JNIEXPORT jint JNICALL Java_com_skvalex_amplayer_apeDuration(JNIEnv *env, jobject obj, msm_ctx* ctx, jstring jfile) {

    int currentframe, nblocks, bytesconsumed;
    int bytesinbuffer, blockstodecode, firstbyte;
    int i = 0, n, bytes_to_write;

    int16_t  sample16;
    int32_t  sample32;

    const char *file = (*env)->GetStringUTFChars(env,jfile,NULL);

    unsigned char inbuffer[INPUT_CHUNKSIZE];
    int32_t decoded0[BLOCKS_PER_LOOP];
    int32_t decoded1[BLOCKS_PER_LOOP];

    unsigned char *p;

    struct timeval tstart, tstop, ttmp; // tstart -> time of the last write.
    useconds_t  tminwrite;

    struct ape_ctx_t ape_ctx;
    int prev_written = 0;

    uint32_t samplestoskip;

#ifdef DBG_TIME
        uint64_t total_tminwrite = 0, total_ttmp = 0, total_sleep = 0;
        int writes = 0, fails = 0;
#endif

	ctx->fd = open(file,O_RDONLY);
	(*env)->ReleaseStringUTFChars(env,jfile,file);

	if(ctx->fd < 0) return -1;

    /* Read the file headers to populate the ape_ctx struct */

	if(read(ctx->fd, ctx->wavbuf, INPUT_CHUNKSIZE) != INPUT_CHUNKSIZE) return -1;

	if(ape_parseheaderbuf(ctx->wavbuf,&ape_ctx) < 0) return -1;

	if ((ape_ctx.fileversion < APE_MIN_VERSION) || (ape_ctx.fileversion > APE_MAX_VERSION)) {
	        close(ctx->fd);
	        return -1;
	}

	   uint32_t filepos, newframe, start_sample;

	        ape_ctx.seektable = (uint32_t *) malloc(ape_ctx.seektablelength);
	        if(!ape_ctx.seektable) {
        	        close(ctx->fd);
	                return -1;
	        }
        	if(lseek(ctx->fd, ape_ctx.seektablefilepos, SEEK_SET) < 0) {
                	free(ape_ctx.seektable);
	                close(ctx->fd);
        	        return -1;
	        }
        	if(read(ctx->fd, ape_ctx.seektable, ape_ctx.seektablelength) != ape_ctx.seektablelength) {
                	free(ape_ctx.seektable);
	                close(ctx->fd);
        	        return -1;
	        }
        close(ctx->fd);
        return ape_ctx.totalsamples/ape_ctx.samplerate;
}
