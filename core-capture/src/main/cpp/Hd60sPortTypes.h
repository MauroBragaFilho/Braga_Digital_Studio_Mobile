/* SPDX-License-Identifier: GPL-2.0
 *
 * Trecho derivado do driver Linux "hd60s" (Elgato Game Capture HD60 S),
 * Copyright (c) 2026 Doug Brown <doug@schmorgal.com>,
 * https://github.com/dougg3/hd60s-linux-driver (GPL-2.0), adaptado para C++/Android.
 * Este arquivo continua sob a GPL-2.0; veja LICENSE-HD60S-GPL2.txt nesta pasta.
 */
#pragma once

// Camada de compatibilidade: permite compilar, quase sem alteração, o código portado do driver Linux
// (tipos do kernel, macros de log e o "dispositivo" mínimo que o front-end MST3367 precisa).

#include <algorithm>
#include <cerrno>
#include <climits>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <unistd.h>

#include "Hd60sLog.h"
#include "Hd60sUsb.h"

typedef uint8_t u8;
typedef uint16_t u16;
typedef uint32_t u32;
typedef uint64_t u64;
typedef int16_t s16;
typedef int32_t s32;

#define ARRAY_SIZE(a) (sizeof(a) / sizeof((a)[0]))
#define BIT(n) (1u << (n))
#define GENMASK(h, l) (((~0u) << (l)) & (~0u >> (31 - (h))))
#define FIELD_PREP(mask, val) ((((unsigned)(val)) << __builtin_ctz(mask)) & (mask))
#define FIELD_GET(mask, reg) ((((unsigned)(reg)) & (mask)) >> __builtin_ctz(mask))

// O primeiro argumento (o "struct device *" do kernel) é descartado sem ser avaliado.
#define dev_dbg(dev, ...) ((void)0)
#define dev_info(dev, ...) LOGI(__VA_ARGS__)
#define dev_warn(dev, ...) LOGW(__VA_ARGS__)
#define dev_err(dev, ...) LOGE(__VA_ARGS__)
#define dev_warn_ratelimited(dev, ...) LOGW(__VA_ARGS__)

#define mutex_lock(m) ((void)0)    // um único thread de controle usa o front-end
#define mutex_unlock(m) ((void)0)
#define lockdep_assert_held(m) ((void)0)

static inline void msleep(unsigned ms) { usleep(ms * 1000u); }
static inline void usleep_range(unsigned lo, unsigned) { usleep(lo); }
static inline u64 div_u64(u64 a, u64 b) { return a / b; }

template <typename T>
static inline T clamp(T v, T lo, T hi) { return v < lo ? lo : (v > hi ? hi : v); }

#define HD60S_VID		0x0fd9
#define HD60S_PID_REV1		0x004f
#define HD60S_PID_REV2		0x005e
#define HD60S_PID_REV3		0x0074
#define HD60S_PID_REV4		0x0076

#define HD60S_IF_VIDEO		0	/* EP 0x83, alt 0-4                  */
#define HD60S_IF_EVENT		1	/* EP 0x81; claimed, never opened    */
#define HD60S_EP_VIDEO		0x83

/*
 * Vendor requests. Both generations use the same six; revs 1-3 route 0xC0
 * through the MCU's I2C bridge rather than addressing chips directly.
 */
#define HD60S_REQ_C0		0xC0	/* channel read/write (MCU + I2C bridge) */
#define HD60S_REQ_C1		0xC1	/* GPIO                                  */
#define HD60S_REQ_C2		0xC2	/* FX3 stream pool byte                  */
#define HD60S_REQ_C6		0xC6	/* EP 0x81 watch list disarm             */
#define HD60S_REQ_C7		0xC7	/* I2C bitrate                           */
#define HD60S_REQ_EC		0xEC	/* cold start                            */

#define HD60S_CH64		0x0064	/* rev-4 register file                   */
#define HD60S_CH_I2C		0x5066	/* MCU I2C bridge, mode 1                */

