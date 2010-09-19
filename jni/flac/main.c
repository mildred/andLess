/***************************************************************************
 *             __________               __   ___.
 *   Open      \______   \ ____   ____ |  | _\_ |__   _______  ___
 *   Source     |       _//  _ \_/ ___\|  |/ /| __ \ /  _ \  \/  /
 *   Jukebox    |    |   (  <_> )  \___|    < | \_\ (  <_> > <  <
 *   Firmware   |____|_  /\____/ \___  >__|_ \|___  /\____/__/\_ \
 *                     \/            \/     \/    \/            \/
 * $Id: main.c 20875 2009-05-08 17:52:38Z dave $
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



#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <inttypes.h>
#include <stdbool.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/select.h>
#include <pthread.h>
#include <sched.h>
#include "../main.h"
#include "decoder.h"
#include <android/log.h>

#ifdef SWAP32
#undef SWAP32
#endif

#define SWAP32(A,B) (((A)[(B)+0]) << 24) | (((A)[(B)+1]) << 16) | (((A)[(B)+2]) << 8) | (((A)[(B)+3]) << 0) 

#if 0
static void dump_headers(FLACContext *s)
{
    fprintf(stderr,"  Blocksize: %d .. %d\n", s->min_blocksize, 
                   s->max_blocksize);
    fprintf(stderr,"  Framesize: %d .. %d\n", s->min_framesize, 
                   s->max_framesize);
    fprintf(stderr,"  Samplerate: %d\n", s->samplerate);
    fprintf(stderr,"  Channels: %d\n", s->channels);
    fprintf(stderr,"  Bits per sample: %d\n", s->bps);
    fprintf(stderr,"  Metadata length: %d\n", s->metadatalength);
    fprintf(stderr,"  Total Samples: %lu\n",s->totalsamples);
    fprintf(stderr,"  Duration: %d ms\n",s->length);
    fprintf(stderr,"  Bitrate: %d kbps\n",s->bitrate);
}
#endif

typedef struct {
    uint32_t sample;
    uint32_t offset;
} flac_seek_t;

static uint32_t time2sample(int time, FLACContext *fc) {
   return ((uint32_t) time) * fc->samplerate;	
}



static bool flac_init(int fd, FLACContext* fc, int start, flac_seek_t *lo, flac_seek_t *hi)
{
    unsigned char buf[255];
    struct stat statbuf;
    bool found_streaminfo=false;
    int endofmetadata=0;
    uint32_t blocklength;
    uint32_t seekpoint_lo,seekpoint_hi;
    uint32_t offset_lo,offset_hi;
    int n;
    bool seeks_found;
    uint32_t target_sample;


    if (lseek(fd, 0, SEEK_SET) < 0) 
    {
        return false;
    }

    if (read(fd, buf, 4) < 4)
    {
    	 return false;
    }

    if (memcmp(buf,"fLaC",4) != 0) 
    {
   
	if(memcmp(buf, "ID3",3) !=0) return false;
	if (read(fd, buf, 6) < 6)
    	{
         return false;
    	}
	target_sample = buf[2] << 21;
	target_sample += buf[3] << 14;
	target_sample += buf[4] << 7;
	target_sample += buf[5];
	target_sample += 10;
	if (lseek(fd, target_sample, SEEK_SET) < 0)
	{
          return false;
	}
	
	if (read(fd, buf, 4) < 4)
	{
         return false;
	}

	if (memcmp(buf,"fLaC",4) != 0) return false;
    }
    target_sample = 0;

    fc->metadatalength = 4;

    seeks_found = start ? false : true;
    lo->sample = 0; lo->offset = 0;
    hi->sample = 0; hi->offset = 0;

    while (!endofmetadata) {
        if (read(fd, buf, 4) < 4)
        {
            return false;
        }

        endofmetadata=(buf[0]&0x80);
        blocklength = (buf[1] << 16) | (buf[2] << 8) | buf[3];
        fc->metadatalength+=blocklength+4;

        if ((buf[0] & 0x7f) == 0)       /* 0 is the STREAMINFO block */
        {
            /* FIXME: Don't trust the value of blocklength */
            if (read(fd, buf, blocklength) < 0)
            {
                return false;
            }
          
            fstat(fd,&statbuf);
            fc->filesize = statbuf.st_size;
            fc->min_blocksize = (buf[0] << 8) | buf[1];
            fc->max_blocksize = (buf[2] << 8) | buf[3];
            fc->min_framesize = (buf[4] << 16) | (buf[5] << 8) | buf[6];
            fc->max_framesize = (buf[7] << 16) | (buf[8] << 8) | buf[9];
            fc->samplerate = (buf[10] << 12) | (buf[11] << 4) 
                             | ((buf[12] & 0xf0) >> 4);
            fc->channels = ((buf[12]&0x0e)>>1) + 1;
            fc->bps = (((buf[12]&0x01) << 4) | ((buf[13]&0xf0)>>4) ) + 1;

            /* totalsamples is a 36-bit field, but we assume <= 32 bits are 
               used */
            fc->totalsamples = (buf[14] << 24) | (buf[15] << 16) 
                               | (buf[16] << 8) | buf[17];

            /* Calculate track length (in ms) and estimate the bitrate 
               (in kbit/s) */
            fc->length = ((int64_t) fc->totalsamples * 1000) / fc->samplerate;

	    if(start) target_sample = time2sample(start,fc);		

	// NB: streaminfo must be the first metadata block.

            found_streaminfo=true;
        } else if ((buf[0] & 0x7f) == 3) { /* 3 is the SEEKTABLE block */
            while (blocklength >= 18) {
                n=read(fd,buf,18);
                if (n < 18) return false;
                blocklength-=n;
		if(!seeks_found) {
#if 0		     	
	              p=(uint32_t*)buf;
	              seekpoint_hi=betoh322(*(p++));
	              seekpoint_lo=betoh322(*(p++));
	              offset_hi=betoh322(*(p++));
	              offset_lo=betoh322(*(p++));
#else
	              seekpoint_hi=SWAP32(buf,0);
	              seekpoint_lo=SWAP32(buf,4);
	              offset_hi=SWAP32(buf,8);
	              offset_lo=SWAP32(buf,12);
#endif
	              if ((seekpoint_hi == 0) && (seekpoint_lo != 0xffffffff) && (offset_hi == 0)) {
			  if(seekpoint_lo > target_sample) {
				hi->sample = seekpoint_lo;
				hi->offset = offset_lo;
				seeks_found = true;			
//				 __android_log_print(ANDROID_LOG_INFO,"liblossless","initial seek succeeded for sample %d %d@%d %d@%d", 
//					target_sample, lo->sample,lo->offset,hi->sample,hi->offset);

			  } else {
				lo->sample = seekpoint_lo;
				lo->offset = offset_lo;
			  }		
                      }
            	}
            }
            lseek(fd, blocklength, SEEK_CUR);
        } else {
            /* Skip to next metadata block */
            if (lseek(fd, blocklength, SEEK_CUR) < 0)
            {
                return false;
            }
        }
    }

   if (found_streaminfo) {
       fc->bitrate = ((int64_t) (fc->filesize-fc->metadatalength) * 8) / fc->length;
       return true;
   } else {
       return false;
   }
}

