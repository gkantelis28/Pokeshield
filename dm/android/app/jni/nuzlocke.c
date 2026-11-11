// Nuzlocke mode's eyes (the rules and the run itself are Nuzlocke.java's): the player's Pokémon read straight out of the game's memory: the party (each second),
// every Pokémon the player owns (boxes included, each scan), and each party Pokémon whose HP falls to 0. Nothing in the game's code is touched. The Pokémon are
// found without any address, on any build; the badges and the battle style (below) use fixed offsets, on the main builds in k_rows only.
// Pokémon Sword / Shield keep every Pokémon in the series' documented PK8 form, the one save editors read: 0x148 bytes, 0x158 in the party (level and stats after
// them); bytes 8-0x147 encrypted with a generator seeded with the encryption constant (+0), the party stats again from the same seed; four 0x50-byte blocks in an
// order picked by the constant; a 16-bit sum of the decrypted words at +6. A record counts only when its checks pass (party: the stats in range, the sum; stored:
// species, the player's trainer ID, the sum), so random memory does not pass. Fields used (offsets in the decrypted record): species +8, trainer ID +0xc / +0xe,
// PID +0x1c, nickname +0x58, HP +0x8a, IVs / egg / nicknamed +0x8c, egg location +0x120, met location +0x122, ball +0x124.
// Finding things: a scan of the game's private writable memory (a few seconds, on this low-priority thread) lists the valid party-form records; the party is the
// longest run of them 0x158 apart (the save data's six slots), the run followed by an empty slot preferred, and the player's trainer ID is its first Pokémon's.
// With that ID known the same scan also lists stored-form records that carry it (the boxes). Every second the party and the party-form records are read again;
// a record of one of the party's Pokémon (by its encryption constant, so a battle's copy counts too) whose HP goes from above 0 to 0 is a faint. A rescan every
// 60 s picks up new records (a battle's copies, a catch sent to the boxes), and one at once when the party no longer reads back. Log lines start with [nuzlocke].
// Two of the game's own objects are found by the same scan, on the main builds they were found in only (k_rows, gated on main's build ID; any other build: nothing
// is read or written, the badge counter stays manual): the one holding the save's "Misc" block (badges, money) and the one holding the game's Options. Each is found
// by the three class-table pointers its constructor writes at +0, +0x48 and +0x50 (base + a fixed offset each, so random memory doesn't match).
// - Misc (Shield main: constructor +0x14386a0, 0x298 bytes on the heap): the block's 0x21c bytes at +0x60, copied in from the save on load (+0x14389d0, block key
//   0x1b882b09) and back on save (+0x1438b30). Byte +0x60 is the badges, one bit per Gym (save editors read the block the same way).
// - Options (constructor +0x140d120): one word at +0x60, the Options menu's settings as bits (load +0x140d730, save +0x140d410, one save block per setting):
//   bits 0-1 text speed, bit 2 battle effects, bit 3 battle style (0 Switch, the default; 1 Set). With "Battle style: Set" on, the engine sets bit 3 again whenever
//   it finds it clear: the game itself then plays Set, and its Options menu shows Set.
#include "dm.h"
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>

enum { STORED = 0x148, PARTY = 0x158, BLK = 0x50, MAXREC = 512, MAXOWN = 1400, CHUNK = 1 << 20, MAXSLOT = 6, NSTEP = 0xb0 };
// the block order for each shuffle value ((constant >> 13) & 31, mod 24): block b of the record is stored at position BLOCKPOS[sv][b]
static const uint8_t BLOCKPOS[24][4] = {
    {0,1,2,3}, {0,1,3,2}, {0,2,1,3}, {0,3,1,2}, {0,2,3,1}, {0,3,2,1}, {1,0,2,3}, {1,0,3,2}, {2,0,1,3}, {3,0,1,2}, {2,0,3,1}, {3,0,2,1},
    {1,2,0,3}, {1,3,0,2}, {2,1,0,3}, {3,1,0,2}, {2,3,0,1}, {3,2,0,1}, {1,2,3,0}, {1,3,2,0}, {2,1,3,0}, {3,1,2,0}, {2,3,1,0}, {3,2,1,0} };

