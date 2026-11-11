import json, os, random, sys, tempfile, unittest, zipfile
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
import modkit

# A synthetic table: made-up layout (not the game's), random filler, so the probe has to discover the layout rather than assume it.
SIZE, COUNT, BASE = 0xB0, 900, 0
LAYOUT = dict(order=['hp', 'atk', 'def', 'spe', 'spa', 'spd'], stats=0x05, types=0x21, catch=0x4B, ability=0x6E)

def make_table(seed=1, size=SIZE, count=COUNT, base=BASE):
    rnd = random.Random(seed)
    data = bytearray(rnd.randrange(256) for _ in range(base + size * count))
    for dex, (name, stats, types, catch, ab) in modkit.KNOWN.items():
        o = base + dex * size
        for i, st in enumerate(LAYOUT['order']): data[o + LAYOUT['stats'] + i] = stats[modkit.STAT_NAMES.index(st)]
        data[o + LAYOUT['types']], data[o + LAYOUT['types'] + 1] = types
        data[o + LAYOUT['catch']] = catch
        data[o + LAYOUT['ability']:o + LAYOUT['ability'] + 2] = ab.to_bytes(2, 'little')
    return bytes(data)

class Probe(unittest.TestCase):
    def test_finds_layout(self):
        c = modkit.probe(make_table())
        self.assertTrue(c)
        l = c[0]
        self.assertEqual((l['entry_size'], l['base'], l['index_shift'], l['count']), (SIZE, 0, 0, COUNT))
        self.assertEqual(l['stat_order'], LAYOUT['order'])
        self.assertEqual(l['fields']['hp']['offset'], LAYOUT['stats'])
        self.assertEqual(l['fields']['spe']['offset'], LAYOUT['stats'] + 3)
        self.assertEqual(l['fields']['type1']['offset'], LAYOUT['types'])
        self.assertEqual(l['fields']['catch_rate']['offset'], LAYOUT['catch'])
        self.assertEqual(l['fields']['ability1'], {'offset': LAYOUT['ability'], 'size': 2})

    def test_header_and_shift(self):
        d = bytearray(make_table(2, 0xA8, 700, 0))
        d = bytes(8 * b'\0') + bytes(d)                      # an 8-byte header shifts the base
        c = modkit.probe(d)
        self.assertTrue(c and c[0]['base'] == 8 and c[0]['entry_size'] == 0xA8)

    def test_rejects_noise(self):
        rnd = random.Random(5)
        self.assertEqual(modkit.probe(bytes(rnd.randrange(256) for _ in range(0xB0 * 900))), [])

    def test_rejects_wrong_values(self):
        d = bytearray(make_table()); d[25 * SIZE + LAYOUT['stats']] ^= 0xFF   # Pikachu's first stat is wrong
        self.assertEqual(modkit.probe(bytes(d)), [])

class Edit(unittest.TestCase):
    def setUp(self):
        self.data = make_table(); self.lay = dict(modkit.probe(self.data)[0])

    def test_edit_changes_only_named_bytes(self):
        new, log = modkit.apply_edits(self.data, self.lay, {'25': {'hp': 100, 'types': [12, 9], 'ability1': 300}})
        diff = [i for i in range(len(new)) if new[i] != self.data[i]]
        self.assertEqual(len(new), len(self.data))
        base = 25 * SIZE
        self.assertTrue(all(base <= i < base + SIZE for i in diff))
        self.assertEqual(new[base + LAYOUT['stats']], 100)
        self.assertEqual(new[base + LAYOUT['types'] + 1], 9)
        self.assertEqual(int.from_bytes(new[base + LAYOUT['ability']:base + LAYOUT['ability'] + 2], 'little'), 300)
        self.assertEqual(len(log), 4)

    def test_rejects_bad_edits(self):
        for bad in ({'25': {'hp': 256}}, {'25': {'nope': 1}}, {'25': {'types': [1]}}, {'99999': {'hp': 1}}, {'25': {'hp': -1}}):
            with self.assertRaises(SystemExit): modkit.apply_edits(self.data, self.lay, bad)

