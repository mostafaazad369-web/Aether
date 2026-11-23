#!/usr/bin/env python3
"""Apply Aether's generation-aware lifecycle extension to the pinned HEV source."""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch-hev-lifecycle.py <hev-source-dir>")

root = Path(sys.argv[1])
main = root / "hev-main.c"
header = root / "hev-socks5-tunnel.h"
tunnel = root / "hev-socks5-tunnel.c"

def apply(path, changes):
    source = path.read_text(encoding="utf-8")
    for old, new, label in changes:
        if new in source:
            continue
        count = source.count(old)
        if count != 1:
            raise SystemExit(
                f"ERROR: {path.name}: expected one occurrence of {label}, found {count}; "
                "pinned HEV source drifted, refusing an unsafe patch."
            )
        source = source.replace(old, new, 1)
    path.write_text(source, encoding="utf-8")

apply(main, [
    (
        "#include <string.h>\n",
        "#include <string.h>\n#include <stdint.h>\n",
        "stdint include",
    ),
    (
        '#include "hev-main.h"\n',
        '''#include "hev-main.h"

typedef void (*HevSocks5TunnelReadyCallback) (void *user_data);

int hev_socks5_tunnel_main_with_ready_callback (
    const char *config_path, int tun_fd, uint64_t generation,
    HevSocks5TunnelReadyCallback ready_callback, void *user_data);
''',
        "main callback declaration",
    ),
    (
        "hev_socks5_tunnel_main_inner (int tun_fd)\n",
        "hev_socks5_tunnel_main_inner (int tun_fd, uint64_t generation,\n"
        "                              HevSocks5TunnelReadyCallback ready_callback,\n"
        "                              void *user_data)\n",
        "main inner signature",
    ),
    (
        "res = hev_socks5_tunnel_init (tun_fd);\n"
        "    if (res < 0)\n"
        "        goto free_task_sys;\n\n"
        "    hev_socks5_tunnel_run ();",
        "res = hev_socks5_tunnel_init (tun_fd, generation);\n"
        "    if (res < 0)\n"
        "        goto free_task_sys;\n\n"
        "    if (ready_callback)\n"
        "        ready_callback (user_data);\n\n"
        "    hev_socks5_tunnel_run ();",
        "post-init ready callback",
    ),
    (
        "    int res = hev_config_init_from_file (config_path);\n"
        "    if (res < 0)\n"
        "        return -1;\n\n"
        "    return hev_socks5_tunnel_main_inner (tun_fd);",
        "    return hev_socks5_tunnel_main_with_ready_callback (config_path, tun_fd,\n"
        "                                                       0, NULL, NULL);",
        "main-from-file wrapper",
    ),
    (
        "    return hev_socks5_tunnel_main_with_ready_callback (config_path, tun_fd,\n"
        "                                                       0, NULL, NULL);\n"
        "}\n",
        "    return hev_socks5_tunnel_main_with_ready_callback (config_path, tun_fd,\n"
        "                                                       0, NULL, NULL);\n"
        "}\n\n"
        "EXPORT_SYMBOL int\n"
        "hev_socks5_tunnel_main_with_ready_callback (\n"
        "    const char *config_path, int tun_fd, uint64_t generation,\n"
        "    HevSocks5TunnelReadyCallback ready_callback, void *user_data)\n"
        "{\n"
        "    int res = hev_config_init_from_file (config_path);\n"
        "    if (res < 0)\n"
        "        return -1;\n\n"
        "    return hev_socks5_tunnel_main_inner (tun_fd, generation,\n"
        "                                         ready_callback, user_data);\n"
        "}",
        "ready callback API implementation",
    ),
    (
        "    return hev_socks5_tunnel_main_inner (tun_fd);\n",
        "    return hev_socks5_tunnel_main_inner (tun_fd, 0, NULL, NULL);\n",
        "main-from-string wrapper",
    ),
    (
        "EXPORT_SYMBOL void\n"
        "hev_socks5_tunnel_quit (void)\n"
        "{\n"
        "    hev_socks5_tunnel_stop ();\n"
        "}\n",
        "EXPORT_SYMBOL void\n"
        "hev_socks5_tunnel_quit (void)\n"
        "{\n"
        "    hev_socks5_tunnel_stop ();\n"
        "}\n\n"
        "EXPORT_SYMBOL void\n"
        "hev_socks5_tunnel_quit_generation (uint64_t generation)\n"
        "{\n"
        "    hev_socks5_tunnel_stop_generation (generation);\n"
        "}\n",
        "generation-specific public quit",
    ),
])