/* I2C slaves behind the bridge on revs 1-3 */
/* 0x94 is the audio codec and 0xa2 the EDID EEPROM; neither is touched here. */
#define HD60S_I2C_PLL		0x88	/* clock/PLL block                       */
#define HD60S_I2C_CTL		0x98	/* board control device                  */
#define HD60S_I2C_TX		0x9a	/* HDMI loop-through transmitter         */
#define HD60S_I2C_MSTAR		0x9c	/* MST3367; reg 0x00 is the bank latch   */
#define HD60S_I2C_MCU		0xaa	/* the Nuvoton                           */
/* a 208-register device that is not on the capture path */
#define HD60S_I2C_EXT		0xd4

/* Channel 0x64 register file; the status block is its first 32 bytes. */
#define HD60S_REG_STREAM_EN	0x10
#define HD60S_REG_COLOR_RANGE	0x12
#define HD60S_REG_PICTURE	0x13	/* brightness/contrast/saturation/hue    */
#define HD60S_REG_AUDIO_SRC	0x3a
#define HD60S_REG_AUDIO_VOL2	0x3b	/* "volume extra" gain                   */
#define HD60S_REG_AUDIO_VOL	0x3c	/* analog volume                         */
#define HD60S_STATUS_LEN	32

/*
 * Control transfers are issued once, never retried and never canceled. The FX3
 * is very picky.
 */
#define HD60S_CTRL_TIMEOUT	5000
/*
 * Input color space, as bank 2 register 0x48 bits 6:5 report it on the MStar
 * front end. The values are the AVI InfoFrame's Y1Y0 field.
 */
#define HD60S_CS_RGB		0
#define HD60S_CS_YCBCR_422	1
#define HD60S_CS_YCBCR_444	2

/* indices into d->pic[], and the ops->pic_controls bits that gate them */
#define HD60S_PIC_BRIGHTNESS	BIT(0)
#define HD60S_PIC_CONTRAST	BIT(1)
#define HD60S_PIC_SATURATION	BIT(2)
#define HD60S_PIC_HUE		BIT(3)
#define HD60S_PIC_ALL		(HD60S_PIC_BRIGHTNESS | HD60S_PIC_CONTRAST | \
				 HD60S_PIC_SATURATION | HD60S_PIC_HUE)

/* Timing as reported by the 32-byte status block. */
struct hd60s_timing {
	u16	vtotal, htotal;
	u16	vactive;		/* FIELD height when interlaced        */
	u16	hactive;
	u8	fps;			/* field rate when interlaced          */
	u8	modeflag;
	u8	audio_khz;
};

/* modeflag bits */
#define HD60S_MF_INTERLACED	BIT(0)
#define HD60S_MF_FRACTIONAL	BIT(1)	/* 1000/1001 rate                      */
#define HD60S_MF_HDCP		BIT(3)
#define HD60S_MF_COLORIMETRY	GENMASK(5, 4)	/* 1 = BT.709, 2 = BT.601      */

/*
 * vactive is the FIELD height for interlaced input. The three half-heights of
 * 480i, 576i and 1080i are treated as interlaced regardless of what the scan
 * bit says.
 */
static inline bool hd60s_interlaced(const struct hd60s_timing *t)
{
	return (t->modeflag & HD60S_MF_INTERLACED) ||
	       t->vactive == 240 || t->vactive == 288 || t->vactive == 540;
}


#define HD60S_MT_EIA	0	/* CEA-861 timings   */
#define HD60S_MT_VESA	1	/* VESA/DMT timings  */
#define HD60S_MT_MEASURED 2	/* not a table row: the chip's own window */

struct hd60s_mode {
	u16	hactive, vactive;	/* vactive is the FIELD height when il */
	u16	htotal, vtotal;
	u32	pixclk;			/* kHz                                */
	u8	vfreq;			/* whole Hz, as the table stores it   */
	u8	interlaced;
	/* CEA-861 code for the AVI InfoFrame, 0 = none */
	u8	vic;
	u8	src;			/* HD60S_MT_*                         */
};