class Cli(unittest.TestCase):
    def test_end_to_end(self):
        with tempfile.TemporaryDirectory() as t:
            rel = 'bin/pml/personal/personal_total.bin'
            p = os.path.join(t, 'RomFS', rel); os.makedirs(os.path.dirname(p))
            open(p, 'wb').write(make_table())
            lay = os.path.join(t, 'lay.json'); ed = os.path.join(t, 'ed.json'); out = os.path.join(t, 'mod'); z = os.path.join(t, 'm.zip')
            with open(ed, 'w', encoding='utf-8-sig') as f:   # with a byte order mark, as Notepad / PowerShell write it
                json.dump({'species': {'1': {'stats': [1, 2, 3, 4, 5, 6]}}}, f)
            run = lambda *a: modkit.sys.argv.__setitem__(slice(None), ['modkit'] + list(a)) or self._main()
            self.assertEqual(run('probe-personal', p, '--out', lay), 0)
            self.assertEqual(run('personal-edit', '--romfs', os.path.join(t, 'RomFS'), '--file', rel, '--layout', lay, '--edits', ed, '--out', out), 0)
            run('package', out, z)
            names = zipfile.ZipFile(z).namelist()
            self.assertIn('romfs/' + rel, names); self.assertIn('readme.txt', names)
            mod = zipfile.ZipFile(z).read('romfs/' + rel)
            self.assertEqual(bytes(mod[SIZE + LAYOUT['stats'] + i] for i in range(6)), bytes([1, 2, 3, 6, 4, 5]))   # hp atk def | spe spa spd
    def _main(self):
        try: modkit.main()
        except SystemExit as e: return e.code or 0
        return 0

    def test_package_prints_on_a_non_utf8_console(self):
        # Windows with output piped: stdout is cp1252, which has no "☰"; package used to crash there after writing the zip
        import io
        with tempfile.TemporaryDirectory() as t:
            os.makedirs(os.path.join(t, 'mod', 'romfs')); open(os.path.join(t, 'mod', 'romfs', 'f'), 'wb').close()
            buf = io.BytesIO(); old = sys.stdout; w = io.TextIOWrapper(buf, encoding='cp1252')
            sys.stdout = w
            try:
                modkit.sys.argv[:] = ['modkit', 'package', os.path.join(t, 'mod'), os.path.join(t, 'm.zip')]
                code = self._main(); w.flush(); out = buf.getvalue()
            finally: sys.stdout = old; w.detach()
            self.assertEqual(code, 0)
            self.assertIn(b'Mods > Import', out)

class RealShape(unittest.TestCase):
    # Synthetic data in the shape found in Shield 1.3.2's bin/pml/personal/personal_total.bin (offsets only, values made up apart from KNOWN):
    # 1192 records of 0xB0, no header, record N = species N, stats at 0 in the order hp atk def spe spa spd, types at 6, catch rate at 8, ability at 0x18
    def test_probe_finds_the_shield_layout(self):
        rnd = random.Random(7); size, count = 0xB0, 1192
        data = bytearray(rnd.randrange(256) for _ in range(size * count))
        for dex, (_, stats, types, catch, ab) in modkit.KNOWN.items():
            o = dex * size
            for i, st in enumerate(['hp', 'atk', 'def', 'spe', 'spa', 'spd']): data[o + i] = stats[modkit.STAT_NAMES.index(st)]
            data[o + 6], data[o + 7] = types; data[o + 8] = catch; data[o + 0x18:o + 0x1A] = ab.to_bytes(2, 'little')
        l = modkit.probe(bytes(data))[0]
        self.assertEqual((l['entry_size'], l['base'], l['index_shift'], l['count']), (size, 0, 0, count))
        self.assertEqual({k: v['offset'] for k, v in l['fields'].items()},
                         {'hp': 0, 'atk': 1, 'def': 2, 'spe': 3, 'spa': 4, 'spd': 5, 'type1': 6, 'type2': 7, 'catch_rate': 8, 'ability1': 0x18})

class Inspect(unittest.TestCase):
    def test_nso_module_id_and_inspect(self):
        with tempfile.TemporaryDirectory() as t:
            nso = os.path.join(t, 'main'); h = bytearray(0x100); h[:4] = b'NSO0'; h[0x40:0x50] = bytes(range(16))
            open(nso, 'wb').write(h)
            self.assertEqual(modkit.nso_module_id(nso), bytes(range(16)).hex())
            tsv = os.path.join(t, 'game.tsv'); open(tsv, 'w').write('build\tv1\t1.3.2\tmain=' + bytes(range(16)).hex() + '\tsdk=ff\n')
            self.assertEqual(modkit.profile_builds(tsv)['main'], bytes(range(16)).hex())
            r = os.path.join(t, 'RomFS', 'bin', 'pml', 'personal'); os.makedirs(r); open(os.path.join(r, 'personal_total.bin'), 'wb').write(b'x' * 10)
            rep = os.path.join(t, 'rep.json')
            a = modkit.argparse.Namespace(romfs=os.path.join(t, 'RomFS'), main=nso, profile=tsv, report=rep, show=10, max_hash=1 << 20)
            modkit.cmd_inspect(a)
            self.assertIn('bin/pml/personal/personal_total.bin', json.load(open(rep))['candidate_hashes'])

if __name__ == '__main__': unittest.main()
