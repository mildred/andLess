#ifndef _LAUDIO_H_INCLUDED
#define _LAUDIO_H_INCLUDED

#ifdef __cplusplus
extern "C" {
#endif

#ifdef FROM_ATRACK_CODE
int  libmedia_start(msm_ctx *ctx, int channels, int samplerate);
void libmedia_stop(msm_ctx *ctx);
void libmedia_pause(msm_ctx *ctx);
void libmedia_resume(msm_ctx *ctx);
ssize_t libmedia_write(msm_ctx *ctx, const void *buf, size_t count);
int  libmediacb_start(msm_ctx *ctx, int channels, int samplerate);
void libmediacb_stop(msm_ctx *ctx);
ssize_t libmediacb_write(msm_ctx *ctx, const void *buf, size_t count);
void libmediacb_wait_done(msm_ctx *ctx);
#else
int  (*libmedia_start)(msm_ctx *ctx, int channels, int samplerate) __attribute__((weak));
void (*libmedia_stop)(msm_ctx *ctx) __attribute__((weak));
void (*libmedia_pause)(msm_ctx *ctx) __attribute__((weak));
void (*libmedia_resume)(msm_ctx *ctx) __attribute__((weak));
ssize_t (*libmedia_write)(msm_ctx *ctx, const void *buf, size_t count) __attribute__((weak));
int  (*libmediacb_start)(msm_ctx *ctx, int channels, int samplerate) __attribute__((weak));
void (*libmediacb_stop)(msm_ctx *ctx) __attribute__((weak));
ssize_t (*libmediacb_write)(msm_ctx *ctx, const void *buf, size_t count) __attribute__((weak));
void (*libmediacb_wait_done)(msm_ctx *ctx) __attribute__((weak));
#endif

#ifdef __cplusplus
}
#endif

#endif