apply(header, [
    (
        '#include "hev-list.h"\n',
        '#include <stdint.h>\n#include "hev-list.h"\n',
        "stdint header include",
    ),
    (
        "int hev_socks5_tunnel_init (int tun_fd);\n",
        "int hev_socks5_tunnel_init (int tun_fd, uint64_t generation);\n",
        "tunnel init declaration",
    ),
    (
        "void hev_socks5_tunnel_stop (void);\n",
        "void hev_socks5_tunnel_stop (void);\n"
        "void hev_socks5_tunnel_stop_generation (uint64_t generation);\n",
        "generation stop declaration",
    ),
])

old_stop = '''void
hev_socks5_tunnel_stop (void)
{
    int res;

    LOG_D ("socks5 tunnel stop");

retry:
    res = atomic_fetch_or (&tsync, SYNC_WAIT);
    if (res & SYNC_WAIT) {
        usleep (500);
        goto retry;
    }

    if (res & SYNC_SEND) {
        res = atomic_fetch_or (&tsync, SYNC_SENT);
        if (!(res & SYNC_SENT)) {
            res = hev_task_io_write (event_fds[1], &res, 1, hev_task_io_nowait,
                                     NULL);
            assert (res > 0 && "socks5 tunnel write event");
        }
    } else {
        atomic_fetch_or (&tsync, SYNC_STOP);
    }

    atomic_fetch_and (&tsync, ~SYNC_WAIT);
}
'''
new_stop = '''static void
hev_socks5_tunnel_stop_internal (uint64_t generation, int check_generation)
{
    int res;

    LOG_D ("socks5 tunnel stop");

    lifecycle_lock ();
    if (!lifecycle_active ||
        (check_generation && lifecycle_generation != generation)) {
        lifecycle_unlock ();
        return;
    }

retry:
    res = atomic_fetch_or (&tsync, SYNC_WAIT);
    if (res & SYNC_WAIT) {
        usleep (500);
        goto retry;
    }

    if (res & SYNC_SEND) {
        res = atomic_fetch_or (&tsync, SYNC_SENT);
        if (!(res & SYNC_SENT)) {
            res = hev_task_io_write (event_fds[1], &res, 1, hev_task_io_nowait,
                                     NULL);
            assert (res > 0 && "socks5 tunnel write event");
        }
    } else {
        atomic_fetch_or (&tsync, SYNC_STOP);
    }

    atomic_fetch_and (&tsync, ~SYNC_WAIT);
    lifecycle_unlock ();
}

void
hev_socks5_tunnel_stop (void)
{
    hev_socks5_tunnel_stop_internal (0, 0);
}

void
hev_socks5_tunnel_stop_generation (uint64_t generation)
{
    hev_socks5_tunnel_stop_internal (generation, 1);
}
'''

apply(tunnel, [
    (
        "static int run;\nstatic atomic_int tsync;\n",
        "static int run;\nstatic atomic_int tsync;\n\n"
        "/* Serialize stop against finalization and reject stale generations. */\n"
        "static atomic_flag lifecycle_gate = ATOMIC_FLAG_INIT;\n"
        "static int lifecycle_active;\n"
        "static uint64_t lifecycle_generation;\n\n"
        "static void\n"
        "lifecycle_lock (void)\n"
        "{\n"
        "    while (atomic_flag_test_and_set_explicit (&lifecycle_gate,\n"
        "                                               memory_order_acquire))\n"
        "        ;\n"
        "}\n\n"
        "static void\n"
        "lifecycle_unlock (void)\n"
        "{\n"
        "    atomic_flag_clear_explicit (&lifecycle_gate, memory_order_release);\n"
        "}\n",
        "lifecycle gate declarations",
    ),
    (
        "hev_socks5_tunnel_init (int tun_fd)\n",
        "hev_socks5_tunnel_init (int tun_fd, uint64_t generation)\n",
        "tunnel init definition",
    ),
    (
        "    hev_task_mutex_init (&mutex);\n\n"
        "    atomic_fetch_or (&tsync, SYNC_SEND);",
        "    hev_task_mutex_init (&mutex);\n\n"
        "    lifecycle_lock ();\n"
        "    lifecycle_active = 1;\n"
        "    lifecycle_generation = generation;\n"
        "    atomic_fetch_or (&tsync, SYNC_SEND);\n"
        "    lifecycle_unlock ();",
        "publish active generation",
    ),
    (
        '    LOG_D ("socks5 tunnel fini");\n\n'
        "retry:",
        '    LOG_D ("socks5 tunnel fini");\n\n'
        "    lifecycle_lock ();\n"
        "    lifecycle_active = 0;\n"
        "    lifecycle_generation = 0;\n"
        "    lifecycle_unlock ();\n\n"
        "retry:",
        "clear active generation",
    ),
    (
        old_stop,
        new_stop,
        "generation-aware stop implementation",
    ),
])

print("Applied Aether generation-safe HEV lifecycle extension.")
