#!/usr/bin/env python3
"""modkit: inspect an extracted Sword / Shield RomFS, find its data tables, and build Galar Native mods from them.

Pure Python 3 (standard library only). Nothing from the game is stored in this repository: every command reads YOUR extracted files and
writes only to the folder you name. Mods you build contain files derived from your dump, so keep them for your own use.

Commands (run `modkit.py <command> -h` for options):

  inspect          Walk a RomFS folder: sizes, extensions, candidate data tables, the ExeFS `main` build ID checked against the game profile.
                   `--report out.json` writes names and sizes only (no file contents), safe to share.
  probe-personal   Work out the layout of the per-species table (stats, types, catch rate, abilities) by looking for species whose values
                   are well known. Writes a layout JSON; prints what it could and could not confirm.
  personal-dump    Print the table as CSV using that layout.
  personal-edit    Apply an edits JSON to the table and write the changed file in mod layout (<out>/romfs/<same path>).
  package          Zip a mod folder the way the app's Mods > Import expects.
"""
import argparse, csv, hashlib, itertools, json, os, re, struct, sys, zipfile

# ----------------------------------------------------------------------------------------------------------------------------------
# Reference facts used to recognise the species table. Canonical stat order (hp, atk, def, spa, spd, spe); type numbering is the
# series' own (0 Normal ... 17 Fairy). These are the same in every generation 8 build of these species.
STAT_NAMES = ['hp', 'atk', 'def', 'spa', 'spd', 'spe']
TYPE_NAMES = ['Normal', 'Fighting', 'Flying', 'Poison', 'Ground', 'Rock', 'Bug', 'Ghost', 'Steel', 'Fire', 'Water', 'Grass', 'Electric',
              'Psychic', 'Ice', 'Dragon', 'Dark', 'Fairy']
# national dex number -> (name, stats, (type1, type2), catch rate, first ability id)
KNOWN = {
    1:   ('Bulbasaur',  (45, 49, 49, 65, 65, 45),      (11, 3),  45,  65),
    6:   ('Charizard',  (78, 84, 78, 109, 85, 100),    (9, 2),   45,  66),
    25:  ('Pikachu',    (35, 55, 40, 50, 50, 90),      (12, 12), 190, 9),
    132: ('Ditto',      (48, 48, 48, 48, 48, 48),      (0, 0),   35,  7),
    143: ('Snorlax',    (160, 110, 65, 65, 110, 30),   (0, 0),   25,  17),
    150: ('Mewtwo',     (106, 110, 90, 154, 90, 130),  (13, 13), 3,   46),
}
# file-name fragments worth listing as candidate data tables
INTERESTING = ('personal', 'waza', 'move', 'monsno', 'evolution', 'evo', 'learn', 'encount', 'trainer', 'trdata', 'item', 'tokusei',
               'ability', 'pokedex', 'zukan', 'egg', 'growth', 'message', 'monsname', 'tamago', 'wild', 'raid', 'nest')

def die(msg):
    sys.exit(f'modkit: {msg}')

def sha(path, limit=None):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        left = limit
        while True:
            b = f.read(1 << 20 if left is None else min(1 << 20, left))
            if not b: break
            h.update(b)
            if left is not None:
                left -= len(b)
                if left <= 0: break
    return h.hexdigest()

def human(n):
    for u in ('B', 'KB', 'MB', 'GB'):
        if n < 1024 or u == 'GB': return f'{n:.0f} {u}' if u == 'B' else f'{n:.1f} {u}'
        n /= 1024

# ----------------------------------------------------------------------------------------------------------------------------------
# inspect

def nso_module_id(path):
    """First 16 bytes of the NSO module ID (what a .pchtxt calls @nsobid-, and the profile's `build ... main=`)."""
    with open(path, 'rb') as f: h = f.read(0x60)
    if len(h) < 0x60 or h[:4] != b'NSO0': return None
    return h[0x40:0x50].hex()

def profile_builds(tsv):
    """{module: build id} from the `build` line of a dm/games/<title>/game.tsv."""
    for ln in open(tsv, encoding='utf-8', errors='replace'):
        if ln.startswith('build\t'):
            return dict(p.split('=', 1) for p in ln.rstrip('\n').split('\t')[3:] if '=' in p)
    return {}

