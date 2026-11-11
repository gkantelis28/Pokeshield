# Nuzlocke mode: design

Status: **milestones 1-3 written, not yet run on a phone (2026-11-11).** The ☰ Nuzlocke tab (Sword / Shield only) has the presets and every option
below, each card tagged with what it does so far ("Partly", "Coming later"); a run locks them. Rules are enforced by telling the player (notices, the run log,
a reminder every 2 minutes), not yet by stopping the game. Working in code:
- faint = dead (you move or release the Pokémon yourself for now); wipe detection (ends the run, or just logs); status line, death message, run log, Graveyard.
- every new Pokémon after the run starts (party or boxes): its area is its met location; a second catch in a used area is flagged, with the shiny clause, the
  dupes clause (species or evolution line), raids (met location "Pokémon Den" / Max Lair: count, free, not allowed, or the only Wild Area source), hatched eggs
  (the only gifts told apart so far), the Wild Area as one area, legendaries not allowed, and a nickname reminder. A list of the areas used is in the tab.
- level caps and "the next Gym Leader's" party size from a badge counter the player sets in the tab (− / +): reminders when over. Caps 20 24 27 36 38 42 46 48,
  then 65; team sizes 2 3 3 4 4 4 4 4, then 6 (public figures, the same in both games; check them against your game).
- shinies not allowed in the party, legendaries box only: reminders.
- **milestone 3 (Shield 1.3.2 only, gated on main's build ID):** the badges are read from the game (the counter in the tab then says "Read from the game" and
  has no − / +; a new badge is logged, with a notice of the new cap), and "Battle style: Locked to Set" holds the game's own Battle Style option on Set (the
  engine sets it back each second when it finds Switch). Other builds (Sword, other versions): badges counted by hand, no Set lock. How the two game objects
  were found is below ("Found in the game's code").
Not yet: failed encounters (needs the game's battle state), gifts other than eggs, which den a raid was at, weather in the Wild Area, the current area (notices on
entering), and everything that changes the game itself (moving the dead to a box, blocking EXP / items / Gym exits, Set mode, Exp. Share).
Area names, species names and evolution lines are read on the phone from the player's RomFS (`bin/message/<language>/script/place_name.dat`, where line N is
met location N; `common/monsname.dat`; `bin/pml/evolution/evo_NNN.bin`, 9 entries of 8 bytes). Those formats were checked against a real Shield 1.3.2 dump.

How the party is read (`dm/android/app/jni/nuzlocke.c`): no hooks and no code addresses (so no build-ID gate). The game keeps each Pokémon in the series'
documented PK8 form (what save editors read), encrypted but self-checking, so the engine scans the game's memory for valid party-form records, takes the
longest run 0x158 apart as the party, and re-reads them every second; a record of a party Pokémon whose HP goes from above 0 to 0 is a faint. Known limits:
a faint revived within the same battle is only caught if that battle's copy was picked up by a rescan (every 60 s); the first run will show in `log.txt`
(lines starting `[nuzlocke]`) whether the party is found at all. State: `<game folder>/nuzlocke/run.properties` and `log.txt` (one run per game folder,
not per save yet). Texts are English only so far (missing translations fall back to English).
The owner went over the list on 2026-11-11 and added five rules (marked *new*). **Every rule beyond the basics is its own setting and optional**:
the new ones are off in every preset and only turn on when picked (which makes the run Custom).

## Where it lives
- A new **Nuzlocke** tab in the in-game settings panel (☰), next to Mods and Saves, built with the panel's existing chip rows (`GameMenu.choice`).
- Rules are saved per game in `env.txt` like the other settings (keys `DM_NZ_*`). The run's state (routes used, deaths, the log) is saved per save
  file in `<game folder>/nuzlocke/`, so exporting / importing a save can carry its run with it.
- **Rules lock when a run starts.** Changing them later means "End run" or "Start a new run" (a confirmation, and it is written in the run log), so a
  run can't quietly get easier halfway through.
- Like other mods, Nuzlocke is **off for local play and online rooms**.

