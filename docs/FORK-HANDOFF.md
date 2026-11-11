# Pokeshield fork: handoff

State as of 2026-11-11 (after the upstream merge). Read this first when picking the work up in a new Claude Code session.

## What this is
`gkantelis28/Pokeshield` is a fork of **Galar Native** (formerly PKShield). It runs Pokémon Sword / Shield 1.3.2 natively on Android from the
player's own dump: the game's ARM64 code runs in-process and NVN graphics run on Vulkan. MIT licensed. The goal of this fork is modding the game:
data mods first, then a built-in **Nuzlocke mode** with many options in the in-game ☰ settings, and eventually **raising the species cap** to add
new Pokémon.

- **Upstream:** `simipoB/Galar-Native-Android` (MIT until 2026-11-10, **GPL-3.0-or-later** since; older code keeps its MIT notice in
  `LICENSE-MIT`). It went offline 2026-11-09 and came back 2026-11-11 with its history intact (the author said it was down for updates).
  The fork started as a copy of upstream at `be5b6d3` (2026-11-06) and **merged upstream `28dbd0b` on 2026-11-11**, which also linked the
  two histories. To update: `git remote add upstream https://github.com/simipoB/Galar-Native-Android` (once), then
  `git fetch upstream && git merge upstream/main`. Upstream has no `.github/`, so the fork's workflow is unaffected; check
  `scripts/build_apk_pc.sh` and `dm/android/tools/build_apk.py` for new build requirements after each merge.
- Branch: `ccr-7cb96f97-hmwl2e` is the only branch (so it's the default). No PR exists because there's no other branch to compare against.

## Done
- **APK builds on GitHub Actions.** `.github/workflows/build-apk.yml` runs `scripts/build_apk_pc.sh` on every push to `dm/`, `scripts/` or the
  workflow, or by hand. The APK is an artifact on the run page.
  - Signed with the repo secret `SIGNING_KEYSTORE_B64` (alias `debug`, passwords `android`). The certificate SHA-256 ends in `…37:BE:4A`.
    The owner holds the keystore privately. Never commit or print it.
  - versionCode = 100 + run number, so each build updates over the last. Package `com.nxnative.pokemonshield` (unchanged from upstream).
  - Run #2 (v102, the pre-merge code) built and is **installed on the owner's phone (a Samsung), and the game runs.** The merge's push
    starts the next build (v103+), which installs as an update over it.
- **modkit** (`modkit/modkit.py`, pure Python, tests in `modkit/tests`): `inspect` a RomFS; `probe-personal` discovers the species table's layout
  from known species values instead of assuming offsets; `personal-dump` / `personal-edit` / `package` make a mod zip for the app's
  Mods > Import. **Works on a real Shield 1.3.2 dump, and a mod built with it worked on the phone** (the Galar starters showed Dragon as a second type).
- **Nuzlocke mode** (`docs/NUZLOCKE.md` has the agreed options and what is built): milestones 1-2 are in code, not yet tried on the phone.
  `dm/android/app/jni/nuzlocke.c` reads the party and the boxes from the game's memory (PK8 records, no hooks), `Nuzlocke.java` runs the rules,
  `GameMenu.java` has the ☰ Nuzlocke tab. Build #5 (v105, milestone 1) compiled.
- Pushing from the PC works (Git Credential Manager has the owner's GitHub login since 2026-11-11).

## Now
The owner copied the app's data folder from the phone (build **v104** installed, game runs) to their PC at
`<user folder>\Downloads\pokemonshield_backup\com.nxnative.pokemonshield\` (the game is **Shield**; RomFS at `files\RomFS`, 50,494 files,
12.1 GB). The original ExeFS is not kept (setup deletes it), so modkit's `--main` doesn't apply. The repo is cloned at `D:\Pokeshield`, with
local work in `D:\Pokeshield\local\` (gitignored).

**2026-11-11: the species table works on the real dump.** `probe-personal` found the layout first time (see `modkit/README.md` and
`modkit/layouts/`), the CSV dump matches known values for species the probe didn't use, and a test mod (`local/StartersDragonTest.zip`: the nine
Galar starter-line records get Dragon as type 2, exactly 9 bytes changed) was built for the owner to import. **Not yet confirmed on the phone.**
Also fixed for Windows: `package` crashed printing "☰" when output was piped, and JSON written by Notepad / PowerShell (byte order mark) was rejected.

`bin/pml` also holds: `evolution/evo_NNN.bin` (1192 files of 72 bytes, one per personal record), `waza_oboe/wazaoboe_total.bin` (level-up
learnsets, 309,920 B = 1192 x 260), `tamagowaza/` (egg moves, 1251 files), `waza/wazaNNNN.wazabin` (827 moves, 136 B each), `item/item.dat`,
`grow_table/` (8 EXP curves).

The ground missing in the overworld after v104 was the fast setup's background shaders (upstream #173); it came back once they finished.
Upstream fixed the order in #174 (`7d6df06`), not merged into the fork yet.

Next steps, in order:
1. The owner tests Nuzlocke on the phone (they asked to batch the testing): Start a run in ☰ Nuzlocke; "Party watch" should say "party found";
   then a faint, a catch, a catch sent to the boxes. If the party isn't found, read the `[nuzlocke]` lines in `Download/PokemonShield-log.txt`.
2. Nuzlocke rules that need the game's code (disassemble `<game folder>/patched/main.patched.bin`; gate addresses on the build ID): the current
   area (area notices, failed encounters), battle state (items in battle, failed encounters), moving the dead to a Graveyard box, blocking EXP
   over the cap, Set mode, Gym exits. Badges are counted by hand in the tab until the game's badge flags are found.
3. `inspect`'s candidate list is too noisy on the real RomFS (32,767 matches, mostly `item_NNNN.bntx` icons): restrict it to data extensions.
4. Other tables (learnsets, moves, encounters, trainers), then the species-cap research described in `modkit/README.md`.

## Rules
- **Never commit anything from the game**: no RomFS files, dumps, keys, extracted or edited game files, CSV dumps of game tables, or mod zips. Keep
  all of that under `local/` or the copied app folder (both gitignored). Layout JSONs (offsets only) and code are fine to commit. The repo is
  **public**, which makes this matter more (the owner was advised to make it private).
- Addresses inside the game's code are valid for 1.3.2 only: gate them on the build ID (CONTRIBUTING.md). The profile's `main` build ID is in
  `dm/games/01008DB008C2C000/game.tsv`.
- Test what can be tested locally (`python -m unittest discover -s modkit/tests`). Say plainly what has only been checked on synthetic data, and
  what has run on the phone.
- The owner is new to this toolchain and on a phone part of the time: give step-by-step instructions with exact paths and commands, and explain
  results in plain words. They want thorough reasoning, not short answers.