def cmd_inspect(a):
    root = a.romfs
    if not os.path.isdir(root): die(f'{root}: not a folder')
    files = []
    for d, _, fs in os.walk(root):
        for f in fs:
            p = os.path.join(d, f)
            files.append((os.path.relpath(p, root).replace(os.sep, '/'), os.path.getsize(p)))
    files.sort()
    if not files: die('no files under that folder')
    total = sum(s for _, s in files)
    print(f'{root}: {len(files)} files, {human(total)}')
    marker = 'bin/sound/NX64/wwise/bank/Stream.pck'
    print(f'  game marker {marker}: {"present" if any(r == marker for r, _ in files) else "MISSING (is this the RomFS root?)"}')
    tops = {}
    for r, s in files:
        t = r.split('/')[0]
        n, sz = tops.get(t, (0, 0)); tops[t] = (n + 1, sz + s)
    print('  top-level folders:')
    for t, (n, sz) in sorted(tops.items(), key=lambda kv: -kv[1][1]):
        print(f'    {t:<24}{n:>7} files {human(sz):>10}')
    exts = {}
    for r, s in files:
        e = os.path.splitext(r)[1].lower() or '(none)'
        n, sz = exts.get(e, (0, 0)); exts[e] = (n + 1, sz + s)
    print('  extensions: ' + ', '.join(f'{e} x{n}' for e, (n, _) in sorted(exts.items(), key=lambda kv: -kv[1][0])[:14]))
    cands = [(r, s) for r, s in files if any(k in r.lower() for k in INTERESTING) and s <= a.max_hash]
    print(f'  candidate data files ({len(cands)}; name matches {", ".join(INTERESTING[:8])} ...):')
    for r, s in cands[:a.show]:
        print(f'    {r}  {human(s)}')
    if len(cands) > a.show: print(f'    ... {len(cands) - a.show} more (use --show N or --report)')
    big = [(r, s) for r, s in files if r.lower().endswith(('.gfpak', '.arc'))]
    if big: print(f'  archives (not unpacked by modkit yet): {len(big)}, e.g. {big[0][0]}')
    mid = None
    if a.main:
        mid = nso_module_id(a.main)
        print(f'  ExeFS main module ID (first 16 bytes): {mid or "not an NSO file"}')
        if a.profile and mid:
            want = profile_builds(a.profile).get('main')
            print(f'  profile {os.path.basename(os.path.dirname(a.profile))} expects main={want}: ' + ('MATCH' if want == mid else 'DIFFERENT (wrong game or version; code patches will not apply)'))
    if a.report:
        out = {'root_marker': any(r == marker for r, _ in files), 'file_count': len(files), 'total_bytes': total, 'main_module_id': mid,
               'files': [{'path': r, 'size': s} for r, s in files],
               'candidate_hashes': {r: sha(os.path.join(root, r)) for r, s in cands}}
        with open(a.report, 'w') as f: json.dump(out, f, indent=1)
        print(f'  wrote {a.report} (names, sizes and hashes only: no file contents)')

# ----------------------------------------------------------------------------------------------------------------------------------
# the per-species table

def u(data, off, size):
    return int.from_bytes(data[off:off + size], 'little')

