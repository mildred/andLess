
#include <jni.h>
#include <utils/String8.h>
#include <android/log.h>

#ifndef BUILD_GINGER 
#include <media/AudioTrack.h>
#else
#include <media/AudioTrack9.h>
#endif

#include "main.h"
#include "msm_audio.h"
#define FROM_ATRACK_CODE 1
#include "std_audio.h"


namespace android {
extern "C" {
/*
int libmedia_init(msm_ctx *ctx) {
   return 0;	
}

void libmedia_exit(msm_ctx *ctx) {
}
*/

int libmedia_start(msm_ctx *ctx, int channels, int samplerate) {

   if(!ctx) return LIBLOSSLESS_ERR_NOCTX;
  __android_log_print(ANDROID_LOG_INFO,"liblossless","libmedia_start chans=%d rate=%d afd=%d atrack=%p",
                channels, samplerate,ctx->afd,ctx->track);
#if 1 
   if(ctx->track && ctx->samplerate == samplerate && ctx->channels == channels) {
	((AudioTrack *) ctx->track)->stop();
	((AudioTrack *) ctx->track)->flush();
	((AudioTrack *)ctx->track)->start();
	return 0; 
   }	
#endif
   if(ctx->track) {
	((AudioTrack *) ctx->track)->stop();
	((AudioTrack *) ctx->track)->flush();
	delete (AudioTrack *) ctx->track;
   }	
   ctx->track = 0;	
   AudioTrack* atrack = new AudioTrack();

  __android_log_print(ANDROID_LOG_INFO,"liblossless","AudioTrack created at %p. Now trying to setup", atrack);

   if(!atrack) return LIBLOSSLESS_ERR_INIT;
   ctx->track = atrack; 	
   status_t status = atrack->set(AudioSystem::MUSIC, samplerate, 
	 AudioSystem::PCM_16_BIT, channels, DEFAULT_CONF_BUFSZ/(2*channels));
   if(status != NO_ERROR) { 

  __android_log_print(ANDROID_LOG_INFO,"liblossless","AudioTrack->set failed, error code=%d!", status);
  __android_log_print(ANDROID_LOG_INFO,"liblossless","Well... trying new Android AudioSystem interface then");
	int chans = (channels == 2) ? 12 : 4;
	status = atrack->set(AudioSystem::MUSIC, samplerate, AudioSystem::PCM_16_BIT, chans, 
		DEFAULT_CONF_BUFSZ/(2*channels));
	   if(status != NO_ERROR) {
  __android_log_print(ANDROID_LOG_INFO,"liblossless","Does not work still, error code=%d. Bailing out.", status);
		delete atrack; ctx->track = 0;
		return LIBLOSSLESS_ERR_INIT;  
	   }		
  }
  __android_log_print(ANDROID_LOG_INFO,"liblossless","AudioTrack setup OK, starting audio!");
   ctx->conf_size = DEFAULT_CONF_BUFSZ; 	
   atrack->start();	
  __android_log_print(ANDROID_LOG_INFO,"liblossless","playback started!");
   return 0; 
}

void libmedia_stop(msm_ctx *ctx) {
  __android_log_print(ANDROID_LOG_INFO,"liblossless","libmetia_stop called, ctx=%p, track=%p", ctx, 
		ctx ? ctx->track : 0);
  if(ctx && ctx->track) {
#if 0 
	((AudioTrack *) ctx->track)->stop();
  __android_log_print(ANDROID_LOG_INFO,"liblossless","libmetia_stop: audio track stopped!");
	((AudioTrack *) ctx->track)->flush();
  __android_log_print(ANDROID_LOG_INFO,"liblossless","libmetia_stop: audio track flushed!");
//	delete (AudioTrack *) ctx->track;	
  __android_log_print(ANDROID_LOG_INFO,"liblossless","libmetia_stop: audio track deleted!");
	ctx->track = 0;	
#else
        ((AudioTrack *) ctx->track)->pause();	
#endif
	ctx->track_time = 0;
	ctx->state = (msm_ctx::_msm_state_t) 0;
	
   }		
}

void libmedia_pause(msm_ctx *ctx) {
  if(ctx && ctx->track) ((AudioTrack *) ctx->track)->pause();	
}

void libmedia_resume(msm_ctx *ctx) {
  if(ctx && ctx->track) ((AudioTrack *) ctx->track)->start();	
}

ssize_t libmedia_write(msm_ctx *ctx, const void *buf, size_t count) {
  if(ctx && ctx->track) return ((AudioTrack *) ctx->track)->write(buf, count);
  else return -1;
}

////////////////////////////////////
////////// MODE_CALLBACK ///////////

#include <sys/resource.h>
static void print_priority(const char *c) {
 int p = getpriority(0,0);
 __android_log_print(ANDROID_LOG_INFO,"liblossless","%s: priority=%d", c, p);
}

static void cbf(int event, void* user, void *info);

int libmediacb_start(msm_ctx *ctx, int channels, int samplerate) {

   if(!ctx) return LIBLOSSLESS_ERR_NOCTX;
	
  __android_log_print(ANDROID_LOG_INFO,"liblossless","libmediacb_start chans=%d rate=%d afd=%d atrack=%p",
                channels, samplerate,ctx->afd,ctx->track);
#if 0 
   if(ctx->track && ctx->samplerate == samplerate && ctx->channels == channels) {
	((AudioTrack *) ctx->track)->stop();
	((AudioTrack *) ctx->track)->flush();
	ctx->cbstart = 0; ctx->cbend = 0;
	((AudioTrack *) ctx->track)->start();
	return 0; 
   }	
#endif

   if(ctx->track) {
//	((AudioTrack *) ctx->track)->stop();
//	((AudioTrack *) ctx->track)->flush();
	delete (AudioTrack *) ctx->track;
   }	

   ctx->track = 0;	

   if(!ctx->cbbuf) {
	ctx->cbbuf = (unsigned char *) malloc(DEFAULT_CB_BUFSZ);
	if(!ctx->cbbuf) return LIBLOSSLESS_ERR_NOMEM;
	ctx->cbbuf_size = DEFAULT_CB_BUFSZ;
   }		
   ctx->cbstart = 0; ctx->cbend = 0;	

   AudioTrack* atrack = new AudioTrack();

  __android_log_print(ANDROID_LOG_INFO,"liblossless","AudioTrack created at %p. Now trying to setup (buffsz %d)", 
	atrack, DEFAULT_ATRACK_CONF_BUFSZ);

   if(!atrack) return LIBLOSSLESS_ERR_INIT;
   ctx->track = atrack; 	
   status_t status;
   status = atrack->set(AudioSystem::MUSIC, samplerate,AudioSystem::PCM_16_BIT, channels,
			DEFAULT_ATRACK_CONF_BUFSZ/(2*channels),0,cbf,ctx);

   if(status != NO_ERROR) { // for Andriod 2.1 and higher
	int chans = (channels == 2) ? 12 : 4;
	status = atrack->set(AudioSystem::MUSIC, samplerate, AudioSystem::PCM_16_BIT, chans,
			DEFAULT_ATRACK_CONF_BUFSZ/(2*channels),0,cbf,ctx);
	if(status != NO_ERROR) {
		delete atrack; ctx->track = 0;
		return LIBLOSSLESS_ERR_INIT;  
	}		
  }
  __android_log_print(ANDROID_LOG_INFO,"liblossless","AudioTrack setup OK, starting audio!");
   ctx->conf_size = DEFAULT_CONF_BUFSZ; 	
   atrack->start();	
  __android_log_print(ANDROID_LOG_INFO,"liblossless","playback started!");

   atrack->setPositionUpdatePeriod(0);
   atrack->setMarkerPosition(0); 	

   static int s(0); if(!s) { print_priority(__FUNCTION__); s = 1; } 		

   return 0; 
}

void libmediacb_stop(msm_ctx *ctx) {
  __android_log_print(ANDROID_LOG_INFO,"liblossless","libmetia_stop called, ctx=%p, track=%p", ctx, 
		ctx ? ctx->track : 0);
  if(ctx && ctx->track) {
        pthread_mutex_lock(&ctx->cbmutex);
	ctx->cbstart = -1;
	pthread_cond_signal(&ctx->cbcond);
        pthread_mutex_unlock(&ctx->cbmutex);
        ((AudioTrack *) ctx->track)->pause();	
	ctx->track_time = 0;
	ctx->state = (msm_ctx::_msm_state_t) 0;
   }		
}


// free room available in buffer
static inline int get_free_bytes(msm_ctx *ctx) {
   return (ctx->cbend >= ctx->cbstart) ?  ctx->cbbuf_size - (ctx->cbend - ctx->cbstart) : ctx->cbstart - ctx->cbend;
}

void libmediacb_wait_done(msm_ctx *ctx) {
    int k;
	if(!ctx || ctx->cbstart == -1) return;
        pthread_mutex_lock(&ctx->cbmutex);
        k = get_free_bytes(ctx);
	if(k > 0) pthread_cond_wait(&ctx->cbdone,&ctx->cbmutex);
	pthread_mutex_unlock(&ctx->cbmutex);
}

ssize_t libmediacb_write(msm_ctx *ctx, const void *buf, size_t cnt) {

    int k;
    int count = (int)cnt;

	if(!ctx || !ctx->track || count > ctx->cbbuf_size || ctx->cbstart < 0) return -1;	
	
	pthread_mutex_lock(&ctx->cbmutex);
	k = get_free_bytes(ctx); 	
	while(k <= count && ctx->cbstart >=0) { // prohibit k==count to prevent from cbstart==cbend after write
//	    __android_log_print(ANDROID_LOG_INFO,"liblossless","libmediacb_write: decoder is ahead, waiting for callback");
	    pthread_cond_wait(&ctx->cbcond,&ctx->cbmutex);
	    k = get_free_bytes(ctx);
	}
	if(ctx->cbstart < 0) {	// we have been stopped from libmediacb_stop
		pthread_mutex_unlock(&ctx->cbmutex);
		return -1;
	} 	
	k = ctx->cbbuf_size - ctx->cbend;  
	if(k > count) {
	    memcpy(ctx->cbbuf+ctx->cbend,buf,count);		
	    ctx->cbend += count;
	} else if(k < count) {
	    memcpy(ctx->cbbuf+ctx->cbend,buf,k);
	    memcpy(ctx->cbbuf,(unsigned char *)buf+k,count-k);
	    ctx->cbend = count-k;				
	} else {
	    memcpy(ctx->cbbuf+ctx->cbend,buf,count);
	    ctx->cbend = 0;		
	}
	pthread_mutex_unlock(&ctx->cbmutex);


   static int s(0);  if(!s) {  print_priority(__FUNCTION__); s = 1; }


    return count; 	
}



// Callback for AudioTrack. 
// We set no markers and handle AudioTrack::EVENT_MORE_DATA only.

static void cbf(int event, void* user, void *info) {
  if(event != AudioTrack::EVENT_MORE_DATA) {
  	if(event == AudioTrack::EVENT_UNDERRUN) __android_log_print(ANDROID_LOG_ERROR,"liblossless","callback: EVENT_UNDERRUN");
	return;
  } 	
  msm_ctx *ctx = (msm_ctx *) user;
  AudioTrack::Buffer *buff = (AudioTrack::Buffer *) info;
  unsigned char *c = (unsigned char *)	buff->raw;
  unsigned int k;

	if(!buff->size) {
           __android_log_print(ANDROID_LOG_ERROR,"liblossless","callback: audiotrack requested zero bytes");
	    return;	
	}
	pthread_mutex_lock(&ctx->cbmutex);
        if(ctx->cbstart < 0) {  // we have been stopped from libmediacb_stop
                pthread_mutex_unlock(&ctx->cbmutex);
                return;
        }
        k = ctx->cbbuf_size - get_free_bytes(ctx); // k == bytes available for output
	if(k < buff->size) {
           __android_log_print(ANDROID_LOG_INFO,"liblossless",
		"callback: decoder lags, audiotrack requested too much (%d, avail %d)",buff->size, k);
	   buff->size  = k; // update if we write less	
	   if(k == 0) {
		pthread_cond_signal(&ctx->cbdone);
		pthread_mutex_unlock(&ctx->cbmutex);
		return;
	   }	 
	}
	k = ctx->cbbuf_size - ctx->cbstart;

	if(k > buff->size) {
	    memcpy(c,ctx->cbbuf+ctx->cbstart,buff->size);
	    ctx->cbstart += buff->size;	
	} else if(k < buff->size) {
	    memcpy(c,ctx->cbbuf+ctx->cbstart,k);
	    memcpy(c+k,ctx->cbbuf,buff->size-k);		
	    ctx->cbstart = buff->size-k;	
	} else {
	    memcpy(c,ctx->cbbuf+ctx->cbstart,buff->size);
	    ctx->cbstart = 0;			
	}
	pthread_cond_signal(&ctx->cbcond);
	pthread_mutex_unlock(&ctx->cbmutex);

   static int s = 0;
   if(!s) {
	//	setpriority(0,0,-19);   
     print_priority(__FUNCTION__); s = 1;
   }
}


 } // extern "C"
}; // namespace android