/* Dummy function needed to pass to flac_decode_frame() */
static void yield() {
//  sched_yield();
}


static bool frame_sync(FLACContext* fc, msm_ctx *ctx, int32_t *decoded0, int32_t *decoded1) {
    unsigned int x = 0;
    bool cached = false;
    size_t buff_size;
    off_t pos;
    /* Make sure we're byte aligned. */
    align_get_bits(&fc->gb);

    while(1) {
        if(fc->gb.size_in_bits - get_bits_count(&fc->gb) < 8) {
            /* Error, end of bitstream, a valid stream should never reach here
             * since the buffer should contain at least one frame header.
             */
//__android_log_print(ANDROID_LOG_INFO,"liblossless","sync error 1");
            return false;
        }

        if(cached)
            cached = false;
        else
            x = get_bits(&fc->gb, 8);

        if(x == 0xff) { /* MAGIC NUMBER for first 8 frame sync bits. */
            x = get_bits(&fc->gb, 8);
            /* We have to check if we just read two 0xff's in a row; the second
             * may actually be the beginning of the sync code.
             */
            if(x == 0xff) { /* MAGIC NUMBER for first 8 frame sync bits. */
                cached = true;
            }
            else if(x >> 2 == 0x3e) { /* MAGIC NUMBER for last 6 sync bits. */
                /* Succesfully synced. */
//__android_log_print(ANDROID_LOG_INFO,"liblossless","synced successfully!!!!");
                break;
            }
        }
    }

    /* Advance and init bit buffer to the new frame. */

  
//    ci->advance_buffer((get_bits_count(&fc->gb)-16)>>3); /* consumed bytes */
//    bit_buffer = ci->request_buffer(&buff_size, MAX_FRAMESIZE+16);
//    init_get_bits(&fc->gb, ctx->wavbuf, buff_size*8);
	
    pos = (get_bits_count(&fc->gb)-16)>>3; 
	
    if(lseek(ctx->fd, pos, SEEK_CUR) < 0) {
//__android_log_print(ANDROID_LOG_INFO,"liblossless","sync error 2");
	return false;
    }	
    buff_size = read(ctx->fd,ctx->wavbuf,MAX_FRAMESIZE+16);

    lseek(ctx->fd, -buff_size, SEEK_CUR);	

    if(buff_size < 0) return false;
    init_get_bits(&fc->gb, ctx->wavbuf, buff_size*8);

    /* Decode the frame to verify the frame crc and
     * fill fc with its metadata.
     */
    if(flac_decode_frame(fc, decoded0, decoded1, ctx->wavbuf, buff_size, yield) < 0) {
//__android_log_print(ANDROID_LOG_INFO,"liblossless","sync error 3");
        return false;
    }
    return true;
}


