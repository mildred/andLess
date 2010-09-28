
#include <jni.h>
#include <pthread.h>

#ifndef _MAIN_H_INCLUDED
#define _MAIN_H_INCLUDED

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
   enum _msm_state_t {
        MSM_STOPPED = 0,
        MSM_PLAYING = 1,
        MSM_PAUSED = 2
   } state;
   enum _msm_mode_t {
	MODE_NONE = 0,
	MODE_DIRECT = 1,	
	MODE_LIBMEDIA = 2,
	MODE_CALLBACK = 3,
	MODE_JAVA = 4
   } mode; 	 	
   int afd, fd, conf_size, cbbuf_size;
   unsigned char *wavbuf, *cbbuf;
   void *track; 	
   int  track_time;	
   int  channels, samplerate, bps, written;
   int  cbstart, cbend;	
   pthread_mutex_t mutex, cbmutex;
   pthread_cond_t  cbcond;
} msm_ctx;

extern int  audio_start(msm_ctx *ctx, int channels, int samplerate);
extern void audio_stop(msm_ctx *ctx);
extern ssize_t  audio_write(JNIEnv *env, jobject obj, msm_ctx *ctx, const void *buf, size_t count);
extern void update_track_time(JNIEnv *env, jobject obj, int time);

extern JNIEXPORT jint	  JNICALL Java_net_avs234_AndLessSrv_audioInit(JNIEnv *env, jobject obj, msm_ctx *prev_ctx, jint mode);
extern JNIEXPORT jboolean JNICALL Java_net_avs234_AndLessSrv_audioExit(JNIEnv *env, jobject obj, msm_ctx *ctx);
extern JNIEXPORT jboolean JNICALL Java_net_avs234_AndLessSrv_audioPause(JNIEnv *env, jobject obj, msm_ctx *ctx);
extern JNIEXPORT jboolean JNICALL Java_net_avs234_AndLessSrv_audioResume(JNIEnv *env, jobject obj, msm_ctx *ctx);
extern JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_audioGetDuration(JNIEnv *env, jobject obj, msm_ctx *ctx);
extern JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_audioGetCurPosition(JNIEnv *env, jobject obj, msm_ctx *ctx);
extern JNIEXPORT jboolean JNICALL Java_net_avs234_AndLessSrv_audioSetVolume(JNIEnv *env, jobject obj, msm_ctx *ctx, jint vol);
extern JNIEXPORT jboolean JNICALL Java_net_avs234_AndLessSrv_audioStop(JNIEnv *env, jobject obj, msm_ctx *ctx);

extern JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_wavPlay(JNIEnv *env, jobject obj, msm_ctx* ctx, jstring jfile, jint start);
extern JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_alacPlay(JNIEnv *env, jobject obj, msm_ctx* ctx, jstring jfile, jint start);
extern JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_flacPlay(JNIEnv *env, jobject obj, msm_ctx* ctx, jstring jfile, jint start);
extern JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_apePlay(JNIEnv *env, jobject obj, msm_ctx* ctx, jstring jfile, jint start);
extern JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_wvPlay(JNIEnv *env, jobject obj, msm_ctx* ctx, jstring jfile, jint start);
extern JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_mpcPlay(JNIEnv *env, jobject obj, msm_ctx* ctx, jstring jfile, jint start);

extern JNIEXPORT jintArray JNICALL extract_flac_cue(JNIEnv *env, jobject obj, jstring jfile);

extern JNIEXPORT jint JNICALL Java_com_skvalex_amplayer_wvDuration(JNIEnv *env, jobject obj, msm_ctx* ctx, jstring jfile);
extern JNIEXPORT jint JNICALL Java_com_skvalex_amplayer_apeDuration(JNIEnv *env, jobject obj, msm_ctx* ctx, jstring jfile);


#define DEFAULT_CONF_BUFSZ 		(4800*4*4)
#define DEFAULT_WAV_BUFSZ 		(128*1024)

// For initialization of AudioTrack in MODE_CALLBACK, affects the track latency
#define DEFAULT_ATRACK_CONF_BUFSZ 	DEFAULT_CONF_BUFSZ

// Callback buffer size, must be larger than (but shouldn't be a multiple of) DEFAULT*CONF_BUFSZ
#define DEFAULT_CB_BUFSZ		(8*DEFAULT_CONF_BUFSZ+2)

#define LIBLOSSLESS_ERR_NOCTX		1
#define LIBLOSSLESS_ERR_INV_PARM	2
#define LIBLOSSLESS_ERR_NOFILE		3
#define LIBLOSSLESS_ERR_FORMAT		4
#define LIBLOSSLESS_ERR_AU_GETCONF 	5	
#define LIBLOSSLESS_ERR_AU_SETCONF	6
#define LIBLOSSLESS_ERR_AU_BUFF		7
#define LIBLOSSLESS_ERR_AU_SETUP	8
#define LIBLOSSLESS_ERR_AU_START	9
#define LIBLOSSLESS_ERR_IO_WRITE 	10	
#define LIBLOSSLESS_ERR_IO_READ		11
#define LIBLOSSLESS_ERR_DECODE		12 
#define LIBLOSSLESS_ERR_OFFSET		13
#define LIBLOSSLESS_ERR_NOMEM		14
#define LIBLOSSLESS_ERR_INIT		15

#ifdef __cplusplus
}
#endif



#endif