def probe(data):
    """Find (entry_size, base, index_shift, stat offsets ...) so that species in KNOWN read back their known values.
    Returns a list of candidate layouts, best first. Only looks at fixed-size record tables."""
    out = []
    n = len(data)
    for size in range(16, 513, 2):
        for base in (0, 4, 8, 12, 16, 32):
            if (n - base) % size or (n - base) // size < 160: continue
            for shift in (0, -1, 1):
                ents = {}
                ok = True
                for dex in KNOWN:
                    i = base + (dex + shift) * size
                    if i < 0 or i + size > n: ok = False; break
                    ents[dex] = data[i:i + size]
                if not ok: continue
                lay = probe_entries(ents, size)
                if lay: out.append(dict(lay, entry_size=size, base=base, index_shift=shift, count=(n - base) // size))
    out.sort(key=lambda l: -len(l['fields']))
    return out

def probe_entries(ents, size):
    # stats: six consecutive bytes, in some order
    stats_at = None
    for o in range(size - 5):
        cand = [set(range(6)) for _ in range(6)]
        ok = True
        for dex, e in ents.items():
            known = KNOWN[dex][1]
            w = e[o:o + 6]
            if sorted(w) != sorted(known): ok = False; break
            for p in range(6): cand[p] &= {j for j in range(6) if known[j] == w[p]}
        if not ok or any(not c for c in cand): continue
        perms = [p for p in itertools.permutations(range(6)) if all(p[i] in cand[i] for i in range(6))]
        if len(perms) == 1: stats_at = (o, perms[0]); break
    if stats_at is None: return None
    o, perm = stats_at
    fields = {STAT_NAMES[perm[i]]: {'offset': o + i, 'size': 1} for i in range(6)}
    notes = {}

    # types: two bytes, type1 then type2
    wants = [KNOWN[d][2] for d in ents]
    t = [off for off in range(size - 1) if all(tuple(e[off:off + 2]) == w for e, w in zip(ents.values(), wants))]
    if len(t) >= 1:
        fields['type1'] = {'offset': t[0], 'size': 1}; fields['type2'] = {'offset': t[0] + 1, 'size': 1}
        if len(t) > 1: notes['types'] = f'ambiguous, also at {t[1:]}'
    wants = [KNOWN[d][3] for d in ents]
    c = [off for off in range(size) if all(e[off] == w for e, w in zip(ents.values(), wants))]
    if c:
        fields['catch_rate'] = {'offset': c[0], 'size': 1}
        if len(c) > 1: notes['catch_rate'] = f'ambiguous, also at {c[1:]}'
    wants = [KNOWN[d][4] for d in ents]
    ab = [off for off in range(size - 1) if all(u(e, off, 2) == w for e, w in zip(ents.values(), wants))]
    if ab:
        fields['ability1'] = {'offset': ab[0], 'size': 2}
        if len(ab) > 1: notes['ability1'] = f'ambiguous, also at {ab[1:]}'
    return {'fields': fields, 'notes': notes, 'stat_order': [STAT_NAMES[perm[i]] for i in range(6)]}

def cmd_probe(a):
    data = open(a.file, 'rb').read()
    print(f'{a.file}: {len(data)} bytes, sha256 {hashlib.sha256(data).hexdigest()[:16]}...')
    cands = probe(data)
    if not cands:
        print('No fixed-size record table with the expected species values was found.\n'
              'Likely causes: this is not the species table; it is a FlatBuffer or inside an archive (modkit cannot read those yet);\n'
              'or the file is compressed. Run `inspect` and send the report.')
        return 1
    lay = cands[0]
    print(f'Best layout: entry size {lay["entry_size"]:#x}, base {lay["base"]:#x}, index shift {lay["index_shift"]}, {lay["count"]} entries'
          f' (species N is entry N{lay["index_shift"]:+d}; forms follow)')
    print(f'  stat order in the file: {", ".join(lay["stat_order"])}')
    for name, f in lay['fields'].items(): print(f'  {name:<11} +{f["offset"]:#04x} ({f["size"]} byte{"s" if f["size"] > 1 else ""})')
    for k, v in lay['notes'].items(): print(f'  note: {k} {v}')
    if len(cands) > 1: print(f'  ({len(cands) - 1} other candidate layout(s); the best covers the most fields)')
    missing = [x for x in ('type1', 'catch_rate', 'ability1') if x not in lay['fields']]
    if missing: print(f'  not confirmed: {", ".join(missing)}')
    lay['source_sha256'] = hashlib.sha256(data).hexdigest()
    with open(a.out, 'w') as f: json.dump(lay, f, indent=1)
    print(f'wrote {a.out}')
    return 0

def load_layout(path):
    lay = json.load(open(path))
    for k in ('entry_size', 'base', 'index_shift', 'fields'):
        if k not in lay: die(f'{path}: layout missing "{k}"')
    return lay

def entry_off(lay, idx):
    off = lay['base'] + (idx + lay['index_shift']) * lay['entry_size']
    if idx < 0 or off < 0 or off + lay['entry_size'] > lay['base'] + lay['count'] * lay['entry_size']: die(f'entry {idx} is outside the table ({lay["count"]} entries)')
    return off

def cmd_dump(a):
    lay = load_layout(a.layout); data = open(a.file, 'rb').read()
    names = list(lay['fields'])
    w = csv.writer(open(a.csv, 'w', newline='') if a.csv else sys.stdout)
    w.writerow(['index', 'known'] + names)
    for idx in range(0, lay['count'] - (1 if lay['index_shift'] > 0 else 0)):
        try: off = entry_off(lay, idx)
        except SystemExit: break
        row = [u(data, off + lay['fields'][n]['offset'], lay['fields'][n]['size']) for n in names]
        w.writerow([idx, KNOWN[idx][0] if idx in KNOWN else ''] + row)

def apply_edits(data, lay, edits):
    """edits: {"<index>": {"<field>": value, "types": [a, b]}}. Returns (new bytes, list of change lines)."""
    out = bytearray(data); log = []
    for key, ch in edits.items():
        idx = int(key); off = entry_off(lay, idx)
        flat = {}
        for k, v in ch.items():
            if k == 'types':
                if not (isinstance(v, list) and len(v) == 2): die(f'{key}: "types" must be [type1, type2]')
                flat['type1'], flat['type2'] = v
            elif k == 'stats':
                if not (isinstance(v, list) and len(v) == 6): die(f'{key}: "stats" must be six numbers in order {STAT_NAMES}')
                flat.update(dict(zip(STAT_NAMES, v)))
            else: flat[k] = v
        for k, v in flat.items():
            f = lay['fields'].get(k)
            if f is None: die(f'{key}: no field "{k}" in the layout (have: {", ".join(lay["fields"])})')
            if not isinstance(v, int) or v < 0 or v >= 1 << (8 * f['size']): die(f'{key}.{k}: {v!r} does not fit {f["size"]} byte(s)')
            p = off + f['offset']; old = u(out, p, f['size'])
            out[p:p + f['size']] = v.to_bytes(f['size'], 'little')
            log.append(f'  entry {idx}{" (" + KNOWN[idx][0] + ")" if idx in KNOWN else ""}: {k} {old} -> {v}')
    return bytes(out), log

def cmd_edit(a):
    lay = load_layout(a.layout)
    src = os.path.join(a.romfs, a.file)
    if not os.path.isfile(src): die(f'{src}: not found')
    data = open(src, 'rb').read()
    if lay.get('source_sha256') and lay['source_sha256'] != hashlib.sha256(data).hexdigest():
        print('warning: this file differs from the one the layout was probed on (different game version, or already edited)', file=sys.stderr)
    edits = json.load(open(a.edits))
    new, log = apply_edits(data, lay, edits.get('species', edits))
    print('\n'.join(log) or 'no changes')
    if a.dry_run or not log: return
    dst = os.path.join(a.out, 'romfs', a.file)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    open(dst, 'wb').write(new)
    with open(os.path.join(a.out, 'readme.txt'), 'w') as f:
        f.write(f'{a.name}\nBuilt by modkit from a personal copy of the game. {len(log)} edit(s) to {a.file}.\nFor Pokémon Sword / Shield 1.3.2 in Galar Native. Mods must be off for local play.\n')
    print(f'wrote {dst} and readme.txt; zip it with: modkit.py package {a.out} <name>.zip')

# ----------------------------------------------------------------------------------------------------------------------------------
def cmd_package(a):
    if not os.path.isdir(os.path.join(a.mod, 'romfs')) and not os.path.isdir(os.path.join(a.mod, 'exefs')): die(f'{a.mod}: needs a romfs/ or exefs/ folder')
    n = 0
    with zipfile.ZipFile(a.zip, 'w', zipfile.ZIP_DEFLATED) as z:
        for d, ds, fs in os.walk(a.mod):
            ds.sort()
            for f in sorted(fs):
                p = os.path.join(d, f); rel = os.path.relpath(p, a.mod).replace(os.sep, '/')
                zi = zipfile.ZipInfo(rel, (2026, 1, 1, 0, 0, 0)); zi.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(zi, open(p, 'rb').read()); n += 1
    print(f'{a.zip}: {n} files. On the phone: Settings (☰) > Mods > Import a mod (.zip)')

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = ap.add_subparsers(dest='cmd', required=True)
    p = sp.add_parser('inspect'); p.add_argument('romfs'); p.add_argument('--main', help='ExeFS/main (an NSO) to read the build ID from')
    p.add_argument('--profile', help='dm/games/<title>/game.tsv to compare the build ID with'); p.add_argument('--report'); p.add_argument('--show', type=int, default=60)
    p.add_argument('--max-hash', type=int, default=256 << 20, help='largest candidate file to hash for --report'); p.set_defaults(f=cmd_inspect)
    p = sp.add_parser('probe-personal'); p.add_argument('file'); p.add_argument('--out', default='personal_layout.json'); p.set_defaults(f=cmd_probe)
    p = sp.add_parser('personal-dump'); p.add_argument('file'); p.add_argument('--layout', required=True); p.add_argument('--csv'); p.set_defaults(f=cmd_dump)
    p = sp.add_parser('personal-edit'); p.add_argument('--romfs', required=True); p.add_argument('--file', required=True, help='path of the table inside the RomFS')
    p.add_argument('--layout', required=True); p.add_argument('--edits', required=True); p.add_argument('--out', required=True); p.add_argument('--name', default='modkit mod')
    p.add_argument('--dry-run', action='store_true'); p.set_defaults(f=cmd_edit)
    p = sp.add_parser('package'); p.add_argument('mod'); p.add_argument('zip'); p.set_defaults(f=cmd_package)
    a = ap.parse_args()
    sys.exit(a.f(a) or 0)

if __name__ == '__main__': main()