typedef struct { uint32_t ec, pid; uint16_t species, hp, hpmax, tid, sid, metloc, eggloc; uint8_t level, ball, nicknamed, egg; char nick[40]; } mon_t;

static ssize_t rd(uintptr_t a, void *b, size_t n) { struct iovec l = { b, n }, r = { (void *)a, n }; return process_vm_readv(getpid(), &l, 1, &r, 1, 0); }
static ssize_t wr(uintptr_t a, const void *b, size_t n) { struct iovec l = { (void *)b, n }, r = { (void *)a, n }; return process_vm_writev(getpid(), &l, 1, &r, 1, 0); }

// fixed offsets in main (see the top): the class tables of the Misc and the Options objects, at +0, +0x48, +0x50 of each
static const struct { const char *build, *what; uint64_t misc[3], opt[3]; } k_rows[] = {
    { "a16802625e7826bf83b6f9708e475b91", "Pokémon Shield 1.3.2", { 0x25a9968, 0x25a99d0, 0x25a9a10 }, { 0x25a8780, 0x25a87e0, 0x25a8820 } },
};
static int g_row = -2;                   // the matching row, -1 none, -2 not looked up yet
static uint64_t g_vt_misc[3], g_vt_opt[3];
static uintptr_t g_misc, g_opt;          // the objects found (0 = not found yet)
static int g_badges = -1, g_badge_bits = -1, g_style = -1;   // as last read; -1 = unknown
static volatile int g_hold_set;          // Nuzlocke.java: hold the battle style on Set
static int row(void) {
    if (g_row != -2) return g_row;
    for (int i = 0; i < g_nmod; i++) if (!strcmp(g_mod[i].name, "main") && g_mod[i].build_id[0]) {
        g_row = -1;
        for (int k = 0; k < (int)(sizeof k_rows / sizeof k_rows[0]); k++) if (!strcmp(g_mod[i].build_id, k_rows[k].build)) {
            g_row = k; uint64_t b = (uint64_t)(uintptr_t)g_mod[i].base;
            for (int j = 0; j < 3; j++) { g_vt_misc[j] = b + k_rows[k].misc[j]; g_vt_opt[j] = b + k_rows[k].opt[j]; }
        }
        fprintf(stderr, "[nuzlocke] main %s: %s\n", g_mod[i].build_id, g_row >= 0 ? k_rows[g_row].what : "no known addresses (badges stay manual, no battle style lock)");
    }
    return g_row;   // (still -2 when main isn't loaded yet: asked again later)
}
static int is_obj(const uint8_t *p, const uint64_t *vt) {
    uint64_t a, b, c; memcpy(&a, p, 8); if (a != vt[0]) return 0;
    memcpy(&b, p + 0x48, 8); memcpy(&c, p + 0x50, 8); return b == vt[1] && c == vt[2];
}
static int still_obj(uintptr_t o, const uint64_t *vt) { uint8_t b[0x58]; return o && rd(o, b, sizeof b) == (ssize_t)sizeof b && is_obj(b, vt); }
static double now_s(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec / 1e9; }
static uint16_t u16(const uint8_t *p) { return (uint16_t)(p[0] | p[1] << 8); }
static uint32_t u32(const uint8_t *p) { return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24; }
static void pk_crypt(uint8_t *d, int n, uint32_t seed) {
    for (int i = 0; i + 1 < n; i += 2) { seed = seed * 0x41C64E6Du + 0x6073u; d[i] ^= (uint8_t)(seed >> 16); d[i + 1] ^= (uint8_t)(seed >> 24); }
}
// the generator k steps on, as seed * A[k] + C[k]: one word of a stored record decrypted without the ones before it (the boxes' quick checks)
static uint32_t JA[NSTEP + 1], JC[NSTEP + 1];
static void jumps(void) { JA[0] = 1; JC[0] = 0; for (int k = 0; k < NSTEP; k++) { JA[k + 1] = JA[k] * 0x41C64E6Du; JC[k + 1] = JC[k] * 0x41C64E6Du + 0x6073u; } }
static uint16_t word_at(const uint8_t *p, uint32_t ec, int off) {   // the decrypted word at record offset off (>= 8, below 0x148), wherever its block is stored
    int b = (off - 8) / BLK, so = 8 + BLOCKPOS[((ec >> 13) & 31) % 24][b] * BLK + (off - 8) % BLK, k = (so - 8) / 2 + 1;
    return (uint16_t)(u16(p + so) ^ ((ec * JA[k] + JC[k]) >> 16));
}
// the decrypted record (blocks in order) from its stored bytes, and its fields; 0 when the sum does not match
static int unpack(const uint8_t *p, mon_t *m) {
    uint32_t ec = u32(p);
    uint8_t d[STORED]; memcpy(d, p, STORED); pk_crypt(d + 8, STORED - 8, ec);
    uint16_t sum = 0; for (int i = 8; i < STORED; i += 2) sum = (uint16_t)(sum + u16(d + i));
    if (sum != u16(d + 6)) return 0;
    const uint8_t *bp = BLOCKPOS[((ec >> 13) & 31) % 24];
    uint8_t a[STORED]; memcpy(a, d, 8); for (int b = 0; b < 4; b++) memcpy(a + 8 + b * BLK, d + 8 + bp[b] * BLK, BLK);
    m->ec = ec; m->species = u16(a + 0x08); m->tid = u16(a + 0x0c); m->sid = u16(a + 0x0e); m->pid = u32(a + 0x1c); m->hp = u16(a + 0x8a);
    uint32_t iv = u32(a + 0x8c); m->egg = (uint8_t)(iv >> 30 & 1); m->nicknamed = (uint8_t)(iv >> 31);
    m->eggloc = u16(a + 0x120); m->metloc = u16(a + 0x122); m->ball = a[0x124];
    if (!m->species || m->species > 898 || m->ball > 30) return 0;
    size_t o = 0;   // the nickname: UTF-16 at +0x58, up to 12 characters; as UTF-8, control characters (the report's separators) as '?'
    for (int i = 0; i < 12; i++) { uint16_t c = u16(a + 0x58 + 2 * i); if (!c) break; if (c < 0x20) c = '?';
        if (c < 0x80) { if (o + 1 >= sizeof m->nick) break; m->nick[o++] = (char)c; }
        else if (c < 0x800) { if (o + 2 >= sizeof m->nick) break; m->nick[o++] = (char)(0xC0 | c >> 6); m->nick[o++] = (char)(0x80 | (c & 63)); }
        else { if (o + 3 >= sizeof m->nick) break; m->nick[o++] = (char)(0xE0 | c >> 12); m->nick[o++] = (char)(0x80 | (c >> 6 & 63)); m->nick[o++] = (char)(0x80 | (c & 63)); } }
    m->nick[o] = 0;
    return 1;
}
// a party-form record (PARTY bytes at p): 1 and its fields when it is valid
static int decode(const uint8_t *p, mon_t *m) {
    uint32_t ec = u32(p);
    if (!ec || p[4] || p[5]) return 0;
    uint8_t ps[16]; memcpy(ps, p + STORED, 16); pk_crypt(ps, 16, ec);   // the party stats first: cheap, and nearly all of memory fails here
    if (ps[0] < 1 || ps[0] > 100 || ps[1]) return 0;                    // level, a zero byte
    for (int i = 2; i < 14; i += 2) { uint16_t v = u16(ps + i); if (!v || v > 999) return 0; }   // max HP, Attack, Defense, Speed, Sp. Atk, Sp. Def
    if (!unpack(p, m)) return 0;
    m->hpmax = u16(ps + 2); m->level = ps[0];
    return m->hp <= m->hpmax;
}
// a stored-form record (STORED bytes) of the player's (trainer ID tid / sid): the quick checks on single words first
static int decode_stored(const uint8_t *p, uint16_t tid, uint16_t sid, mon_t *m) {
    uint32_t ec = u32(p);
    if (!ec || p[4] || p[5]) return 0;
    if (word_at(p, ec, 0x0c) != tid || word_at(p, ec, 0x0e) != sid) return 0;
    uint16_t sp = word_at(p, ec, 0x08); if (!sp || sp > 898) return 0;
    if (!unpack(p, m) || m->egg) return 0;   // (an egg in the boxes isn't a Pokémon yet)
    m->level = 0; m->hpmax = 0;
    return 1;
}