/* The registers the chip's mode detect reads, in the order it reads them. */
struct hd60s_mstar_regs {
	u8	b0_55;		/* sync status                        */
	u8	b0_6a, b0_6b;	/* htotal, in TMDS clocks             */
	u8	b0_57, b0_58;	/* horizontal period counter          */
	u8	b0_59, b0_5a;	/* vertical period counter            */
	u8	b0_5b, b0_5c;	/* vtotal                             */
	u8	b0_5f;		/* bit 3 = interlaced                 */
	u8	b2_29, b2_28;	/* hactive                            */
	u8	b1_01;		/* bit 2 = deep color valid           */
	u8	b2_47;		/* color depth, low nibble            */
	u8	b0_5c_again;	/* re-read of 0x5C, for the stability test */
};

enum hd60s_detect_result {
	HD60S_DET_OK,
	HD60S_DET_NO_SYNC,	/* no source; blank the output       */
	HD60S_DET_UNSTABLE,	/* measurement did not settle        */
	HD60S_DET_NO_MODE,	/* stable, but no table entry matched */
	HD60S_DET_DEEP_MISLOCK,	/* 36-bit link decoded as 24-bit     */
};

struct hd60s_detect {
	u16	htotal, vtotal, hactive;
	u16	hfreq;		/* units of 100 Hz  */
	u16	fv;		/* units of 0.1 Hz  */
	u16	calc_fv;	/* hfreq * 1000 / vtotal, for the log */
	u16	vper;		/* the raw counter fv is derived from */
	bool	interlaced;
	const struct hd60s_mode *mode;	/* set only on HD60S_DET_OK */
	/*
	 * DEEP_MISLOCK only: the row the reading becomes once the 36-bit
	 * scaling is applied. Evidence for the diagnosis, not a mode to
	 * program -- the chip's pixel data is noise.
	 */
	const struct hd60s_mode *deep_row;
};

enum hd60s_detect_result hd60s_mstar_decode(const struct hd60s_mstar_regs *r,
					    struct hd60s_detect *out);

const struct hd60s_mode *hd60s_find_mode(u16 hactive, u16 htotal, u16 vtotal,
					 u16 vfreq_dhz, bool interlaced);

bool hd60s_vper_fractional(u16 vper, u16 fv);

#define HD60S_CSC_VALUES	12

/* Color-range conversion; rev 4 writes the same values to a register. */
#define HD60S_CR_BYPASS		0	/* no conversion            */
#define HD60S_CR_SHRINK		1	/* 0-255  -> 16-235         */
#define HD60S_CR_EXPAND		2	/* 16-235 -> 0-255          */

/*
 * Twelve Q12 values for bank 0 registers 0x93-0xAA: the Y, Cr and Cb rows,
 * each as (G, R, B), then the three output offsets.
 *
 * pic[] is d->pic[], all 128 for neutral: brightness is the Y output offset,
 * contrast a gain on the luma row, saturation a gain on the chroma rows and
 * hue a rotation of the chroma rows into each other. color_range is
 * HD60S_CR_*, folded into the Q12 scale (hd60s_csc_num[] in hd60s-modes.c).
 *
 * passthrough says the source already sends YCbCr, so the matrix becomes the
 * identity with the picture controls still applied; converting anyway converts
 * twice and looks like it. It does not affect the output offsets -- whether
 * the incoming chroma is centered on 128 or 0 is bank 0 0x92, see
 * mstar_reg92().
 */
void hd60s_csc_matrix(bool bt709, bool passthrough, u8 color_range,
		      const u8 pic[4], s16 out[HD60S_CSC_VALUES]);