## Presets
| Preset | What it sets |
| --- | --- |
| Classic | Faint = dead, first encounter per area, nicknames, everything else off |
| Standard (default) | Classic + dupes clause (evolution line) + shiny clause + gifts count as the area's encounter |
| Hardcore | Standard + level caps (hard) + no items in battle + no Max Raids + wipe ends the run |
| Custom | Whatever the options below are set to; picking an option on a preset turns it into Custom |

## Options
### Core
| Option | Choices (default first) |
| --- | --- |
| Nuzlocke | Off · On |
| When a Pokémon faints | Dead: move it to the Graveyard box · Dead: release it (asks to confirm the rule at run start) · Dead: keep it, but it can't be put in the party |
| First encounter only | Per area · Off (catch anything; deaths still count) |
| The Wild Area counts as | One area per zone · One area in total · One area per zone and weather |
| Max Raid Battles | Count as the area's encounter · Free (don't use up the area) · Not allowed · *new:* The only encounter source in the Wild Area (wild catches there are blocked) |
| Gifts and fixed encounters (starter, fossils, gift Pokémon, legendaries) | Count as the area's encounter · Free · Not allowed |
| Failed encounters (the wild one fled, fainted, or you ran away) | Use up the area · Retry allowed |
| Nicknames | Required (asks at every catch) · Optional |

