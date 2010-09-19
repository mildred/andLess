#include "main.h"

#ifndef _LAUDIO_H_INCLUDED
#define _LAUDIO_H_INCLUDED

#ifdef __cplusplus
extern "C" {
#endif

int  libmedia_start(msm_ctx *ctx, int channels, int samplerate);
void libmedia_stop(msm_ctx *ctx);
void libmedia_pause(msm_ctx *ctx);
void libmedia_resume(msm_ctx *ctx);
ssize_t libmedia_write(msm_ctx *ctx, const void *buf, size_t count);

int  libmediacb_start(msm_ctx *ctx, int channels, int samplerate);
void libmediacb_stop(msm_ctx *ctx);
//void libmediacb_pause(msm_ctx *ctx);
//void libmediacb_resume(msm_ctx *ctx);
ssize_t libmediacb_write(msm_ctx *ctx, const void *buf, size_t count);

#ifdef __cplusplus
}
#endif

#endif