/* Seek to sample - adapted from libFLAC 1.1.3b2+ */
static bool flac_seek(FLACContext* fc, msm_ctx *ctx, 
		uint32_t target_sample, 
		flac_seek_t *lo, flac_seek_t *hi,
		int32_t *decoded0, int32_t *decoded1) {

    off_t orig_pos = lseek(ctx->fd,0,SEEK_CUR);
    off_t pos = -1;
    unsigned long lower_bound, upper_bound;
    unsigned long lower_bound_sample, upper_bound_sample;
    unsigned approx_bytes_per_frame;
    uint32_t this_frame_sample = fc->samplenumber;
    unsigned this_block_size = fc->blocksize;
    bool needs_seek = true, first_seek = true;
    size_t buff_size;


    /* We are just guessing here. */
    if(fc->max_framesize > 0)
        approx_bytes_per_frame = (fc->max_framesize + fc->min_framesize)/2 + 1;
    /* Check if it's a known fixed-blocksize stream. */
    else if(fc->min_blocksize == fc->max_blocksize && fc->min_blocksize > 0)
        approx_bytes_per_frame = fc->min_blocksize*fc->channels*fc->bps/8 + 64;
    else
        approx_bytes_per_frame = 4608 * fc->channels * fc->bps/8 + 64;

    /* Set an upper and lower bound on where in the stream we will search. */
    lower_bound = fc->metadatalength;
    lower_bound_sample = 0;
    upper_bound = fc->filesize;
    upper_bound_sample = fc->totalsamples>0 ? fc->totalsamples : target_sample;

    if(lo->sample < hi->sample && lo->offset <= hi->offset) {
	lower_bound = fc->metadatalength + lo->offset;
	lower_bound_sample = lo->sample;
	upper_bound = fc->metadatalength + hi->offset;
	upper_bound_sample = hi->sample;
    }	

   while(1) {

        /* Check if bounds are still ok. */
        if(lower_bound_sample >= upper_bound_sample || lower_bound > upper_bound) {
//__android_log_print(ANDROID_LOG_INFO,"liblossless","seek error 1");
		 return false;
	}
  
        /* Calculate new seek position */
        if(needs_seek) {
            pos = (off_t)(lower_bound +  (((target_sample - lower_bound_sample) *
              (int64_t)(upper_bound - lower_bound)) / (upper_bound_sample - lower_bound_sample)) - approx_bytes_per_frame);

            if(pos >= (off_t)upper_bound) pos = (off_t)upper_bound-1;
            if(pos < (off_t)lower_bound)  pos = (off_t)lower_bound;
        }
	
	if(lseek(ctx->fd,pos,SEEK_SET) < 0) return false;

//        bit_buffer = ci->request_buffer(&buff_size, MAX_FRAMESIZE+16);
//        init_get_bits(&fc->gb, bit_buffer, buff_size*8);
        
	buff_size = read(ctx->fd,ctx->wavbuf,MAX_FRAMESIZE+16);
	if(buff_size < 0)  return false;
	init_get_bits(&fc->gb, ctx->wavbuf, buff_size*8);

	if(lseek(ctx->fd,pos,SEEK_SET) < 0) return false;

        /* Now we need to get a frame.  It is possible for our seek
         * to land in the middle of audio data that looks exactly like
         * a frame header from a future version of an encoder.  When
         * that happens, frame_sync() will return false.
         * But there is a remote possibility that it is properly
         * synced at such a "future-codec frame", so to make sure,
         * we wait to see several "unparseable" errors in a row before
         * bailing out.
         */
        {
            unsigned unparseable_count;
            bool got_a_frame = false;
            for(unparseable_count = 0; !got_a_frame  && unparseable_count < 10; unparseable_count++) {
                if(frame_sync(fc,ctx,decoded0,decoded1))  got_a_frame = true;
            }
            if(!got_a_frame) {
		lseek(ctx->fd,orig_pos,SEEK_SET);
//__android_log_print(ANDROID_LOG_INFO,"liblossless","seek error 3");
                return false;
            }
        }
   
        this_frame_sample = fc->samplenumber;
        this_block_size = fc->blocksize;

        if(target_sample >= this_frame_sample
           && target_sample < this_frame_sample+this_block_size) {
            /* Found the frame containing the target sample. */
            fc->sample_skip = target_sample - this_frame_sample;
            break;
        }

        if(this_frame_sample + this_block_size >= upper_bound_sample &&
           !first_seek) {
            if(pos == (off_t)lower_bound || !needs_seek) {
		lseek(ctx->fd,orig_pos,SEEK_SET);
//__android_log_print(ANDROID_LOG_INFO,"liblossless","seek error 4 %ld %ld %d",pos,lower_bound,needs_seek);
                return false;
            }
            /* Our last move backwards wasn't big enough, try again. */
            approx_bytes_per_frame *= 2;
            continue;
        }
        /* Allow one seek over upper bound,
         * required for streams with unknown total samples.
         */
        first_seek = false;

        /* Make sure we are not seeking in a corrupted stream */
        if(this_frame_sample < lower_bound_sample) {
   	    lseek(ctx->fd,orig_pos,SEEK_SET);
//__android_log_print(ANDROID_LOG_INFO,"liblossless","seek error 5");
            return false;
        }

        approx_bytes_per_frame = this_block_size*fc->channels*fc->bps/8 + 64;

        /* We need to narrow the search. */
        if(target_sample < this_frame_sample) {
            upper_bound_sample = this_frame_sample;
            upper_bound = (unsigned long) lseek(ctx->fd,0,SEEK_CUR);
        }
        else { /* Target is beyond this frame. */
            /* We are close, continue in decoding next frames. */
            if(target_sample < this_frame_sample + 4*this_block_size) {
                pos = fc->framesize + (unsigned long) lseek(ctx->fd,0,SEEK_CUR);
                needs_seek = false;
            }

            lower_bound_sample = this_frame_sample + this_block_size;
            lower_bound = fc->framesize + (unsigned long) lseek(ctx->fd,0,SEEK_CUR) ;
        }
    }

    return true;
}


