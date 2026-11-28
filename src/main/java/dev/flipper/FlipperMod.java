package dev.flipper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;
import java.util.Set;
import java.util.concurrent.*;

/** Commands: /flip on | off | stop | status | debug | ui | ui | auto on|off | purse <n> */
public class FlipperMod implements ClientModInitializer {
    public static Config cfg;
    static final FlipEngine engine = new FlipEngine();
    static final Budget budget = new Budget();
    static final Bot bot = new Bot();
    static volatile boolean on = false;
    static final Set<String> tried = ConcurrentHashMap.newKeySet(), alerted = ConcurrentHashMap.newKeySet();
    static int cooldown = 0, flashTick = 0, purseTick = 0;
    static volatile long purseCache = -1;
    static volatile boolean reported = false;
    static String lastShownError = "";
    static final java.util.Queue<String> manualBuys = new ConcurrentLinkedQueue<>();   // from the dashboard Buy button
    static volatile long purseCache = -1;
    static int purseTick = 0;
    static volatile String flashText = null;
    static volatile long flashUntil = 0;

    @Override public void onInitializeClient() {
        cfg = Config.load();
        engine.load();
        Runtime.getRuntime().addShutdownHook(new Thread(engine::save));
        WebUi.start(cfg);
        var t = new Thread(FlipperMod::pollLoop, "ahflipper-poll");
        t.setDaemon(true);
        t.start();

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null) return;
            if (++purseTick % 20 == 0) purseCache = GameBridge.purse();   // read here, on the game thread
            if (flashText != null) {                                      // keep the sell price on screen for ~12s
                if (System.currentTimeMillis() > flashUntil) flashText = null;
                else if (++flashTick % 30 == 1) GameBridge.overlay(flashText);
            }
            bot.tick(cfg);
            if (bot.busy()) return;
            if (cooldown > 0) { cooldown--; return; }
            if (mc.gui.screen() != null) return;
            if (!manualBuys.isEmpty()) { manualBuy(manualBuys.poll()); return; }
            if (!on || !cfg.autoExecute) return;
            pickNext();
        });
        ClientReceiveMessageEvents.GAME.register((m, overlay) -> {
            String s = m.getString();
            bot.onMessage(s);
            if (s.startsWith("[Auction]") && s.contains("bought")) budget.sold(s);
        });
        ClientSendMessageEvents.ALLOW_COMMAND.register(cmd -> {
            if (!cmd.equals("flip") && !cmd.startsWith("flip ")) return true;
            handle(cmd.length() > 4 ? cmd.substring(5).trim() : "status");
            return false;
        });
    }

    /**
     * Hypixel rebuilds its auction cache about once a minute, so polling faster than that finds nothing new.
     * Instead: sleep until just before the next rebuild, probe page 0 every peekMs, and the moment
     * lastUpdated changes, download every page in parallel.
     */
    static void pollLoop() {
        long lastSeen = 0, nextAt = 0;
        while (true) {
            try {
                if (!on) { Thread.sleep(1000); continue; }
                long now = System.currentTimeMillis();
                if (now < nextAt) { Thread.sleep(Math.min(500, nextAt - now)); continue; }
                var first = AuctionApi.page0();
                long detected = System.currentTimeMillis();
                if (first.lastUpdated() == lastSeen) { nextAt = detected + cfg.peekMs; continue; }
                lastSeen = first.lastUpdated();
                nextAt = detected + cfg.cacheSeconds * 1000L - cfg.earlyMs;
                try {
                    engine.refresh(cfg, first); alert();
                    engine.lastError = ""; lastShownError = "";
                    if (!reported) {
                        reported = true;
                        GameBridge.say("§7Scan working: " + engine.lastCount + " auctions checked. Type /flip ui for the control panel.");
                    }
                }
                catch (AuctionApi.Stale st) { lastSeen = 0; nextAt = 0; }   // cache rolled mid-fetch: go again
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                e.printStackTrace();
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                engine.lastError = msg;
                if (!msg.equals(lastShownError)) { lastShownError = msg; GameBridge.say("§cScan failed: " + msg); }
                try { Thread.sleep(5000); } catch (InterruptedException i) { return; }
            }
        }
    }

    /** Buy first (speed matters), relist whenever there is nothing to buy. */
    static void pickNext() {
        long now = System.currentTimeMillis(), purse = GameBridge.purse();
        FlipEngine.Flip f;
        while ((f = engine.fast.poll()) != null) if (tryBuy(f, purse)) return;   // found mid-download
        for (var g : engine.flips) if (tryBuy(g, purse)) return;
        if (cfg.autoRelist) for (var p : budget.open) {
            boolean ready = p.state == Budget.State.BOUGHT || (p.state == Budget.State.BID && now > p.endMs + 15_000);
            if (!ready) continue;
            var st = engine.stats.get(p.key);
            long fair = st != null ? st.fair() : Math.round(p.cost * 1.1);
            long price = Math.max(Math.round(fair * (1 - cfg.undercutPct / 100)), Math.round(p.cost * 1.03));
            bot.claimAndList(cfg, p, price, () -> { p.state = Budget.State.LISTED; p.listPrice = price; cooldown = 20; }, () -> {
                if (p.state == Budget.State.BID) budget.open.remove(p);       // bid probably lost
                else if (++p.failures >= 3) { p.state = Budget.State.LISTED; GameBridge.say("§eList " + p.name + " manually."); }
                cooldown = 40;
            });
            return;
        }
    }

    /** Dashboard Buy button: works even in alert-only mode, but still obeys the budget rules. */
    static void manualBuy(String uuid) {
        FlipEngine.Flip f = null;
        for (var g : engine.flips) if (g.a().uuid().equals(uuid)) { f = g; break; }
        if (f == null) { GameBridge.say("§cThat flip is no longer listed."); return; }
        if (!budget.allows(cfg, f, GameBridge.purse())) {
            GameBridge.say("§cYour budget rules block this buy (open flips, per-item cap or reserve).");
            return;
        }
        tried.add(uuid);
        bot.buy(cfg, f, budget);
        cooldown = 4;
    }

    static boolean tryBuy(FlipEngine.Flip f, long purse) {
        if (tried.contains(f.a().uuid()) || !budget.allows(cfg, f, purse)) return false;
        tried.add(f.a().uuid());
        bot.buy(cfg, f, budget);
        cooldown = 4;
        return true;
    }

    /** Price to list at: fair value minus the undercut, never below cost + 3%. */
    static long recommended(FlipEngine.Flip f) {
        return Math.max(Math.round(f.fair() * (1 - cfg.undercutPct / 100)), Math.round(f.buy() * 1.03));
    }

    static void flash(String text) {
        flashText = text; flashUntil = System.currentTimeMillis() + 12_000; flashTick = 0;
        GameBridge.overlay(text);
    }

    static void alert() {
        if (cfg.autoExecute) return;
        int n = 0;
        for (var f : engine.flips) { if (n++ >= 3) break; alertFlip(f); }
    }

    static void alertFlip(FlipEngine.Flip f) {
        if (!alerted.add(f.a().uuid())) return;
        GameBridge.say(String.format("%s%s: buy %,d, list at ~%,d, profit %,d (%.0f%%), ~%.1f sales/h  §7/viewauction %s",
                f.bid() ? "§6BID " : "§a", f.a().name(), f.buy(), recommended(f), f.profit(), f.margin(), f.salesPerHour(), f.a().uuid()));
    }

    static void handle(String arg) {
        String[] a = arg.split(" ");
        switch (a[0]) {
            case "on" -> { on = true; reported = false; GameBridge.say("Scanning. Type /flip ui for the dashboard. Past sales load from SkyCofl, so flips should start appearing within a few minutes."); }
            case "off" -> { on = false; bot.stop(); GameBridge.say("Off."); }
            case "stop" -> { bot.stop(); GameBridge.say("Current task cancelled."); }
            case "auto" -> { cfg.autoExecute = a.length > 1 && a[1].equals("on"); cfg.save(); GameBridge.say("autoExecute = " + cfg.autoExecute); }
            case "purse" -> { if (a.length > 1) { cfg.purseOverride = Long.parseLong(a[1]); cfg.save(); } GameBridge.say("purse override = " + cfg.purseOverride); }
            case "debug" -> GameBridge.say(engine.debug);
            case "ui" -> GameBridge.say(WebUi.running
                    ? "Dashboard (open in your browser): http://127.0.0.1:" + cfg.uiPort + "/?t=" + cfg.uiToken
                    : "Dashboard is off or its port is in use. Change uiPort in config/ahflipper.json.");
            default -> GameBridge.say(String.format("on=%s auto=%s purse=%,d committed=%,d open=%d flips=%d lastScan=%s%s",
                    on, cfg.autoExecute, GameBridge.purse(), budget.committed(), budget.open.size(), engine.flips.size(),
                    engine.lastScanAt == 0 ? "never" : ((System.currentTimeMillis() - engine.lastScanAt) / 1000) + "s ago",
                    engine.lastError.isEmpty() ? "" : " lastError=" + engine.lastError));
        }
    }
}
