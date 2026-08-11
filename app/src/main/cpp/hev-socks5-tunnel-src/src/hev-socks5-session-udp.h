/*
 ============================================================================
 Name        : hev-socks5-session-udp.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2017 - 2023 hev
 Description : Socks5 Session UDP
 ============================================================================
 */

#ifndef __HEV_SOCKS5_SESSION_UDP_H__
#define __HEV_SOCKS5_SESSION_UDP_H__

#include <hev-socks5-client-udp.h>

#include "hev-socks5-session.h"

#define HEV_SOCKS5_SESSION_UDP(p) ((HevSocks5SessionUDP *)p)
#define HEV_SOCKS5_SESSION_UDP_CLASS(p) ((HevSocks5SessionUDPClass *)p)
#define HEV_SOCKS5_SESSION_UDP_TYPE (hev_socks5_session_udp_class ())

typedef struct _HevSocks5SessionUDP HevSocks5SessionUDP;
typedef struct _HevSocks5SessionUDPClass HevSocks5SessionUDPClass;

/* How many mapdns destinations one association remembers. A session is one
 * application socket, and an app that uses several named peers at once is a
 * WebRTC/ICE agent — a handful of STUN/TURN hosts, not hundreds. */
#define HEV_SOCKS5_UDP_NAME_SLOTS 16
#define HEV_SOCKS5_UDP_NAME_MAX 255

/* One "the app addressed this peer by name" record.
 *
 * Under mapdns the app never sees a real address: it resolved a hostname to a
 * synthetic 198.18.x.x and sends there, so every reply must appear to come from
 * that synthetic address or the app drops it as coming from the wrong peer. The
 * proxy only knows the name, so the name is what comes back — and this is where
 * it is turned back into the address the app is waiting on. */
typedef struct
{
    char name[HEV_SOCKS5_UDP_NAME_MAX + 1];
    unsigned char len;
    int addr; /* mapdns synthetic IPv4, network order */
    int port; /* host order, as lwIP uses */
} HevSocks5UDPNameMap;

struct _HevSocks5SessionUDP
{
    HevSocks5ClientUDP base;

    HevSocks5SessionData data;

    HevList frame_list;
    struct udp_pcb *pcb;
    HevTaskMutex *mutex;
    int frames;
    int addr;
    int port;
    /* Round-robin over the slots; a name that falls out simply gets re-learned
     * on the app's next datagram to it. */
    HevSocks5UDPNameMap names[HEV_SOCKS5_UDP_NAME_SLOTS];
    int names_next;
};

struct _HevSocks5SessionUDPClass
{
    HevSocks5ClientUDPClass base;

    HevSocks5SessionIface session;
};

HevObjectClass *hev_socks5_session_udp_class (void);

int hev_socks5_session_udp_construct (HevSocks5SessionUDP *self,
                                      struct udp_pcb *pcb, HevTaskMutex *mutex);

HevSocks5SessionUDP *hev_socks5_session_udp_new (struct udp_pcb *pcb,
                                                 HevTaskMutex *mutex);

#endif /* __HEV_SOCKS5_SESSION_UDP_H__ */