static int *flac_read_cue(int fd)
{
    unsigned char buf[255];
    int endofmetadata = 0;
    uint32_t blocklength, *times, samplerate = 0, off_lo, off_hi;
    uint8_t *p, i, k, j = 0, n, *cue_buf = 0;
    uint64_t k1, k2;

    if (lseek(fd, 0, SEEK_SET) < 0) return 0;
    if (read(fd, buf, 4) < 4) return 0;
    if (memcmp(buf,"fLaC",4) != 0) {

        if(memcmp(buf, "ID3",3) !=0) return 0;
        if (read(fd, buf, 6) < 6) return 0;
        blocklength = buf[2] << 21;
        blocklength += buf[3] << 14;
        blocklength += buf[4] << 7;
        blocklength += buf[5];
        blocklength += 10;
        if (lseek(fd, blocklength, SEEK_SET) < 0) return 0;
        if (read(fd, buf, 4) < 4) return 0;
        if (memcmp(buf,"fLaC",4) != 0) return 0;
    }
    while (!endofmetadata) {
        if (read(fd, buf, 4) < 4) return 0;
        endofmetadata=(buf[0]&0x80);
        blocklength = (buf[1] << 16) | (buf[2] << 8) | buf[3];

        if ((buf[0] & 0x7f) == 0)  { /* STREAMINFO */
            if (read(fd, buf, blocklength) < 0) return 0;
            samplerate = (buf[10] << 12) | (buf[11] << 4) | ((buf[12] & 0xf0) >> 4);
	} else if ((buf[0] & 0x7f) == 5) { /* BLOCK_CUESHEET */
	    if(!samplerate) return 0;	// STREAMINFO must be present as the first metadata block 
	    cue_buf = (unsigned char *)	malloc(blocklength);
	    if(!cue_buf) return 0;	
            if(read(fd, cue_buf, blocklength) != blocklength) return 0;
#define SIZEOF_METADATA_BLOCK_CUESHEET	 (128+8+1+258+1)
#define SIZEOF_CUESHEET_TRACK		 (8+1+12+1+13+1)
#define SIZEOF_CUESHEET_TRACK_INDEX	 (8+1+3)
	    p = cue_buf + SIZEOF_METADATA_BLOCK_CUESHEET; // now pointing to CUESHEET_TRACK
	    n = p[-1];
	    times = (uint32_t *) malloc((n+1) * sizeof(uint32_t));	
	    if(!times) {
		free(cue_buf); return 0;
	    }		
//	__android_log_print(ANDROID_LOG_ERROR,"liblossless","Found CUE block, %d tracks", n);
	    memset(times,0,(n+1)*sizeof(uint32_t));	
	    for(i = 0, j = 0; i < n && j < n; i++) {
		// CUESHEET_TRACK
	      uint8_t idx_points, track_no = p[8];
		off_hi = SWAP32(p,0);
		off_lo = SWAP32(p,4);
		k1 = (((uint64_t) off_hi) << 32) |((uint64_t) off_lo);				    	
		p += SIZEOF_CUESHEET_TRACK; // now pointing to the first CUE_TRACK_INDEX
		idx_points = p[-1];
		if(track_no >= 0 && track_no <= 99) {
		   uint8_t *p1 = p;
		    for(k = 0; k < idx_points; k++) {	
			if(p1[8] == 1) {	// Save INDEX 01 records only!
	                    off_hi = SWAP32(p1,0);
        	            off_lo = SWAP32(p1,4);
                	    k2 = (((uint64_t) off_hi) << 32) |((uint64_t) off_lo);
			    times[++j] = (uint32_t)((k2+k1)/samplerate);
//	__android_log_print(ANDROID_LOG_ERROR,"liblossless","Found CUE index 01 at %d", times[j-1]);
			    break; 		
			}
			p1 += SIZEOF_CUESHEET_TRACK_INDEX;
		    }	
		} 
		p += idx_points*SIZEOF_CUESHEET_TRACK_INDEX;  
	    }				
	    free(cue_buf);
	    if(j == 0) {
		free(times); return 0;
	    }	
	    times[0] = (uint32_t) j;
//	__android_log_print(ANDROID_LOG_ERROR,"liblossless","Returning array, len=%d", j);
	    return (int *) times;	
        } else {
            /* Skip to next metadata block */
            if (lseek(fd, blocklength, SEEK_CUR) < 0)
            {
                return 0;
            }
        }
    }
    return 0;
}