### Clauses
| Option | Choices |
| --- | --- |
| Dupes clause | Evolution line · Species · Off |
| Shiny clause (shinies are always catchable, and don't use up the area) | On · Off |
| Shiny clause also lets a shiny join the party | On · Off |
| Legendary / Mythical | Allowed · Box only · Not allowed |

### Difficulty
| Option | Choices |
| --- | --- |
| Level caps (the next Gym Leader's ace) | Off · Soft (no EXP above the cap) · Hard (no EXP, and a Pokémon above it can't enter battle) |
| Exp. Share (always on in Sword / Shield) | Game default · Off |
| Exp. Candy | Allowed · Up to the level cap · Not allowed |
| Items in battle | Allowed · Limited to N per battle (1-5) · Not allowed · *new:* Only what the enemy trainer carries (the items in that trainer's data, as many times as they have them; none in wild battles) |
| *new:* Battle style | Game default · Locked to Set (the game's own Options setting, held on Set for the run) |
| *new:* Gyms | Free to leave · Can't leave until the Gym Leader is beaten (the exit is blocked, with a message) |
| Held items | Allowed · Berries only · Not allowed |
| Healing outside battle (Pokémon Centers, healing items, camp curry) | Allowed · Limited per area · Not allowed |
| Dynamax / Gigantamax | Allowed · Gym battles only · Not allowed |
| Party size | 6 · 5 · 4 · 3 · 2 · 1 · *new:* Match the next Gym Leader (their team size) |

### End of the run
| Option | Choices |
| --- | --- |
| Whole party fainted (wipe) | The run ends (the save stays playable, marked "run failed") · Lose a "life" (1-3 lives) · Just logged |
| Second chances | None · One revive token per badge |
| After the Champion | The run ends (marked "won") · The rules stay on |

### Display
| Option | Choices |
| --- | --- |
| Status line in the corner | Deaths and areas left · Off |
| Death message | Full screen, with the Pokémon's nickname · Short notice · Off |
| Area notice on entering (whether its encounter is still available) | On · Off |
| Run log page in ☰ (catches, deaths, badges, with dates) | Always on; export as a text file to Download |

## How it can be enforced
Each rule is built at the lowest level that works, and the settings page labels rules that only warn so nothing claims to be enforced when it isn't.

1. **Tracking (app only):** the run log, area list, death count and status line. Needs the game's state readable: the party, boxes, current area
   ID, and badges.
2. **Automatic actions (hooks):** a faint during battle marks the Pokémon dead, and it goes to the Graveyard box after the battle. A catch in a used
   area is flagged. Needs hooks on the game's own functions for faint, catch, encounter start and end, and area change.
3. **Blocking (hooks that return early):** stopping a Poké Ball throw in a used area, item use in battle, a revive on a dead Pokémon, EXP over the
   cap, a dead Pokémon being moved to the party. The most work per rule.

Order of work: tracking, then faint → dead, then first encounter, then level caps, then the rest.

## What's needed from the game, and how it is found
All of these are addresses inside the 1.3.2 `main` and are valid for that build only. The code checks the build ID before installing any hook, as
CONTRIBUTING.md requires, and a mismatch turns Nuzlocke off with a message. They are found by disassembling the player's own `main` with the tools
in `dm/tools/` (`nso2elf.py`, `sdis.py`). No game code is committed: only addresses and the byte patterns used to check them.

- The party, the boxes and the Pokémon record layout (per-Pokémon HP, species, nickname, level). Save-editing tools already document most of the record layout.
- The current area / location ID, plus a table mapping IDs to names for the area list.
- The functions that run when a Pokémon faints, when a wild battle starts and ends (with its result), on a catch, and on EXP gain.
- The trainer and Gym Leader data, for the level caps and "match the next Gym Leader" (team sizes), and each trainer's battle items for "only
  what the enemy trainer carries" (from the RomFS, read with `modkit`), plus which trainer the current battle is against.
- The Battle Style option in the game's settings (to hold it on Set), the Gym interiors' area IDs and the exit warps out of them (to block leaving),
  and the item-use path in battle (to count and limit items).

The game's code is available for this: the app keeps its unpacked, patched copy at `<game folder>/patched/main.patched.bin` (setup deletes only
the original ExeFS). Check first that addresses found in it match the original 1.3.2 layout before relying on them.

Difficulty of the *new* rules: Set mode, raids-only and Gym team size are small additions to existing options; leaving a Gym needs the exit
blocked (blocking tier); trainer-item limits are the most work (trainer item data, the current opponent, and a check on every item use), so they come last.

## Found in the game's code (Shield 1.3.2, main build `a16802625e7826bf83b6f9708e475b91`)
Found in `<game folder>/patched/main.patched.bin` (offsets from main's start; the patched copy keeps the original layout for these). The save is a list of
blocks keyed by a 32-bit hash (save editors document the keys); the game copies the blocks it uses into objects of its own on load and back on save, so the
live values are in those objects, not in the save data:
- **Misc** (key `0x1b882b09`): constructor `0x14386a0` (heap, 0x298 bytes), load `0x14389d0`, save `0x1438b30`. Class tables written at +0 / +0x48 / +0x50:
  `0x25a9968` / `0x25a99d0` / `0x25a9a10` (base + offset at run time: the class-table slot is the relocated GOT entry `0x262a9f0` → `0x25a9958`, + 0x10 / 0x78 / 0xb8).
  The block's 0x21c bytes are at +0x60: byte 0 = badges, one bit per Gym; money at +4.
- **Options**: constructor `0x140d120`, load `0x140d730`, save `0x140d410` (one block per setting: `0x92eb0306` text speed, `0xccc153cd` battle effects,
  `0x765468c3` battle style, ...). Class tables `0x25a8780` / `0x25a87e0` / `0x25a8820` (GOT `0x262a818` → `0x25a8770`). One word at +0x60, the settings as
  bits: 0-1 text speed, 2 battle effects, **3 battle style (0 Switch, the constructor's default; 1 Set)**.
The engine finds both objects by those three pointers during its memory scan (`nuzlocke.c`, `k_rows`); no game code is changed. Sword's main has its own build
ID and wasn't checked (no dump), so Sword gets no row yet.

## Decided (2026-11-11)
- When the Graveyard box is full, the dead go to another Graveyard box (Graveyard 2, 3, ...).
- Standard stays the default preset.

## Open questions
- Release on death: the release itself needs a hook on the game's box code.
- When every box is full, there is no box left for a new Graveyard (warn, and keep the dead Pokémon out of the party until there's space?).
- The Wild Area options need the zone IDs mapped, which comes from the RomFS once it can be read.
