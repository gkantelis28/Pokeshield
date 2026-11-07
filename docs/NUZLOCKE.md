# Nuzlocke mode: design

Status: **design only, nothing built yet.** This page lists the options and how each would work, so they can be agreed before any code.

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
| Max Raid Battles | Count as the area's encounter · Free (don't use up the area) · Not allowed |
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
| Items in battle | Allowed · Limited to N per battle (1-5) · Not allowed |
| Held items | Allowed · Berries only · Not allowed |
| Healing outside battle (Pokémon Centers, healing items, camp curry) | Allowed · Limited per area · Not allowed |
| Dynamax / Gigantamax | Allowed · Gym battles only · Not allowed |
| Party size | 6 · 5 · 4 · 3 · 2 · 1 |

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
- The trainer and Gym Leader data, for the level caps (from the RomFS, read with `modkit`).

## Open questions
- Release on death: the release itself needs a hook on the game's box code. Moving to a Graveyard box needs free box space, so what happens when it's full?
- The Wild Area options need the zone IDs mapped, which comes from the RomFS once it can be read.