JNIEXPORT jintArray JNICALL extract_flac_cue(JNIEnv *env, jobject obj, jstring jfile) {

  const char *file = (*env)->GetStringUTFChars(env,jfile,NULL);
  int fd;	
  int *k = 0, n;
  jintArray ja = 0;
    if(!file) {
        (*env)->ReleaseStringUTFChars(env,jfile,file);  return 0;
    }
    fd = open(file,O_RDONLY);
    (*env)->ReleaseStringUTFChars(env,jfile,file);
    if(fd < 0 || (k = flac_read_cue(fd)) == 0 || (n = k[0]) == 0) {
	if(k) free(k);
	close(fd);
	return 0;
    }	
    close(fd);
    ja = (*env)->NewIntArray(env,n);
    (*env)->SetIntArrayRegion(env,ja,0,n,(jint *)(k+1));    
    free(k);	
    return ja;
}


JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_flacPlay(JNIEnv *env, jobject obj, msm_ctx* ctx, jstring jfile, jint start) {

    const char *file = (*env)->GetStringUTFChars(env,jfile,NULL);
    int i = 0, n, scale;
    int bytesleft = 0, bytes_to_write = 0, consumed = 0;
    unsigned char buf[MAX_FRAMESIZE];
    int32_t decoded0[MAX_BLOCKSIZE];
    int32_t decoded1[MAX_BLOCKSIZE];
    unsigned char *p;	
    FLACContext fc[1];
    struct timeval tstart, tstop, ttmp; // tstart -> time of the last write.
    useconds_t  tminwrite;
    int prev_written = 0;
    flac_seek_t seek_lo, seek_hi;

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


//  __android_log_print(ANDROID_LOG_INFO,"liblossless","calling flac_init()");

	if(!flac_init(ctx->fd,fc,start,&seek_lo,&seek_hi)) return LIBLOSSLESS_ERR_FORMAT;
  
//  __android_log_print(ANDROID_LOG_INFO,"liblossless","flac_init() exited, calling flac_seek()");

	if(start) {
	   if(!flac_seek(fc,ctx,time2sample(start,fc),&seek_lo,&seek_hi,decoded0,decoded1)) {
	   	if(!flac_seek(fc,ctx,time2sample(start+1,fc),&seek_lo,&seek_hi,decoded0,decoded1)) return LIBLOSSLESS_ERR_OFFSET;
	   } 		
	}

//  __android_log_print(ANDROID_LOG_INFO,"liblossless","flac_seek() exited, starting playback");
		
	i = audio_start(ctx, fc->channels, fc->samplerate);
	if(i != 0) {
	        close(ctx->fd);
	        return i;
	}

        ctx->channels = fc->channels;
        ctx->samplerate = fc->samplerate;
        ctx->bps = fc->bps;
        ctx->written = 0;

	pthread_mutex_lock(&ctx->mutex);
	ctx->state = MSM_PLAYING;
	ctx->track_time = fc->totalsamples / fc->samplerate;
	pthread_mutex_unlock(&ctx->mutex);

	update_track_time(env,obj,ctx->track_time); 
 

	bytesleft = read(ctx->fd,buf,sizeof(buf));
//        gettimeofday(&tstart,0);
   	
    while (bytesleft && (ctx->state != MSM_STOPPED)) 
    { 
	if(flac_decode_frame(fc,decoded0,decoded1,buf,bytesleft,yield) < 0) {
               if(ctx->state != MSM_STOPPED) {
                    if(ctx->state != MSM_PAUSED) pthread_mutex_lock(&ctx->mutex);
                    ctx->state = MSM_STOPPED;
                    pthread_mutex_unlock(&ctx->mutex);
                }
                if(ctx->fd == -1) return 0; // we were stopped from the main thread
		if(ctx->written/(ctx->channels * ctx->samplerate * (ctx->bps/8))+2 > ctx->track_time) break;
                close(ctx->fd); ctx->fd = -1;
		return LIBLOSSLESS_ERR_DECODE;
	}
	consumed = fc->gb.index/8;
        scale = FLAC_OUTPUT_DEPTH - fc->bps;
        p = ctx->wavbuf + bytes_to_write;

        for (i=0; i < fc->blocksize; i++) {
             /* Left sample */
             decoded0[i] = decoded0[i]>>scale;
             *(p++) = decoded0[i]&0xff;
             *(p++) = (decoded0[i]&0xff00)>>8;
             if (fc->bps == 24) *(p++)=(decoded0[i]&0xff0000)>>16;

             if (fc->channels == 2) {
                 /* Right sample */
                 decoded1[i]=decoded1[i]>>scale;
                 *(p++)=decoded1[i]&0xff;
                 *(p++)=(decoded1[i]&0xff00)>>8;
                 if (fc->bps==24) *(p++)=(decoded1[i]&0xff0000)>>16;
             }
        }

        n = fc->blocksize * fc->channels * (fc->bps/8);

	if(n + bytes_to_write >= ctx->conf_size) {
	    p = ctx->wavbuf; n += bytes_to_write;	

	    if(prev_written && ctx->mode != MODE_CALLBACK) {	
		tminwrite = ((uint64_t)((uint64_t)(prev_written))*1000000)/((uint64_t)(fc->samplerate*fc->channels*(fc->bps/8)));
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

        memmove(buf,&buf[consumed],bytesleft-consumed);
        bytesleft -= consumed;

        n = read(ctx->fd,&buf[bytesleft],sizeof(buf)-bytesleft);
        if (n > 0) bytesleft+=n;
	else if(n < 0) {
		if(ctx->state != MSM_STOPPED) {
		    if(ctx->state != MSM_PAUSED) pthread_mutex_lock(&ctx->mutex);
		    ctx->state = MSM_STOPPED;
		    pthread_mutex_unlock(&ctx->mutex);
		}
		if(ctx->fd == -1) return 0; // we were stopped from the main thread	
		close(ctx->fd); ctx->fd = -1;
	        return LIBLOSSLESS_ERR_IO_READ;
	}
	
//	if(ctx->state != MSM_STOPPED) sched_yield();
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
