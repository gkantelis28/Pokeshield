# modkit

Tools for making Galar Native mods from your own Sword / Shield dump. Pure Python 3, no dependencies. Nothing from the game is stored here:
every command reads your extracted files and writes only to the folder you name.

**Status: species table works on a real Shield 1.3.2 dump (2026-11-11).** The species-table reader finds its layout by looking for species with
well-known values (Bulbasaur, Pikachu, Charizard, Ditto, Snorlax, Mewtwo), so it does not depend on offsets remembered from elsewhere. On Shield
1.3.2, `bin/pml/personal/personal_total.bin` is 1192 records of 0xB0 bytes with no header; record N is national dex N (0 is empty), forms
follow from 899. Stats are at 0-5 in the order hp atk def spe spa spd, types at 6-7, catch rate at 8, the first ability at 0x18 (2 bytes). The
probed layout is in `layouts/shield-1.3.2-personal_total.json` (offsets and the file's hash only), and you can pass it as `--layout` directly.
Spot checks on species the probe did not use (Venusaur, Gengar, Garchomp, the Galar starters, Dragapult, Eternatus, Calyrex) all matched.
Sword is untested (probably the same layout; run `probe-personal` and compare).

## Getting the files
If the game is set up in the app, the RomFS is already unpacked (the 1.3.2 update merged in): `setup_game.py` moves it to `<game folder>/RomFS`, where
the game folder is `Android/data/com.nxnative.pokemonshield/files/` for the first game set up, or `files/<title ID>/` for the second (Shield
`01008DB008C2C000`, Sword `0100ABF008968000`). Setup deletes the original ExeFS afterwards, so leave out `--main`: a game that runs in the app is 1.3.2 already.

Android 11 and later stop most file managers from opening `Android/data`. Ways in:
- **USB to a PC** (Windows Explorer / macOS Android File Transfer): `Android/data` is usually visible there. Copy `RomFS/bin/pml` (the species data; small),
  or the whole `RomFS` to run `inspect` on everything.
- `adb pull /sdcard/Android/data/com.nxnative.pokemonshield/files/RomFS/bin/pml`.
- Without a dump on the phone already: unpack the NSP on a PC with your keys (hactool, or `dm/tools/nx_extract`, which merges the update).

## Steps
```sh
# 1. What is in there? --report writes names/sizes/hashes only (no contents) and is safe to share.
python3 modkit/modkit.py inspect /path/to/RomFS --main /path/to/ExeFS/main \
    --profile dm/games/01008DB008C2C000/game.tsv --report inspect.json

# 2. Find the species table (try the files `inspect` lists under "personal") and learn its layout.
python3 modkit/modkit.py probe-personal /path/to/RomFS/<table file> --out layout.json

# 3. Read it.
python3 modkit/modkit.py personal-dump /path/to/RomFS/<table file> --layout layout.json --csv species.csv

# 4. Change it. edits.json: {"species": {"25": {"hp": 100, "types": [12, 9]}, "1": {"stats": [80,80,80,80,80,80]}}}
python3 modkit/modkit.py personal-edit --romfs /path/to/RomFS --file <path inside RomFS> \
    --layout layout.json --edits edits.json --out mymod --name "My mod" --dry-run
#    (drop --dry-run to write mymod/romfs/<path>)

# 5. Zip it, then on the phone: Settings (☰) > Mods > Import a mod (.zip)
python3 modkit/modkit.py package mymod mymod.zip
```
Field names come from the layout (`hp atk def spa spd spe type1 type2 catch_rate ability1`); `stats` is shorthand for the six stats in that order and
`types` for `[type1, type2]`. Types use the series' numbering (0 Normal, 1 Fighting, 2 Flying, 3 Poison, 4 Ground, 5 Rock, 6 Bug, 7 Ghost,
8 Steel, 9 Fire, 10 Water, 11 Grass, 12 Electric, 13 Psychic, 14 Ice, 15 Dragon, 16 Dark, 17 Fairy). Mods must be off for local play and online rooms.

Tests: `python3 -I -m unittest discover -s modkit/tests`

## Not done yet
- Archives (`.gfpak`), FlatBuffer tables and the game's text files. If the species table turns out to live in one of those, `probe-personal` says so and
  that becomes the next job.
- Learnsets, evolutions, moves, encounters, trainers.
- Code patches (`.pchtxt`): need disassembly of your `main`; `dm/tools/` already has `nso.py`, `nso2elf.py`, `sdis.py` and `builtin_mods.py` to build on.

## Roadmap for the bigger ideas
**Raising the species cap.** The limit is spread across the data and the code: the size of the personal / learnset / evolution / dex tables, the
Pokédex and caught flags in the save file, string tables for names and dex text, model and animation lookups, and fixed-size arrays or index checks
in the executable. Plan: (1) map every table keyed by species from the real files; (2) find the code that reads each count, by tracing loads of the
table sizes in `main`; (3) first prove the approach by repurposing an existing slot end to end; (4) only then extend tables, with a save-format
decision made up front (extending the dex bitfields changes the save layout, so saves need a migration or a separate save slot).

**Nuzlocke mode.** Best built app-side rather than as a game patch. The engine shares the game's process, so it can watch party and box state and
battle results through a few hooked functions: mark a Pokémon dead when it faints and move it to a graveyard, allow only the first catch per route,
enforce a level cap, and offer a species clause. It needs addresses for 1.3.2 (gated on the build ID, as CONTRIBUTING.md requires) and a toggle
under ☰. State would be stored per save, next to it.
