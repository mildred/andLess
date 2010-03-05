
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
	MODE_LIBMEDIA = 2
   } mode; 	 	
   int afd, fd, conf_size;
   unsigned char *wavbuf;
   void *track; 	
   int  track_time;	
   int  channels, samplerate, bps, written;
   pthread_mutex_t mutex;
} msm_ctx;

extern int  audio_start(msm_ctx *ctx, int channels, int samplerate);
extern void audio_stop(msm_ctx *ctx);
extern ssize_t  audio_write(msm_ctx *ctx, const void *buf, size_t count);
extern void update_track_time(JNIEnv *env, jobject obj, int time);

extern JNIEXPORT jint	  JNICALL Java_net_avs234_AndLessSrv_audioInit(JNIEnv *env, jobject obj, msm_ctx *prev_ctx, jint mode);
extern JNIEXPORT jboolean JNICALL Java_net_avs234_AndLessSrv_audioExit(JNIEnv *env, jobject obj, msm_ctx *ctx);
extern JNIEXPORT jboolean JNICALL Java_net_avs234_AndLessSrv_audioPause(JNIEnv *env, jobject obj, msm_ctx *ctx);
extern JNIEXPORT jboolean JNICALL Java_net_avs234_AndLessSrv_audioResume(JNIEnv *env, jobject obj, msm_ctx *ctx);
extern JNIEXPORT jint 	  JNICALL Java_net_avs234_AndLessSrv_audioGetDuration(JNIEnv *env, jobject obj, msm_ctx *ctx);
extern JNIEXPORT jint     JNICALL Java_net_avs234_AndLessSrv_audioGetCurPosition(JNIEnv *env, jobject obj, msm_ctx *ctx);
extern JNIEXPORT jboolean JNICALL Java_net_avs234_AndLessSrv_audioSetVolume(JNIEnv *env, jobject obj, msm_ctx *ctx, jint vol);
extern JNIEXPORT jboolean JNICALL Java_net_avs234_AndLessSrv_audioStop(JNIEnv *env, jobject obj, msm_ctx *ctx);

extern JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_wavPlay(JNIEnv *env, jobject obj, msm_ctx* ctx, jstring jfile, jint start);
extern JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_flacPlay(JNIEnv *env, jobject obj, msm_ctx* ctx, jstring jfile, jint start);
extern JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_apePlay(JNIEnv *env, jobject obj, msm_ctx* ctx, jstring jfile, jint start);
extern JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_wvPlay(JNIEnv *env, jobject obj, msm_ctx* ctx, jstring jfile, jint start);
extern JNIEXPORT jint JNICALL Java_net_avs234_AndLessSrv_mpcPlay(JNIEnv *env, jobject obj, msm_ctx* ctx, jstring jfile, jint start);


#define DEFAULT_CONF_BUFSZ 		(4800*4)
#define DEFAULT_WAV_BUFSZ 		(128*1024)

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
