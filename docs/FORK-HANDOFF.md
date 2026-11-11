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
  Mods > Import. **Tested only on synthetic data so far.**
- `docs/NUZLOCKE.md`: the agreed shape of Nuzlocke mode (presets, options, enforcement tiers). Design only, no code yet; the owner hasn't
  reviewed the option list yet.

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

Next steps, in order:
1. The owner imports the test mod and reports back (a starter's summary should show Dragon as a second type). If it doesn't show, check the app
   log for the `fs mount rom ... (overlay first)` line before suspecting the data.
2. `inspect`'s candidate list is too noisy on the real RomFS (32,767 matches, mostly `item_NNNN.bntx` icons): restrict it to data extensions.
3. Then: other tables (learnsets, evolutions, moves, encounters, trainers), text files, then Nuzlocke (go over the option list with the owner
   first), then the species-cap research described in `modkit/README.md`.

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
