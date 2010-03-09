
#include <jni.h>
#include <utils/String8.h>
#include <android/log.h>
#include <media/AudioTrack.h>
#include "main.h"
#include "msm_audio.h"
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
#if 0
   if(ctx->track) delete (AudioTrack *) ctx->track;
#else
   if(ctx->track) {
//	((AudioTrack *)ctx->track)->start();
	((AudioTrack *)ctx->track)->start();
	return 0; 
   }	
#endif
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

#if 0
extern "C" JNIEXPORT void  JNICALL Java_net_avs234_AndLessSrv_audioTest(JNIEnv *env, jobject obj) {

 unsigned char buff[9600];
		if(!atrack) {
			LOGW("CANNOT create track!");
			return;
		} else LOGW("Track CREATED!");
		if(status == NO_ERROR) {
			LOGW("Track set success!@!");
			atrack->start();
			ssize_t result = atrack->write(buff,sizeof(buff));
			LOGW("Wrote %ld bytes!", result);
			atrack->pause();
			atrack->start();
			atrack->stop();
			atrack->flush();
		} else LOGW("BAAD status!");				
		LOGW("Deleting track ...");
		delete atrack;
		LOGW("Track deleted!");

}
#endif

 }
};




