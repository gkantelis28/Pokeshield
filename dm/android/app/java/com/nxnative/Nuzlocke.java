// Nuzlocke mode (docs/NUZLOCKE.md), Pokémon Sword / Shield only. The rules are settings (DM_NZ_* in env.txt, the ☰ Nuzlocke tab); starting a run copies them into the
// run, where they stay locked until it ends. The run lives in <game folder>/nuzlocke/: run.properties (state, the locked rules, the dead, the Pokémon seen) and log.txt.
// While a run is on, a thread asks the engine (app/jni/nuzlocke.c) for the party once a second: a faint marks that Pokémon dead (by its encryption constant, which never
// changes, so a nickname change or an evolution keeps it dead), a dead Pokémon left in the party is reminded about, and a party with only dead Pokémon is a wipe.
// What each rule does so far is its status (ON / PARTLY / LATER), shown on its card, so nothing claims to be enforced when it isn't.
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public final class Nuzlocke {
    private Nuzlocke() { }
    private static native String nativePoll();

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
        new Opt("Core", "DM_NZ_FIRST", "First encounter only", LATER, new String[][] {
            { "", "Per area", "Only the first wild Pokémon in each area may be caught" }, { "off", "Off", "Catch anything (deaths still count)" } }),
        new Opt("Core", "DM_NZ_WILD", "The Wild Area counts as", LATER, new String[][] {
            { "", "Per zone", "One area per zone" }, { "one", "One area", "The whole Wild Area is one area" }, { "weather", "Zone + weather", "One area per zone and weather" } }),
        new Opt("Core", "DM_NZ_RAIDS", "Max Raid Battles", LATER, new String[][] {
            { "", "Count", "A raid uses up its area's encounter" }, { "free", "Free", "Raids don't use up the area" }, { "none", "Not allowed", "" },
            { "only", "Only source", "In the Wild Area, raids are the only encounters (wild catches there are blocked)" } }),
        new Opt("Core", "DM_NZ_GIFTS", "Gifts and fixed encounters", LATER, new String[][] {
            { "", "Count", "The starter, fossils, gifts and legendaries use up their area" }, { "free", "Free", "They don't use up the area" }, { "none", "Not allowed", "" } }),
        new Opt("Core", "DM_NZ_FAILED", "Failed encounters", LATER, new String[][] {
            { "", "Use up the area", "The wild one fled or fainted, or you ran: the area is used up" }, { "retry", "Retry allowed", "" } }),
        new Opt("Core", "DM_NZ_NICK", "Nicknames", LATER, new String[][] { { "", "Required", "Asked for at every catch" }, { "optional", "Optional", "" } }),
        new Opt("Clauses", "DM_NZ_DUPES", "Dupes clause", LATER, new String[][] {
            { "", "Evolution line", "A Pokémon whose evolution line you have doesn't use up the area" }, { "species", "Species", "A species you have doesn't use up the area" }, { "off", "Off", "" } }),
        new Opt("Clauses", "DM_NZ_SHINY", "Shiny clause", LATER, new String[][] {
            { "", "On", "Shinies may always be caught and don't use up the area" }, { "off", "Off", "" } }),
        new Opt("Clauses", "DM_NZ_SHINY_PARTY", "Shinies may join the party", LATER, new String[][] { { "", "Yes", "" }, { "off", "No", "" } }),
        new Opt("Clauses", "DM_NZ_LEGEND", "Legendary / Mythical", LATER, new String[][] { { "", "Allowed", "" }, { "box", "Box only", "" }, { "none", "Not allowed", "" } }),
        new Opt("Difficulty", "DM_NZ_LEVELCAP", "Level caps", LATER, new String[][] {
            { "", "Off", "" }, { "soft", "Soft", "No EXP above the next Gym Leader's ace" }, { "hard", "Hard", "No EXP above the cap, and a Pokémon above it can't battle" } }),
        new Opt("Difficulty", "DM_NZ_EXPSHARE", "Exp. Share", LATER, new String[][] { { "", "Game default", "Always on, as in the game" }, { "off", "Off", "" } }),
        new Opt("Difficulty", "DM_NZ_CANDY", "Exp. Candy", LATER, new String[][] { { "", "Allowed", "" }, { "cap", "Up to the cap", "" }, { "none", "Not allowed", "" } }),
        new Opt("Difficulty", "DM_NZ_ITEMS", "Items in battle", LATER, new String[][] {
            { "", "Allowed", "" }, { "1", "1 per battle", "" }, { "3", "3 per battle", "" }, { "5", "5 per battle", "" }, { "none", "Not allowed", "" },
            { "trainer", "Trainer's items", "Only what the enemy trainer carries, as many as they have; none in wild battles" } }),
        new Opt("Difficulty", "DM_NZ_HELD", "Held items", LATER, new String[][] { { "", "Allowed", "" }, { "berries", "Berries only", "" }, { "none", "Not allowed", "" } }),
        new Opt("Difficulty", "DM_NZ_HEAL", "Healing outside battle", LATER, new String[][] {
            { "", "Allowed", "Pokémon Centers, healing items, camp curry" }, { "limited", "Limited per area", "" }, { "none", "Not allowed", "" } }),
        new Opt("Difficulty", "DM_NZ_DYNAMAX", "Dynamax / Gigantamax", LATER, new String[][] { { "", "Allowed", "" }, { "gyms", "Gyms only", "" }, { "none", "Not allowed", "" } }),
        new Opt("Difficulty", "DM_NZ_STYLE", "Battle style", LATER, new String[][] { { "", "Game default", "" }, { "set", "Locked to Set", "The game's Set mode, held for the run" } }),
        new Opt("Difficulty", "DM_NZ_GYMS", "Gyms", LATER, new String[][] {
            { "", "Free to leave", "" }, { "locked", "Can't leave", "A Gym can't be left until its Leader is beaten" } }),
        new Opt("Difficulty", "DM_NZ_PARTY", "Party size", LATER, new String[][] {
            { "", "6", "" }, { "5", "5", "" }, { "4", "4", "" }, { "3", "3", "" }, { "2", "2", "" }, { "1", "1", "" }, { "gym", "Next Gym Leader's", "As many as the next Gym Leader has" } }),
        new Opt("End of the run", "DM_NZ_WIPE", "Whole party fainted (wipe)", PARTLY, new String[][] {
            { "", "Run ends", "The run ends, marked failed (the save stays playable)" }, { "lives", "Lives", "Lose a life (coming later: logged for now)" }, { "log", "Just logged", "" } }),
        new Opt("End of the run", "DM_NZ_SECOND", "Second chances", LATER, new String[][] { { "", "None", "" }, { "token", "Revive token per badge", "" } }),
        new Opt("End of the run", "DM_NZ_CHAMP", "After the Champion", LATER, new String[][] { { "", "Run ends (won)", "" }, { "on", "Rules stay on", "" } }),
        new Opt("Display", "DM_NZ_STATUSLINE", "Status line in the corner", ON, new String[][] { { "", "On", "Deaths so far, at the top right" }, { "off", "Off", "" } }),
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
    static synchronized void start(Context c) {
        Properties p = new Properties(); p.setProperty("state", "on"); p.setProperty("started", now());
        String pr = GameSettings.get(c, "DM_NZ_PRESET"); p.setProperty("rule.DM_NZ_PRESET", pr);
        for (Opt o : OPTS) p.setProperty("rule." + o.key, GameSettings.get(c, o.key));
        new File(dir(c), "log.txt").delete();
        save(c, p); log(c, "Run started (" + label(PRESETS, pr) + ")");
    }
    static synchronized void end(Context c, String how, String why) {   // how: ended / failed / won
        Properties p = load(c); if (!"on".equals(p.getProperty("state"))) return;
        p.setProperty("state", how); p.setProperty("ended", now()); save(c, p); log(c, why);
    }
    static volatile String engineState = "";   // what the engine last said about the party (the menu shows it)

    // ---------- while playing ----------
    private static volatile Activity act; private static Thread watcher;
    static void attach(Activity a) {   // GameActivity.onCreate
        act = a; if (watcher != null || !forThisGame(a)) return;
        watcher = new Thread(Nuzlocke::watch, "nuzlocke"); watcher.setDaemon(true); watcher.setPriority(Thread.MIN_PRIORITY); watcher.start();
    }
    private static void watch() {
        long lastRemind = 0;
        for (;;) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            Activity a = act; if (a == null) continue;
            if (!active(a)) { status(a, null); continue; }
            String r; try { r = nativePoll(); } catch (UnsatisfiedLinkError e) { return; }
            List<String[]> party = new ArrayList<>(); List<String> faints = new ArrayList<>();
            for (String ln : r.split("\n")) { String[] f = ln.split("\t", -1);
                if (f[0].equals("S") && f.length > 1) engineState = f[1];
                else if (f[0].equals("P") && f.length > 6) party.add(f);   // P constant species level hp maxhp nickname
                else if (f[0].equals("F") && f.length > 1) faints.add(f[1]); }
            List<String> news = new ArrayList<>(); String remind = null; int deaths;
            synchronized (Nuzlocke.class) {
                Properties p = load(a); if (!"on".equals(p.getProperty("state"))) continue;
                boolean changed = false, first = !p.containsKey("party.seen");
                for (String[] m : party) if (!p.containsKey("seen." + m[1])) {
                    p.setProperty("seen." + m[1], m[6]); changed = true;
                    log(a, (first ? "In the party at the start: " : "Joined the party: ") + m[6] + " (Lv. " + m[3] + ")");
                }
                if (!party.isEmpty() && first) { p.setProperty("party.seen", "1"); changed = true; }
                for (String ec : faints) if (!p.containsKey("dead." + ec)) {
                    String nick = p.getProperty("seen." + ec, "?"), species = "", level = "";
                    for (String[] m : party) if (m[1].equals(ec)) { nick = m[6]; species = m[2]; level = m[3]; }
                    p.setProperty("dead." + ec, nick + "\t" + species + "\t" + level + "\t" + now()); changed = true;
                    log(a, nick + (level.isEmpty() ? "" : " (Lv. " + level + ")") + " fainted: dead");
                    news.add(nick);
                }
                int alive = 0; List<String> deadIn = new ArrayList<>();
                for (String[] m : party) if (p.containsKey("dead." + m[1])) deadIn.add(m[6]); else alive++;
                if (!party.isEmpty() && alive == 0 && !p.containsKey("wiped")) {
                    p.setProperty("wiped", now()); changed = true;
                    String w = rule(p, "DM_NZ_WIPE");
                    if (w.isEmpty()) { save(a, p); changed = false; end(a, "failed", "Wipe: every Pokémon in the party is dead. The run is over"); notice(a, T.t(a, "Wipe! The run is over."), true); }
                    else log(a, "Wipe: every Pokémon in the party is dead");
                }
                if (!deadIn.isEmpty() && System.currentTimeMillis() - lastRemind > 120000 && news.isEmpty()) {
                    lastRemind = System.currentTimeMillis();
                    remind = rule(p, "DM_NZ_FAINT").equals("release") ? T.t(a, "%s is dead: release it.", String.join(", ", deadIn))
                                                                     : T.t(a, "%s is dead: put it in a box.", String.join(", ", deadIn));
                }
                if (changed) save(a, p);
                deaths = dead(p).size();
                String dm = rule(p, "DM_NZ_DEATHMSG");
                for (String n : news) if (!dm.equals("off")) notice(a, T.t(a, "%s has fainted. It's dead.", n), dm.isEmpty());
                if (!news.isEmpty()) lastRemind = System.currentTimeMillis();
                status(a, rule(p, "DM_NZ_STATUSLINE").equals("off") ? null : T.t(a, "Nuzlocke · deaths %d", deaths));
            }
            if (remind != null) notice(a, remind, false);
        }
    }
    // a notice over the game: not a pop-up (no touch, no focus), gone after a few seconds; big = in the middle, larger
    private static void notice(Activity a, String s, boolean big) {
        a.runOnUiThread(() -> {
            View root = a.getWindow().getDecorView(); float dp = a.getResources().getDisplayMetrics().density;
            TextView t = new TextView(a); t.setText(s); t.setTextColor(0xFFFFFFFF); t.setTextSize(big ? 22 : 14); t.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(); g.setColor(big ? 0xE0300A12 : 0xCC121419); g.setCornerRadius(16 * dp); t.setBackground(g);
            int pad = Math.round((big ? 22 : 12) * dp); t.setPadding(pad * 2, pad, pad * 2, pad);
            PopupWindow w = new PopupWindow(t, -2, -2, false); w.setTouchable(false);
            try { w.showAtLocation(root, big ? Gravity.CENTER : Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, big ? 0 : Math.round(24 * dp)); } catch (RuntimeException e) { return; }
            root.postDelayed(() -> { try { w.dismiss(); } catch (RuntimeException e) { } }, big ? 5000 : 4000);
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