// ---------- the watcher thread's own state ----------
static uintptr_t g_rec[MAXREC]; static uint32_t g_rec_ec[MAXREC]; static int g_rec_hp[MAXREC], g_nrec;   // party-form records, with the HP last read there
static uintptr_t g_party; static double g_scan_t = -1e9; static int g_have_ot; static uint16_t g_tid, g_sid;
static uint32_t g_told[4096]; static int g_ntold;   // encryption constants already sent as N lines (open addressing, 0 = free)
// ---------- shared with nativePoll ----------
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static mon_t g_cur[MAXSLOT]; static int g_ncur; static char g_state[160] = "searching for the party";
static char *g_ev; static size_t g_nev, g_cev;

static int has_rec(uintptr_t a) { int lo = 0, hi = g_nrec; while (lo < hi) { int m = (lo + hi) / 2; if (g_rec[m] == a) return 1; if (g_rec[m] < a) lo = m + 1; else hi = m; } return 0; }
static int told(uint32_t ec) {   // 1 when ec was sent before; else remembers it
    unsigned h = (ec * 2654435761u) & 4095;
    for (int i = 0; i < 4096; i++, h = (h + 1) & 4095) { if (g_told[h] == ec) return 1; if (!g_told[h]) { if (g_ntold < 4000) { g_told[h] = ec; g_ntold++; } return 0; } }
    return 1;
}
static void event(const char *line) {   // (g_mu held)
    size_t l = strlen(line);
    if (g_nev + l + 1 > g_cev) { size_t c = g_cev ? g_cev * 2 : 16384; while (c < g_nev + l + 1) c *= 2; if (c > (1u << 20)) return; char *n = realloc(g_ev, c); if (!n) return; g_ev = n; g_cev = c; }
    memcpy(g_ev + g_nev, line, l); g_nev += l; g_ev[g_nev] = 0;
}
static int shiny(const mon_t *m) { return ((m->tid ^ m->sid ^ (m->pid >> 16) ^ (m->pid & 0xffff)) & 0xffff) < 16; }
// kind constant species level hp maxhp nickname met-location ball shiny nicknamed egg-location egg (fields 0-12; stored records have level, HP and max HP 0)
static void mon_line(char *out, size_t n, char kind, const mon_t *m) {
    snprintf(out, n, "%c\t%08x\t%u\t%u\t%u\t%u\t%s\t%u\t%u\t%d\t%u\t%u\t%u\n", kind, m->ec, m->species, m->level, m->hp, m->hpmax, m->nick, m->metloc, m->ball, shiny(m),
             m->nicknamed, m->eggloc, m->egg);
}
static void scan(void) {
    static uint8_t buf[CHUNK + PARTY]; static mon_t own[MAXOWN]; double t0 = now_s(); unsigned long long bytes = 0; int n = 0, nown = 0, nmap = 0, have_ot = g_have_ot;
    uint16_t tid = g_tid, sid = g_sid;
    int r = row(); uintptr_t misc = still_obj(g_misc, g_vt_misc) ? g_misc : 0, opt = still_obj(g_opt, g_vt_opt) ? g_opt : 0;
    FILE *f = fopen("/proc/self/maps", "r"); char ln[512];
    while (f && fgets(ln, sizeof ln, f)) {
        unsigned long s, e; char pr[8] = "";
        if (sscanf(ln, "%lx-%lx %7s", &s, &e, pr) != 3 || pr[0] != 'r' || pr[1] != 'w' || pr[3] != 'p' || strstr(ln, "/dev/")) continue;
        nmap++;
        for (unsigned long a = s; a < e; a += CHUNK) {
            size_t len = e - a < CHUNK + PARTY ? e - a : CHUNK + PARTY; if (len < STORED) break;
            if (rd(a, buf, len) != (ssize_t)len) continue;
            bytes += len; mon_t m;
            for (size_t i = 0; i + STORED <= len && i < CHUNK; i += 8) {
                if (r >= 0) { if (!misc && is_obj(buf + i, g_vt_misc)) misc = a + i; else if (!opt && is_obj(buf + i, g_vt_opt)) opt = a + i; }
                if (i + PARTY <= len && n < MAXREC && decode(buf + i, &m)) { g_rec[n] = a + i; g_rec_ec[n] = m.ec; g_rec_hp[n] = m.hp; n++; continue; }
                if (have_ot && nown < MAXOWN && decode_stored(buf + i, tid, sid, &m)) own[nown++] = m;
            }
        }
    }
    if (f) fclose(f);
    g_nrec = n;   // (in address order: the mappings are listed in order)
    // the party: the longest run 0x158 apart (at most six), a run followed by an empty (all zero) slot preferred
    uintptr_t best = 0; int bscore = 0, bn = 0;
    for (int i = 0; i < n; i++) {
        if (has_rec(g_rec[i] - PARTY)) continue;   // (not the start of a run)
        int run = 1; while (run < MAXSLOT && has_rec(g_rec[i] + (uintptr_t)run * PARTY)) run++;
        int empty = 0; if (run < MAXSLOT) { uint8_t z[PARTY]; if (rd(g_rec[i] + (uintptr_t)run * PARTY, z, PARTY) == PARTY) { empty = 1; for (int k = 0; k < PARTY; k++) if (z[k]) { empty = 0; break; } } }
        int score = run * 2 + empty;
        if (score > bscore) { bscore = score; best = g_rec[i]; bn = run; }
    }
    if (r >= 0 && (misc != g_misc || opt != g_opt))
        fprintf(stderr, "[nuzlocke] game objects: Misc (badges) %s%lx, Options %s%lx\n", misc ? "at " : "not found ", (unsigned long)misc, opt ? "at " : "not found ", (unsigned long)opt);
    g_misc = misc; g_opt = opt;
    g_party = best; g_scan_t = now_s();
    if (best && !g_have_ot) { uint8_t b[PARTY]; mon_t m; if (rd(best, b, PARTY) == PARTY && decode(b, &m)) { g_tid = m.tid; g_sid = m.sid; g_have_ot = 1; } }
    pthread_mutex_lock(&g_mu);
    if (best) snprintf(g_state, sizeof g_state, "party found (%d), %d Pokémon of yours in the boxes and party", bn, nown);
    else snprintf(g_state, sizeof g_state, "searching for the party (%d records)", n);
    if (have_ot) {   // every Pokémon of the player's not sent before, then "D": that scan saw them all
        char l[256]; for (int i = 0; i < nown; i++) if (!told(own[i].ec)) { mon_line(l, sizeof l, 'N', &own[i]); event(l); }
        snprintf(l, sizeof l, "D\t%d\n", nown); event(l);
    }
    pthread_mutex_unlock(&g_mu);
    fprintf(stderr, "[nuzlocke] scan: %d party-form and %d stored records of the player's in %d mappings (%.0f MB), %.1f s; party %s%lx, %d slot(s)\n", n, nown, nmap,
            bytes / 1048576.0, now_s() - t0, best ? "at " : "not found ", (unsigned long)best, bn);
    if (!have_ot && g_have_ot) { fprintf(stderr, "[nuzlocke] trainer ID known: scanning again for the boxes\n"); scan(); }
}
// the badges and the battle style, and the Set hold (each second)
static void poll_game(void) {
    uint8_t b[0x64]; int bits = -1, style = -1;
    if (g_misc && rd(g_misc, b, sizeof b) == (ssize_t)sizeof b && is_obj(b, g_vt_misc)) bits = b[0x60]; else g_misc = 0;
    if (g_opt && rd(g_opt, b, sizeof b) == (ssize_t)sizeof b && is_obj(b, g_vt_opt)) {
        uint32_t w; memcpy(&w, b + 0x60, 4); style = (int)(w >> 3 & 1);
        if (g_hold_set && !style) {   // (an atomic OR on the game's own word: the other settings' bits stay as the game has them)
            __atomic_fetch_or((uint32_t *)(g_opt + 0x60), 8u, __ATOMIC_SEQ_CST); style = 1;
            fprintf(stderr, "[nuzlocke] battle style was Switch: set back to Set\n");
        }
    } else g_opt = 0;
    pthread_mutex_lock(&g_mu);
    if (bits >= 0 && bits != g_badge_bits) fprintf(stderr, "[nuzlocke] badges: %02x (%d)\n", bits, __builtin_popcount((unsigned)bits));
    g_badge_bits = bits; g_badges = bits < 0 ? -1 : __builtin_popcount((unsigned)bits); g_style = style;
    pthread_mutex_unlock(&g_mu);
}
static void poll_once(void) {
    uint8_t b[PARTY]; mon_t cur[MAXSLOT]; int nc = 0;
    poll_game();
    if (g_party) for (int k = 0; k < MAXSLOT; k++) { if (rd(g_party + (uintptr_t)k * PARTY, b, PARTY) != PARTY || !decode(b, &cur[nc])) break; nc++; }
    if (!nc) { pthread_mutex_lock(&g_mu); g_ncur = 0; pthread_mutex_unlock(&g_mu); g_party = 0; return; }
    pthread_mutex_lock(&g_mu);
    memcpy(g_cur, cur, sizeof cur); g_ncur = nc;
    for (int i = 0; i < g_nrec; i++) {
        mon_t m; if (rd(g_rec[i], b, PARTY) != PARTY || !decode(b, &m) || m.ec != g_rec_ec[i]) { g_rec_hp[i] = -1; continue; }   // (gone, or something else now)
        int mine = 0; const char *nick = m.nick; for (int k = 0; k < nc; k++) if (cur[k].ec == m.ec) { mine = 1; nick = cur[k].nick; }
        if (mine && g_rec_hp[i] > 0 && m.hp == 0) {
            char ln[96]; snprintf(ln, sizeof ln, "F\t%08x\n", m.ec); event(ln);
            fprintf(stderr, "[nuzlocke] %s (species %u, %08x) fainted (record at %lx)\n", nick, m.species, m.ec, (unsigned long)g_rec[i]);
        }
        g_rec_hp[i] = m.hp;
    }
    pthread_mutex_unlock(&g_mu);
}
static void *watch_main(void *x) {
    (void)x; setpriority(PRIO_PROCESS, (id_t)gettid(), 10);   // (this thread only: below the game's)
    jumps();
    fprintf(stderr, "[nuzlocke] watching the party\n");
    for (;;) {
        if (!g_party || now_s() - g_scan_t > 60) {
            if (!g_party && now_s() - g_scan_t < 10) { sleep(1); continue; }   // (not found: the game may still be loading; try every 10 s)
            scan();
        }
        poll_once();
        usleep(1000000);
    }
    return NULL;
}
// Nuzlocke.java, once a second while a run is on (tab-separated lines): "S <state>"; "G <badges> <badge bits> <battle style>" (-1 = not known: another build,
// or not found yet; style 0 Switch, 1 Set); "P ..." for each party slot in order and "N ..." for each Pokémon of the player's not reported before (mon_line's
// fields); "D <count>" after a scan that saw all of them; "F <constant>" for each faint since the last call
JNIEXPORT jstring JNICALL Java_com_nxnative_Nuzlocke_nativePoll(JNIEnv *env, jclass c) {
    (void)c; static int started;
    if (!started) { started = 1; pthread_t t; if (!pthread_create(&t, NULL, watch_main, NULL)) pthread_detach(t); }
    pthread_mutex_lock(&g_mu);
    size_t cap = 2048 + g_nev; char *out = malloc(cap); if (!out) { pthread_mutex_unlock(&g_mu); return (*env)->NewStringUTF(env, ""); }
    size_t o = (size_t)snprintf(out, cap, "S\t%s\nG\t%d\t%d\t%d\n", g_state, g_badges, g_badge_bits, g_style);
    for (int k = 0; k < g_ncur; k++) { char l[256]; mon_line(l, sizeof l, 'P', &g_cur[k]); size_t ll = strlen(l); if (o + ll < cap) { memcpy(out + o, l, ll); o += ll; } }
    if (g_nev && o + g_nev < cap) { memcpy(out + o, g_ev, g_nev); o += g_nev; }
    out[o] = 0; g_nev = 0;
    pthread_mutex_unlock(&g_mu);
    jstring s = (*env)->NewStringUTF(env, out); free(out);
    return s;
}
// Nuzlocke.java: hold the game's battle style on Set (a run with "Battle style: Set"), or stop holding it (the player's own choice then stays as it is)
JNIEXPORT void JNICALL Java_com_nxnative_Nuzlocke_nativeHoldSet(JNIEnv *env, jclass c, jboolean on) { (void)env; (void)c; g_hold_set = on ? 1 : 0; }
