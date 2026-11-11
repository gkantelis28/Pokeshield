// Nuzlocke mode (docs/NUZLOCKE.md), Pokémon Sword / Shield only. The rules are settings (DM_NZ_* in env.txt, the ☰ Nuzlocke tab); starting a run copies them into the
// run, where they stay locked until it ends. The run lives in <game folder>/nuzlocke/: run.properties (state, the locked rules, badges, the dead, the Pokémon seen, the
// areas used) and log.txt.
// While a run is on, a thread asks the engine (app/jni/nuzlocke.c) what it sees once a second: the party, every Pokémon of the player's it hasn't reported before
// (boxes included), and faints. A faint marks that Pokémon dead (by its encryption constant, which never changes: a new nickname or an evolution keeps it dead). A new
// Pokémon is a catch (or a gift, a hatched egg, a raid): its met location is its area, and the run's rules are checked against it. Rules are enforced by telling
// the player (a notice, the run log, reminders), not yet by stopping the game; each rule's card says how far it goes (ON / PARTLY / LATER).
// Area and species names and evolution lines come from the player's own RomFS (bin/message/<language>/script/place_name.dat, common/monsname.dat,
// bin/pml/evolution), read at run time: none of the game's text or data is in the app.
package com.nxnative;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.PopupWindow;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public final class Nuzlocke {
    private Nuzlocke() { }
    private static native String nativePoll();
    private static native void nativeHoldSet(boolean on);

    // ---------- the rules ----------
    static final int ON = 0, PARTLY = 1, LATER = 2;
    static final class Opt {
        final String group, key, title; final int status; final String[][] ch;   // ch: { value, chip label, what it does }; "" is the Standard preset's choice
        Opt(String group, String key, String title, int status, String[][] ch) { this.group = group; this.key = key; this.title = title; this.status = status; this.ch = ch; }
    }
    static final Opt[] OPTS = {
        new Opt("Core", "DM_NZ_FAINT", "When a Pokémon faints", PARTLY, new String[][] {
            { "", "Graveyard box", "Dead: it goes to a Graveyard box (the next one when that is full). For now the faint is found and logged, and you move it yourself" },
            { "release", "Release", "Dead: release it. For now the faint is found and logged, and you release it yourself" },
            { "bench", "Keep, benched", "Dead: it stays, but may not be in the party. For now you're reminded while it is" } }),
        new Opt("Core", "DM_NZ_FIRST", "First encounter only", PARTLY, new String[][] {
            { "", "Per area", "Only the first Pokémon in each area may be caught. For now catches are checked (a second catch in an area is flagged); encounters you didn't catch aren't seen yet" },
            { "off", "Off", "Catch anything (deaths still count)" } }),
        new Opt("Core", "DM_NZ_WILD", "The Wild Area counts as", PARTLY, new String[][] {
            { "", "Per zone", "One area per zone" }, { "one", "One area", "The whole Wild Area is one area" },
            { "weather", "Zone + weather", "One area per zone and weather (coming later: per zone for now)" } }),
        new Opt("Core", "DM_NZ_RAIDS", "Max Raid Battles", PARTLY, new String[][] {
            { "", "Count", "A raid catch uses up an area (all dens count as one area, \"Pokémon Den\", for now)" }, { "free", "Free", "Raid catches don't use up an area" },
            { "none", "Not allowed", "A raid catch is flagged" },
            { "only", "Only source", "In the Wild Area, raids are the only encounters: a wild catch there is flagged" } }),
        new Opt("Core", "DM_NZ_GIFTS", "Gifts and fixed encounters", PARTLY, new String[][] {
            { "", "Count", "Gifts use up their area. For now only hatched eggs are told apart from catches" }, { "free", "Free", "Hatched eggs don't use up an area" },
            { "none", "Not allowed", "A hatched egg is flagged" } }),
        new Opt("Core", "DM_NZ_FAILED", "Failed encounters", LATER, new String[][] {
            { "", "Use up the area", "The wild one fled or fainted, or you ran: the area is used up" }, { "retry", "Retry allowed", "" } }),
        new Opt("Core", "DM_NZ_NICK", "Nicknames", PARTLY, new String[][] {
            { "", "Required", "A catch without a nickname is reminded about until it has one (rename it from the box)" }, { "optional", "Optional", "" } }),
        new Opt("Clauses", "DM_NZ_DUPES", "Dupes clause", PARTLY, new String[][] {
            { "", "Evolution line", "A dupe doesn't use up its area, so you may catch the next one there; catching a dupe itself is flagged" },
            { "species", "Species", "The same, by species" }, { "off", "Off", "" } }),
        new Opt("Clauses", "DM_NZ_SHINY", "Shiny clause", ON, new String[][] {
            { "", "On", "Shinies may always be caught and don't use up the area" }, { "off", "Off", "" } }),
        new Opt("Clauses", "DM_NZ_SHINY_PARTY", "Shinies may join the party", PARTLY, new String[][] { { "", "Yes", "" }, { "off", "No", "Reminded while a shiny is in the party" } }),
        new Opt("Clauses", "DM_NZ_LEGEND", "Legendary / Mythical", PARTLY, new String[][] {
            { "", "Allowed", "" }, { "box", "Box only", "Reminded while one is in the party" }, { "none", "Not allowed", "A catch is flagged" } }),
        new Opt("Difficulty", "DM_NZ_LEVELCAP", "Level caps", PARTLY, new String[][] {
            { "", "Off", "" }, { "soft", "Soft", "No EXP above the next Gym Leader's ace. For now you're reminded when a Pokémon is over the cap (badges are read from the game on Shield 1.3.2; elsewhere count them in this tab)" },
            { "hard", "Hard", "No EXP above the cap, and a Pokémon above it can't battle. For now you're reminded when one is over it" } }),
        new Opt("Difficulty", "DM_NZ_EXPSHARE", "Exp. Share", LATER, new String[][] { { "", "Game default", "Always on, as in the game" }, { "off", "Off", "" } }),
        new Opt("Difficulty", "DM_NZ_CANDY", "Exp. Candy", LATER, new String[][] { { "", "Allowed", "" }, { "cap", "Up to the cap", "" }, { "none", "Not allowed", "" } }),
        new Opt("Difficulty", "DM_NZ_ITEMS", "Items in battle", LATER, new String[][] {
            { "", "Allowed", "" }, { "1", "1 per battle", "" }, { "3", "3 per battle", "" }, { "5", "5 per battle", "" }, { "none", "Not allowed", "" },
            { "trainer", "Trainer's items", "Only what the enemy trainer carries, as many as they have; none in wild battles" } }),
        new Opt("Difficulty", "DM_NZ_HELD", "Held items", LATER, new String[][] { { "", "Allowed", "" }, { "berries", "Berries only", "" }, { "none", "Not allowed", "" } }),
        new Opt("Difficulty", "DM_NZ_HEAL", "Healing outside battle", LATER, new String[][] {
            { "", "Allowed", "Pokémon Centers, healing items, camp curry" }, { "limited", "Limited per area", "" }, { "none", "Not allowed", "" } }),
        new Opt("Difficulty", "DM_NZ_DYNAMAX", "Dynamax / Gigantamax", LATER, new String[][] { { "", "Allowed", "" }, { "gyms", "Gyms only", "" }, { "none", "Not allowed", "" } }),
        new Opt("Difficulty", "DM_NZ_STYLE", "Battle style", ON, new String[][] { { "", "Game default", "" },
            { "set", "Locked to Set", "The game's own Battle Style option is held on Set for the run (switching it back in Options doesn't stay). Shield 1.3.2 only so far" } }),
        new Opt("Difficulty", "DM_NZ_GYMS", "Gyms", LATER, new String[][] {
            { "", "Free to leave", "" }, { "locked", "Can't leave", "A Gym can't be left until its Leader is beaten" } }),
        new Opt("Difficulty", "DM_NZ_PARTY", "Party size", PARTLY, new String[][] {
            { "", "6", "" }, { "5", "5", "Reminded while the party is bigger" }, { "4", "4", "Reminded while the party is bigger" }, { "3", "3", "Reminded while the party is bigger" },
            { "2", "2", "Reminded while the party is bigger" }, { "1", "1", "Reminded while the party is bigger" },
            { "gym", "Next Gym Leader's", "As many as the next Gym Leader has (by your badges); reminded while the party is bigger" } }),
        new Opt("End of the run", "DM_NZ_WIPE", "Whole party fainted (wipe)", PARTLY, new String[][] {
            { "", "Run ends", "The run ends, marked failed (the save stays playable)" }, { "lives", "Lives", "Lose a life (coming later: logged for now)" }, { "log", "Just logged", "" } }),
        new Opt("End of the run", "DM_NZ_SECOND", "Second chances", LATER, new String[][] { { "", "None", "" }, { "token", "Revive token per badge", "" } }),
        new Opt("End of the run", "DM_NZ_CHAMP", "After the Champion", LATER, new String[][] { { "", "Run ends (won)", "" }, { "on", "Rules stay on", "" } }),
        new Opt("Display", "DM_NZ_STATUSLINE", "Status line in the corner", ON, new String[][] { { "", "On", "Deaths so far (and the level cap), at the top right" }, { "off", "Off", "" } }),
        new Opt("Display", "DM_NZ_DEATHMSG", "Death message", ON, new String[][] {
            { "", "Full", "A big notice with the Pokémon's nickname" }, { "short", "Short", "A short notice at the top" }, { "off", "Off", "" } }),
        new Opt("Display", "DM_NZ_AREA", "Area notice on entering", LATER, new String[][] { { "", "On", "Whether the area's encounter is still available" }, { "off", "Off", "" } }),
    };
    static final String[][] PRESETS = { { "", "Standard", "Classic, plus the dupes clause (evolution line), the shiny clause, and gifts counting as the area's encounter" },
        { "classic", "Classic", "Faint = dead, first encounter per area, nicknames; everything else off" },
        { "hardcore", "Hardcore", "Standard, plus hard level caps, no items in battle, no Max Raids, and a wipe ends the run" },
        { "custom", "Custom", "Your own mix (picking any rule below makes it Custom)" } };
    private static Map<String, String> preset(String p) {
        Map<String, String> m = new HashMap<>(); for (Opt o : OPTS) m.put(o.key, "");
        if (p.equals("classic")) { m.put("DM_NZ_DUPES", "off"); m.put("DM_NZ_SHINY", "off"); m.put("DM_NZ_SHINY_PARTY", "off"); m.put("DM_NZ_GIFTS", "free"); }
        else if (p.equals("hardcore")) { m.put("DM_NZ_LEVELCAP", "hard"); m.put("DM_NZ_ITEMS", "none"); m.put("DM_NZ_RAIDS", "none"); m.put("DM_NZ_WIPE", ""); }
        return m;
    }
    // the menu changed a rule (only while no run is on): a preset sets every rule; any single rule makes it Custom
    static void setOption(Context c, String key, String value) {
        if (active(c)) return;
        if (key.equals("DM_NZ_PRESET")) { GameSettings.set(c, key, value); if (!value.equals("custom")) for (Map.Entry<String, String> e : preset(value).entrySet()) GameSettings.set(c, e.getKey(), e.getValue()); return; }
        GameSettings.set(c, key, value); GameSettings.set(c, "DM_NZ_PRESET", "custom");
    }
    static boolean forThisGame(Context c) { String id = GameSettings.game(c)[0].toUpperCase(Locale.ROOT); return id.startsWith("0100ABF008968000") || id.startsWith("01008DB008C2C000"); }
    static String label(String[][] ch, String v) { for (String[] o : ch) if (o[0].equals(v)) return o[1]; return ch[0][1]; }

    // ---------- facts about the games (public, the same in Sword and Shield): each Gym Leader's ace level and team size, by badges held; the legendaries ----------
    // After the eighth badge the cap is the Champion's ace. The caps are the usual Nuzlocke ones from the Gym Leaders' highest-levelled Pokémon
    static final int[] CAPS = { 20, 24, 27, 36, 38, 42, 46, 48, 65 }, TEAM = { 2, 3, 3, 4, 4, 4, 4, 4, 6 };
    private static final Set<Integer> LEGEND = new HashSet<>(Arrays.asList(144, 145, 146, 150, 151, 243, 244, 245, 249, 250, 251, 377, 378, 379, 380, 381, 382, 383, 384,
        385, 386, 480, 481, 482, 483, 484, 485, 486, 487, 488, 489, 490, 491, 492, 493, 494, 638, 639, 640, 641, 642, 643, 644, 645, 646, 647, 648, 649, 716, 717, 718,
        719, 720, 721, 772, 773, 785, 786, 787, 788, 789, 790, 791, 792, 800, 801, 802, 807, 808, 809, 888, 889, 890, 891, 892, 893, 894, 895, 896, 897, 898));
    static final int DEN = 162, MAX_LAIR = 244, WILD_FIRST = 122, WILD_LAST = 156;   // met locations: Pokémon Den (raids), Max Lair, the Wild Area's zones and station
    static int badges(Properties p) { try { return Math.max(0, Math.min(8, Integer.parseInt(p.getProperty("badges", "0")))); } catch (NumberFormatException e) { return 0; } }

    // ---------- the game's own text and data, from the player's RomFS ----------
    private static String[] places = new String[0], species = new String[0]; private static int[] base; private static String dataLang;
    // a Sword / Shield text file: a header (line count at +2, the section at +0xc), then per line an offset and a length; each line's UTF-16 is XORed with a key that
    // starts at 0x7c89 + 0x2983 x line and turns left by 3 bits each character; 0x10 starts a variable (a length, then that many words), skipped here
    static String[] text(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] d = new byte[(int) r.length()]; r.readFully(d);
            ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
            int n = b.getShort(2) & 0xffff, sec = b.getInt(12); String[] out = new String[n]; int key = 0x7c89;
            for (int i = 0; i < n; i++) {
                int off = b.getInt(sec + 4 + i * 8), len = b.getShort(sec + 8 + i * 8) & 0xffff, k = key, skip = 0; boolean end = false; StringBuilder sb = new StringBuilder();
                for (int j = 0; j < len; j++) {
                    int c = (b.getShort(sec + off + j * 2) & 0xffff) ^ k; k = ((k << 3) | (k >>> 13)) & 0xffff;
                    if (end) continue;
                    if (skip == -1) { skip = c; continue; }
                    if (skip > 0) { skip--; continue; }
                    if (c == 0x10) { skip = -1; continue; }
                    if (c == 0) { end = true; continue; }
                    sb.append((char) c);
                }
                out[i] = sb.toString(); key = (key + 0x2983) & 0xffff;
            }
            return out;
        } catch (IOException | RuntimeException e) { return new String[0]; }
    }
    private static String langDir(Context c) { String l = T.lang(c); return l.equals("es") ? "Spanish" : l.equals("fr") ? "French" : l.equals("ja") ? "JPN" : "English"; }
    private static synchronized void loadData(Context c) {
        String lang = langDir(c); if (lang.equals(dataLang)) return; dataLang = lang;
        File rom = new File(GameSettings.dir(c), "RomFS/bin"), msg = new File(rom, "message/" + lang);
        places = text(new File(msg, "script/place_name.dat")); species = text(new File(msg, "common/monsname.dat"));
        // evolution lines: each evo_NNN.bin is 9 entries of 8 bytes (method, argument, species it becomes, form, level); base[s] = the first species of s's line
        int[] pre = new int[899];
        for (int s = 1; s < 899; s++) {
            File f = new File(rom, String.format(Locale.ROOT, "pml/evolution/evo_%03d.bin", s)); if (!f.exists()) continue;
            try (RandomAccessFile r = new RandomAccessFile(f, "r")) { byte[] d = new byte[(int) Math.min(72, r.length())]; r.readFully(d);
                for (int e = 0; e + 8 <= d.length; e += 8) { int method = (d[e] & 0xff) | (d[e + 1] & 0xff) << 8, to = (d[e + 4] & 0xff) | (d[e + 5] & 0xff) << 8;
                    if (method != 0 && to > 0 && to < 899 && to != s && pre[to] == 0) pre[to] = s; }
            } catch (IOException e) { }
        }
        int[] b = new int[899];
        for (int s = 1; s < 899; s++) { int x = s; for (int g = 0; g < 4 && pre[x] != 0; g++) x = pre[x]; b[s] = x; }
        base = b;
    }
    static String place(Context c, int id) { loadData(c); return id >= 0 && id < places.length && !places[id].trim().isEmpty() ? places[id].trim() : T.t(c, "area %d", id); }
    static String speciesName(Context c, int s) { loadData(c); return s > 0 && s < species.length && !species[s].isEmpty() ? species[s] : "#" + s; }
    private static int lineOf(Context c, int s) { loadData(c); return base != null && s > 0 && s < base.length ? base[s] : s; }

    // ---------- the run ----------
    static File dir(Context c) { File d = new File(GameSettings.dir(c), "nuzlocke"); d.mkdirs(); return d; }
    static synchronized Properties load(Context c) {
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(new File(dir(c), "run.properties"))) { p.load(in); } catch (IOException e) { }
        return p;
    }
    private static synchronized void save(Context c, Properties p) {
        File f = new File(dir(c), "run.properties"), tmp = new File(dir(c), "run.properties.part");
        try (FileOutputStream out = new FileOutputStream(tmp)) { p.store(out, "Nuzlocke run (Nuzlocke.java)"); } catch (IOException e) { return; }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
    }
    static boolean active(Context c) { return "on".equals(load(c).getProperty("state")); }
    static String rule(Properties p, String key) { return p.getProperty("rule." + key, ""); }
    private static String now() { return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date()); }
    static synchronized void log(Context c, String s) {
        try (FileWriter w = new FileWriter(new File(dir(c), "log.txt"), true)) { w.write(now() + "  " + s + "\n"); } catch (IOException e) { }
    }
    static List<String> logLines(Context c, int max) {
        List<String> all = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(new File(dir(c), "log.txt")))) { String l; while ((l = r.readLine()) != null) all.add(l); } catch (IOException e) { }
        return all.size() > max ? all.subList(all.size() - max, all.size()) : all;
    }
    // the dead: { nickname, species number, level, when }
    static List<String[]> dead(Properties p) {
        List<String[]> out = new ArrayList<>();
        for (String k : p.stringPropertyNames()) if (k.startsWith("dead.")) out.add(p.getProperty(k).split("\t", -1));
        out.sort((x, y) -> x.length > 3 && y.length > 3 ? x[3].compareTo(y[3]) : 0);
        return out;
    }
    // the areas used: { area, who used it }
    static List<String[]> areas(Properties p) {
        List<String[]> out = new ArrayList<>();
        for (String k : p.stringPropertyNames()) if (k.startsWith("area.")) out.add(new String[] { k.substring(5), p.getProperty(k) });
        out.sort((x, y) -> x[0].compareTo(y[0]));
        return out;
    }
    static synchronized void start(Context c) {
        Properties p = new Properties(); p.setProperty("state", "on"); p.setProperty("started", now()); p.setProperty("baseline", "pending");
        String pr = GameSettings.get(c, "DM_NZ_PRESET"); p.setProperty("rule.DM_NZ_PRESET", pr);
        for (Opt o : OPTS) p.setProperty("rule." + o.key, GameSettings.get(c, o.key));
        new File(dir(c), "log.txt").delete();
        save(c, p); log(c, "Run started (" + label(PRESETS, pr) + ")");
    }
    static synchronized void end(Context c, String how, String why) {   // how: ended / failed / won
        Properties p = load(c); if (!"on".equals(p.getProperty("state"))) return;
        p.setProperty("state", how); p.setProperty("ended", now()); save(c, p); log(c, why);
    }
    static synchronized void setBadges(Context c, int n) {
        Properties p = load(c); if (!"on".equals(p.getProperty("state"))) return;
        n = Math.max(0, Math.min(8, n)); if (n == badges(p)) return;
        p.setProperty("badges", String.valueOf(n)); save(c, p); log(c, "Badges: " + n);
    }
    static volatile String engineState = "";   // what the engine last said about the party (the menu shows it)
    static volatile int gameBadges = -1, gameStyle = -1;   // read from the game (Shield 1.3.2); -1 = not known (another build, or not found yet): badges are counted by hand

    // ---------- while playing ----------
    private static volatile Activity act; private static Thread watcher;
    static void attach(Activity a) {   // GameActivity.onCreate
        act = a; if (watcher != null || !forThisGame(a)) return;
        watcher = new Thread(Nuzlocke::watch, "nuzlocke"); watcher.setDaemon(true); watcher.setPriority(Thread.MIN_PRIORITY); watcher.start();
    }
    // a Pokémon as the engine reports it (mon_line): 1 constant, 2 species, 3 level, 4 HP, 5 max HP, 6 nickname, 7 met location, 8 ball, 9 shiny, 10 nicknamed,
    // 11 egg location, 12 egg
    private static int num(String[] m, int i) { try { return i < m.length ? Integer.parseInt(m[i]) : 0; } catch (NumberFormatException e) { return 0; } }
    private static void watch() {
        long lastRemind = 0;
        for (;;) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            Activity a = act; if (a == null) continue;
            if (!active(a)) { status(a, null); try { nativeHoldSet(false); } catch (UnsatisfiedLinkError e) { return; } continue; }
            String r; try { nativeHoldSet("set".equals(rule(load(a), "DM_NZ_STYLE"))); r = nativePoll(); } catch (UnsatisfiedLinkError e) { return; }
            List<String[]> party = new ArrayList<>(), fresh = new ArrayList<>(); List<String> faints = new ArrayList<>(); boolean scanned = false; int got = -1;
            for (String ln : r.split("\n")) { String[] f = ln.split("\t", -1);
                if (f[0].equals("S") && f.length > 1) engineState = f[1];
                else if (f[0].equals("G") && f.length > 3) { got = f[1].equals("-1") ? -1 : num(f, 1); gameBadges = got; gameStyle = f[3].equals("-1") ? -1 : num(f, 3); }
                else if (f[0].equals("P") && f.length > 12) { party.add(f); fresh.add(f); }
                else if (f[0].equals("N") && f.length > 12) fresh.add(f);
                else if (f[0].equals("F") && f.length > 1) faints.add(f[1]);
                else if (f[0].equals("D")) scanned = true; }
            List<String[]> notices = new ArrayList<>(); String remind = null; int deaths;
            synchronized (Nuzlocke.class) {
                Properties p = load(a); if (!"on".equals(p.getProperty("state"))) continue;
                boolean changed = false, baseline = !"done".equals(p.getProperty("baseline"));
                if (got >= 0 && got != badges(p)) {   // the game's own badges (Shield 1.3.2): they replace the hand count
                    boolean earned = got > badges(p) && !baseline;   // (not the badges already held when the run started)
                    log(a, earned ? T.t(a, "Badge earned: %d (read from the game)", got) : T.t(a, "Badges: %d (read from the game)", got));
                    p.setProperty("badges", String.valueOf(got)); changed = true;
                    if (earned && !rule(p, "DM_NZ_LEVELCAP").isEmpty()) notices.add(new String[] { T.t(a, "Badge %d! The level cap is now %d.", got, CAPS[Math.min(8, got)]), "" });
                }
                // new Pokémon: before the first full scan of a run, what the player already had; after it, catches (and gifts, eggs, raids)
                for (String[] m : fresh) { if (num(m, 12) == 1 || p.containsKey("seen." + m[1])) continue;
                    p.setProperty("seen." + m[1], m[6]); changed = true;
                    if (baseline) { remember(a, p, m); if (m[0].equals("P")) log(a, "In the party at the start: " + who(a, m)); }
                    else notices.addAll(arrived(a, p, m)); }
                if (baseline && scanned) { p.setProperty("baseline", "done"); changed = true; log(a, "From now on, new Pokémon are checked against the rules"); }
                for (String ec : faints) if (!p.containsKey("dead." + ec)) {
                    String nick = p.getProperty("seen." + ec, "?"), sp = "", level = "";
                    for (String[] m : party) if (m[1].equals(ec)) { nick = m[6]; sp = m[2]; level = m[3]; }
                    p.setProperty("dead." + ec, nick + "\t" + sp + "\t" + level + "\t" + now()); changed = true;
                    log(a, nick + (level.isEmpty() ? "" : " (Lv. " + level + ")") + " fainted: dead");
                    String dm = rule(p, "DM_NZ_DEATHMSG");
                    if (!dm.equals("off")) notices.add(new String[] { T.t(a, "%s has fainted. It's dead.", nick), dm.isEmpty() ? "big" : "" });
                }
                int alive = 0; for (String[] m : party) if (!p.containsKey("dead." + m[1])) alive++;
                if (!party.isEmpty() && alive == 0 && !p.containsKey("wiped")) {
                    p.setProperty("wiped", now()); changed = true;
                    if (rule(p, "DM_NZ_WIPE").isEmpty()) { save(a, p); end(a, "failed", "Wipe: every Pokémon in the party is dead. The run is over"); p = load(a); changed = false; notices.add(new String[] { T.t(a, "Wipe! The run is over."), "big" }); }
                    else log(a, "Wipe: every Pokémon in the party is dead");
                }
                if (System.currentTimeMillis() - lastRemind > 120000 && notices.isEmpty()) {
                    List<String> w = problems(a, p, party); changed = true;   // (it clears nickname reminders that are done)
                    if (!w.isEmpty()) { lastRemind = System.currentTimeMillis(); remind = String.join("\n", w); }
                }
                if (!notices.isEmpty()) lastRemind = System.currentTimeMillis();
                if (changed) save(a, p);
                deaths = dead(p).size();
                String cap = rule(p, "DM_NZ_LEVELCAP").isEmpty() ? "" : " · " + T.t(a, "cap %d", CAPS[badges(p)]);
                status(a, rule(p, "DM_NZ_STATUSLINE").equals("off") || !"on".equals(p.getProperty("state")) ? null : T.t(a, "Nuzlocke · deaths %d", deaths) + cap);
            }
            for (String[] n : notices) notice(a, n[0], n[1].equals("big"));
            if (remind != null) notice(a, remind, false);
        }
    }
    private static String who(Context c, String[] m) {
        String sp = speciesName(c, num(m, 2)); return m[6].equals(sp) || m[6].isEmpty() ? sp : m[6] + " (" + sp + ")";
    }
    // a Pokémon the player had when the run started: it counts for the dupes clause
    private static void remember(Context c, Properties p, String[] m) { p.setProperty("dupe." + lineOf(c, num(m, 2)), m[6]); p.setProperty("dupe.s" + num(m, 2), m[6]); }
    // a new Pokémon during the run: its area and the rules; returns the notices
    private static List<String[]> arrived(Context c, Properties p, String[] m) {
        List<String[]> out = new ArrayList<>(); List<String> broke = new ArrayList<>(), notes = new ArrayList<>();
        int sp = num(m, 2), met = num(m, 7), eggloc = num(m, 11); boolean shinyMon = num(m, 9) == 1, hatched = eggloc != 0, raid = met == DEN || met == MAX_LAIR;
        String name = who(c, m), where = place(c, met);
        String dupesRule = rule(p, "DM_NZ_DUPES"); String dkey = dupesRule.equals("species") ? "dupe.s" + sp : "dupe." + lineOf(c, sp);
        boolean shinyFree = shinyMon && rule(p, "DM_NZ_SHINY").isEmpty();
        boolean dupe = !dupesRule.equals("off") && !rule(p, "DM_NZ_FIRST").equals("off") && !shinyFree && p.containsKey(dkey);   // (dupes only matter with first encounters)
        if (!rule(p, "DM_NZ_FIRST").equals("off")) {
            String area = null;
            if (shinyFree) notes.add(T.t(c, "shiny clause: the area isn't used up"));
            else if (raid) { String rr = rule(p, "DM_NZ_RAIDS");
                if (rr.equals("none")) broke.add(T.t(c, "Max Raids aren't allowed")); else if (!rr.equals("free")) area = String.valueOf(met); }
            else if (hatched) { String g = rule(p, "DM_NZ_GIFTS");
                if (g.equals("none")) broke.add(T.t(c, "eggs and gifts aren't allowed")); else if (g.isEmpty()) area = String.valueOf(met); }
            else {
                if (rule(p, "DM_NZ_RAIDS").equals("only") && met >= WILD_FIRST && met <= WILD_LAST) broke.add(T.t(c, "only raids count in the Wild Area"));
                area = rule(p, "DM_NZ_WILD").equals("one") && met >= WILD_FIRST && met <= WILD_LAST ? "wild" : String.valueOf(met);
            }
            if (area != null && dupe) { notes.add(T.t(c, "a dupe: the area isn't used up")); area = null; }
            if (area != null) {
                String by = p.getProperty("area." + area);
                if (by != null) broke.add(T.t(c, "%s was already used (%s)", area.equals("wild") ? T.t(c, "the Wild Area") : where, by));
                else p.setProperty("area." + area, name);
            }
        }
        if (dupe) broke.add(T.t(c, "a dupe of %s", p.getProperty(dkey)));
        if (LEGEND.contains(sp) && rule(p, "DM_NZ_LEGEND").equals("none")) broke.add(T.t(c, "legendaries aren't allowed"));
        p.setProperty("dupe." + lineOf(c, sp), name); p.setProperty("dupe.s" + sp, name);
        if (num(m, 10) == 0 && !hatched && rule(p, "DM_NZ_NICK").isEmpty()) p.setProperty("nick." + m[1], name);
        String how = raid ? "Raid catch" : hatched ? "Hatched" : "Caught";
        log(c, how + ": " + name + (num(m, 3) > 0 ? " (Lv. " + m[3] + ")" : "") + " at " + where + (shinyMon ? ", shiny" : "")
            + (notes.isEmpty() ? "" : "; " + String.join("; ", notes)) + (broke.isEmpty() ? "" : ". BREAKS A RULE: " + String.join("; ", broke)));
        if (!broke.isEmpty()) { p.setProperty("flag." + m[1], String.join("; ", broke)); out.add(new String[] { T.t(c, "%s breaks a rule: %s", name, String.join("; ", broke)), "big" }); }
        else if (p.containsKey("nick." + m[1])) out.add(new String[] { T.t(c, "%s needs a nickname (rename it from the box).", name), "" });
        return out;
    }
    // what is wrong right now, for the reminder: dead in the party, over the cap, too many, a shiny or a legendary in the party, a missing nickname
    private static List<String> problems(Context c, Properties p, List<String[]> party) {
        List<String> w = new ArrayList<>(), deadIn = new ArrayList<>(), over = new ArrayList<>(), shinies = new ArrayList<>(), legends = new ArrayList<>(), nonick = new ArrayList<>();
        int cap = CAPS[badges(p)];
        for (String[] m : party) {
            String name = m[6];
            if (p.containsKey("dead." + m[1])) { deadIn.add(name); continue; }
            if (!rule(p, "DM_NZ_LEVELCAP").isEmpty() && num(m, 3) > cap) over.add(name + " (" + m[3] + ")");
            if (num(m, 9) == 1 && rule(p, "DM_NZ_SHINY_PARTY").equals("off")) shinies.add(name);
            if (LEGEND.contains(num(m, 2)) && rule(p, "DM_NZ_LEGEND").equals("box")) legends.add(name);
            if (p.containsKey("nick." + m[1])) { if (num(m, 10) == 1) p.remove("nick." + m[1]); else nonick.add(name); }
        }
        if (!deadIn.isEmpty()) w.add(rule(p, "DM_NZ_FAINT").equals("release") ? T.t(c, "Dead, release: %s", String.join(", ", deadIn)) : T.t(c, "Dead, put in a box: %s", String.join(", ", deadIn)));
        if (!over.isEmpty()) w.add(T.t(c, "Over the level cap of %d: %s", cap, String.join(", ", over)));
        String ps = rule(p, "DM_NZ_PARTY"); int max = ps.isEmpty() ? 6 : ps.equals("gym") ? TEAM[badges(p)] : num(new String[] { ps }, 0);
        if (max > 0 && party.size() > max) w.add(T.t(c, "Party too big: %d of %d", party.size(), max));
        if (!shinies.isEmpty()) w.add(T.t(c, "Shinies may not be in the party: %s", String.join(", ", shinies)));
        if (!legends.isEmpty()) w.add(T.t(c, "Legendaries are box only: %s", String.join(", ", legends)));
        if (!nonick.isEmpty()) w.add(T.t(c, "Needs a nickname: %s", String.join(", ", nonick)));
        return w;
    }
    // a notice over the game: not a pop-up (no touch, no focus), gone after a few seconds; big = in the middle, larger
    private static void notice(Activity a, String s, boolean big) {
        a.runOnUiThread(() -> {
            View root = a.getWindow().getDecorView(); float dp = a.getResources().getDisplayMetrics().density;
            TextView t = new TextView(a); t.setText(s); t.setTextColor(0xFFFFFFFF); t.setTextSize(big ? 20 : 14); t.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(); g.setColor(big ? 0xE0300A12 : 0xCC121419); g.setCornerRadius(16 * dp); t.setBackground(g);
            int pad = Math.round((big ? 22 : 12) * dp); t.setPadding(pad * 2, pad, pad * 2, pad);
            PopupWindow w = new PopupWindow(t, -2, -2, false); w.setTouchable(false);
            try { w.showAtLocation(root, big ? Gravity.CENTER : Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, big ? 0 : Math.round(24 * dp)); } catch (RuntimeException e) { return; }
            root.postDelayed(() -> { try { w.dismiss(); } catch (RuntimeException e) { } }, big ? 6000 : 5000);
        });
    }
    private static PopupWindow line; private static TextView lineText;
    private static void status(Activity a, String s) {   // the status line at the top right; null hides it
        a.runOnUiThread(() -> {
            if (s == null) { if (line != null) try { line.dismiss(); } catch (RuntimeException e) { } line = null; return; }
            if (line == null) {
                lineText = new TextView(a); lineText.setTextColor(0xFFFFFFFF); lineText.setTextSize(11); lineText.setShadowLayer(3, 0, 0, 0xFF000000);
                lineText.setBackgroundColor(0x66000000); lineText.setPadding(12, 4, 12, 4);
                line = new PopupWindow(lineText, -2, -2, false); line.setTouchable(false);
                try { line.showAtLocation(a.getWindow().getDecorView(), Gravity.TOP | Gravity.END, 16, 16); } catch (RuntimeException e) { line = null; return; }
            }
            lineText.setText(s);
        });
    }
}