/* State the MStar front end carries between calls. */
struct hd60s_mstar {
	u8	blk[5];		/* read once at init, restored on stop         */
	bool	blk_valid;
	u32	audio_hz;
	/* the 0xD4 device's reference select and divider, as last written */
	u8	d4_sel, d4_reg, d4_val;
	bool	d4_valid;
	bool	warned_nomode;	/* only complain about an unmatched mode once  */
	bool	tx_probed;	/* its ID has been read                        */
	bool	tx_present;	/* the loop-through transmitter answered       */
	u32	tx_audio_hz;	/* the rate its audio path is programmed for   */
	bool	tx_audio_dirty;	/* bank 2 was touched; reprogram it            */
	bool	tx_up;		/* the takeover ran and the canary still holds */
	/* from the sink's EDID; an unidentified sink is driven as DVI */
	bool	tx_sink_hdmi;
	bool	tx_sink_audio;
	bool	tx_edid_valid;	/* current for this hot-plug session           */
	u8	tx_edid_tries;
	/* the received AVI InfoFrame's DB4 and DB2 bits 5:4; 0 = none */
	u8	src_vic;
	u8	src_aspect;
	/* what the loop-through AVI InfoFrame last emitted (DB2, DB4) */
	u8	tx_avi_db2;
	u8	tx_avi_vic;
	bool	hdcp;		/* bank 1 0x01 bit 0 | 0x34 bit 7 */
	/* the chip's measured window as a mode row; applied may point at it */
	struct hd60s_mode measured;
	bool	applied_frac;	/* the applied mode is a 1000/1001 rate */
	/* deep-color mis-lock recovery, bounded per signal session */
	u8	deep_tries;
	bool	deep_zeroed;	/* the gate block is being held at zero        */
	bool	warned_deep;
	/* an unmatched timing, seen once and awaiting a second poll */
	bool	nomode_pending;
	bool	nomode_interlaced;
	u8	nomode_tries;
	u16	nomode_hactive, nomode_vtotal, nomode_fv;
	/* what is programmed, and the detection that chose it */
	const struct hd60s_mode *applied;
	u16	applied_vtotal, applied_fv, applied_hactive;
	bool	applied_interlaced;
	bool	bt709;		/* colorimetry of the detected mode */
	/* declared by the received AVI InfoFrame; 0xff = nothing yet */
	u8	input_cs;	/* HD60S_CS_*                                 */
	u8	input_q;
	/* and what was last programmed, so a re-apply is level-triggered */
	u8	applied_cs, applied_q;
	bool	applied_bt709;
};

/** Estado mínimo do "dispositivo" para o código portado. */
struct hd60s_dev {
    hd60s::UsbDevice* usb = nullptr;
    struct hd60s_mstar mst;
    bool signal = false;      // há sinal (já com debounce), como d->signal do driver
    bool input_full = false;  // faixa de entrada detectada é full
    u8 pic[4] = {0x80, 0x80, 0x80, 0x80};
    u8 audio_src = 0;         // 0 = áudio embutido no HDMI
    u8 color_range = HD60S_CR_BYPASS;
    int ctrl_lock = 0;
    bool gone = false;
};

// Plano de controle (Hd60sMstarFrontEnd.cpp), usado pelo código portado.
int hd60s_vout_nolock(struct hd60s_dev* d, u8 req, u16 val, u16 idx, const void* buf, u16 len);
int hd60s_i2c_write(struct hd60s_dev* d, u8 slave, const u8* tx, int txlen);
int hd60s_i2c_read(struct hd60s_dev* d, u8 slave, const u8* tx, int txlen, u8* rx, int rxlen);

// Front-end MST3367 (Hd60sPortMstar.cpp).
int hd60s_mstar_init(struct hd60s_dev* d);
int hd60s_mstar_detect(struct hd60s_dev* d, struct hd60s_timing* t, bool* signal);
int hd60s_mstar_stream(struct hd60s_dev* d, bool on);
int hd60s_mstar_disarm_events(struct hd60s_dev* d);
int hd60s_mstar_write_csc(struct hd60s_dev* d);
