package dev.flipper;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Finds flips. "Does it sell" is measured from real sales: ended BIN auctions are matched back to the
 * listing we saw earlier, and past sales are seeded from SkyCofl so there is no long warm-up.
 * New listings are checked the moment their page downloads (snipe), not after the whole scan.
 */
public final class FlipEngine {
    public record Flip(Auction a, long buy, long fair, long profit, double margin,
                       double salesPerHour, double hoursToClear, double score, boolean bid) {}
    public record Stats(long fair, double perHour, double hoursToClear) {}

    final Map<String, String> seen = new ConcurrentHashMap<>();            // uuid -> key for live BINs
    final Map<String, ArrayDeque<long[]>> sales = new HashMap<>();         // key -> [ts, price]
    final Map<String, String> tags = new ConcurrentHashMap<>();            // key -> SkyCofl item tag
    final Map<String, Long> seededAt = new ConcurrentHashMap<>();          // key -> when history was pulled
    final Set<String> seeding = ConcurrentHashMap.newKeySet();
    final ExecutorService seedPool = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "ahflipper-seed"); t.setDaemon(true); return t; });
    long observedMs = 0, lastRefresh = 0;
    int refreshes = 0, seedMsgs = 0;
    public final Queue<Flip> fast = new ConcurrentLinkedQueue<>();         // flips found mid-download
    public volatile List<Flip> flips = List.of();
    public volatile Map<String, Stats> stats = Map.of();
    // diagnostics (/flip status, /flip debug)
    public volatile String debug = "No scan has finished yet.", lastError = "", lastSeedError = "";
    public volatile long lastScanAt = 0;
    public volatile int lastCount = 0;
    final Map<String, Long> seedFailUntil = new ConcurrentHashMap<>();
    int seedOk = 0, seedFails = 0;
    long missProfit = Long.MIN_VALUE;
    String missText = "none";

    public synchronized void refresh(Config c, AuctionApi.Page first) throws Exception {
        long now = System.currentTimeMillis();
        if (lastRefresh > 0) observedMs += Math.min(now - lastRefresh, 300_000);
        lastRefresh = now;
        fast.clear();
        var endedF = CompletableFuture.supplyAsync(() -> {
            try { return AuctionApi.ended(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        var list = AuctionApi.rest(first, c.maxParallel, page -> snipe(c, page));   // all pages in parallel
        for (var s : endedF.get()) {
            if (!s.bin()) continue;
            String k = seen.remove(s.uuid());
            if (k != null) sales.computeIfAbsent(k, x -> new ArrayDeque<>()).add(new long[]{s.ts(), s.price()});
        }
        Set<String> ids = new HashSet<>();
        for (var a : list) if (a.bin() && !a.claimed()) { ids.add(a.uuid()); seen.putIfAbsent(a.uuid(), a.key()); }
        seen.keySet().retainAll(ids);
        for (var d : sales.values()) while (!d.isEmpty() && now - d.peekFirst()[0] > 86_400_000L) d.pollFirst();
        lastCount = list.size();
        lastScanAt = System.currentTimeMillis();
        evaluate(c, list, now);
        if (++refreshes % 5 == 0) save();
    }

    /** Called per downloaded page: checks brand-new BIN listings against last scan's stats and queues hits at once. */
    void snipe(Config c, List<Auction> page) {
        long now = System.currentTimeMillis();
        var st = stats;
        for (var a : page) {
            if (!a.bin() || a.claimed() || a.end() <= now || ignored(c, a) || seen.containsKey(a.uuid())) continue;
            var s = st.get(a.key());
            if (s == null || s.hoursToClear() > c.maxHoursToClear) continue;
            var out = new ArrayList<Flip>(1);
            consider(c, a, a.price(), s.fair(), s.perHour(), s.hoursToClear(), false, out);
            for (var f : out) {
                fast.add(f);
                if (!c.autoExecute) FlipperMod.alertFlip(f);
            }
        }
    }

    // ---- keep history between sessions ----
    static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("ahflipper-sales.json"); }

    public synchronized void load() {
        try {
            if (!Files.exists(file())) return;
            var o = JsonParser.parseString(Files.readString(file())).getAsJsonObject();
            observedMs = o.get("observedMs").getAsLong();
            long cutoff = System.currentTimeMillis() - 86_400_000L;
            for (var e : o.getAsJsonObject("sales").entrySet()) {
                var d = new ArrayDeque<long[]>();
                for (var x : e.getValue().getAsJsonArray()) {
                    var a = x.getAsJsonArray();
                    long ts = a.get(0).getAsLong();
                    if (ts >= cutoff) d.add(new long[]{ts, a.get(1).getAsLong()});
                }
                if (!d.isEmpty()) sales.put(e.getKey(), d);
            }
            if (o.has("tags")) for (var e : o.getAsJsonObject("tags").entrySet()) tags.put(e.getKey(), e.getValue().getAsString());
            if (o.has("seeded")) for (var e : o.getAsJsonObject("seeded").entrySet()) seededAt.put(e.getKey(), e.getValue().getAsLong());
        } catch (Exception ignored) {}
    }

    public synchronized void save() {
        try {
            var o = new JsonObject();
            o.addProperty("observedMs", observedMs);
            var all = new JsonObject();
            for (var e : sales.entrySet()) {
                var arr = new JsonArray();
                for (var x : e.getValue()) { var p = new JsonArray(); p.add(x[0]); p.add(x[1]); arr.add(p); }
                all.add(e.getKey(), arr);
            }
            o.add("sales", all);
            var t = new JsonObject();
            tags.forEach(t::addProperty);
            o.add("tags", t);
            var s = new JsonObject();
            seededAt.forEach(s::addProperty);
            o.add("seeded", s);
            Files.writeString(file(), o.toString());
        } catch (Exception ignored) {}
    }

    // ---- SkyCofl seeding ----
    /** Items that look like flips but have no sales history yet get their past sales pulled, one request at a time. */
    boolean suspect(Config c, String key, List<Auction> l) {
        if (!c.useCoflnet) return false;
        long buy = l.get(0).price(), up = l.get(1).price();
        long profit = up - Math.round(up * 0.01) - listFee(up) - (long) c.extraFeeCoins - buy;
        if (buy < c.minBuyPrice || profit < c.minProfit) return false;
        maybeSeed(key, l.get(0));
        return true;
    }

    void maybeSeed(String key, Auction sample) {
        long now = System.currentTimeMillis();
        Long at = seededAt.get(key);
        Long failed = seedFailUntil.get(key);
        if (failed != null && now < failed) return;
        if ((at != null && now - at < 43_200_000L) || seeding.size() >= 40 || !seeding.add(key)) return;
        seedPool.submit(() -> {
            try {
                Thread.sleep(1100);                                   // stay far below SkyCofl's rate limits
                String name = sample.name().replaceAll("§.", "").trim();
                String tag = tags.get(key);
                if (tag == null) { tag = Coflnet.tagOf(sample.uuid()); tags.put(key, tag); Thread.sleep(1100); }
                var rows = Coflnet.sold(tag, name);
                long cutoff = System.currentTimeMillis() - 86_400_000L;
                rows.removeIf(x -> x[0] < cutoff);
                synchronized (FlipEngine.this) {
                    var all = new ArrayList<long[]>(sales.getOrDefault(key, new ArrayDeque<>()));
                    all.addAll(rows);
                    all.sort(Comparator.comparingLong(x -> x[0]));
                    var merged = new ArrayDeque<long[]>();
                    long[] prev = null;
                    for (var x : all) {
                        if (prev != null && prev[0] == x[0] && prev[1] == x[1]) continue;
                        merged.add(x); prev = x;
                    }
                    sales.put(key, merged);
                }
                seededAt.put(key, System.currentTimeMillis());
                seedOk++;
                if (seedMsgs++ < 5)
                    GameBridge.say("§7" + (rows.isEmpty() ? "No matching past sales for " : "Loaded " + rows.size() + " past sales for ")
                            + name + " (sales data: SkyCofl, sky.coflnet.com)");
            } catch (Exception e) {
                seedFails++;
                lastSeedError = key.split("\\|")[0] + ": " + (e.getMessage() == null ? e.toString() : e.getMessage());
                seedFailUntil.put(key, System.currentTimeMillis() + 600_000L);   // retry in 10 min
            } finally {
                seeding.remove(key);
            }
        });
    }

    void evaluate(Config c, List<Auction> list, long now) {
        double obsH = Math.min(24, observedMs / 3.6e6);
        var bins = list.stream().filter(a -> a.bin() && !a.claimed() && a.end() > now && !ignored(c, a))
                .collect(Collectors.groupingBy(Auction::key));
        var st = new HashMap<String, Stats>();
        var out = new ArrayList<Flip>();
        int groups = 0, withHist = 0, needHist = 0, lowVol = 0, saturated = 0, unstable = 0, floor = 0, small = 0, big = 0;
        missProfit = Long.MIN_VALUE; missText = "none";
        for (var en : bins.entrySet()) {
            String key = en.getKey();
            var l = en.getValue().stream().sorted(Comparator.comparingLong(Auction::price)).toList();
            if (l.size() < 2) continue;
            groups++;
            var d = sales.get(key);
            double ob = seededAt.containsKey(key) ? 24 : obsH;
            if (d == null || d.size() < 3 || ob < c.minObservedMinutes / 60.0) {
                if (suspect(c, key, l)) needHist++;
                continue;
            }
            withHist++;
            double perHour = d.size() / ob;
            if (perHour * 24 < c.minSalesPerDay) { lowVol++; continue; }
            long[] prices = d.stream().mapToLong(x -> x[1]).sorted().toArray();
            long median = prices[prices.length / 2];
            int near = 0;
            for (long pr : prices) if (pr >= median * 0.85 && pr <= median * 1.15) near++;
            if (near < prices.length * c.minStability) { unstable++; continue; }   // sale prices too scattered to trust
            long fair = Math.min(median, l.get(1).price());      // conservative: lower of median sale / 2nd-lowest BIN
            double htc = l.size() / perHour;
            st.put(key, new Stats(fair, perHour, htc));
            if (htc > c.maxHoursToClear) { saturated++; continue; }   // too many listings to resell quickly
            int r = consider(c, l.get(0), l.get(0).price(), fair, perHour, htc, false, out);
            if (r == 1) floor++; else if (r == 2) small++; else if (r == 3) big++;
        }
        if (c.enableBids) for (var a : list) {
            if (a.bin() || a.claimed() || ignored(c, a)) continue;
            long left = (a.end() - now) / 1000;
            if (left > c.bidWindowSec || left < c.minBidWindowSec) continue;
            var s = st.get(a.key());
            if (s == null || s.hoursToClear() > c.maxHoursToClear) continue;
            consider(c, a, a.nextBid(), s.fair(), s.perHour(), s.hoursToClear(), true, out);
        }
        out.sort(Comparator.comparingDouble(Flip::score).reversed());
        stats = st; flips = out;
        debug = String.format("Scanned %,d auctions, %,d item groups with 2+ BINs. %d have sales history, %d look profitable but still need history (%d fetches running, %d done, %d failed). "
                        + "Rejected: %d low sales volume, %d saturated, %d unstable prices, %d profit/margin too small, %d margin too high, %d under price floor. PASSED: %d. Closest miss: %s.%s",
                list.size(), groups, withHist, needHist, seeding.size(), seedOk, seedFails, lowVol, saturated, unstable, small, big, floor, out.size(), missText,
                lastSeedError.isEmpty() ? "" : " Last SkyCofl error: " + lastSeedError);
    }

    /** 0 = added, 1 = under price floor, 2 = profit/margin too small, 3 = margin suspiciously high. */
    int consider(Config c, Auction a, long buy, long fair, double ph, double htc, boolean bid, List<Flip> out) {
        if (buy < c.minBuyPrice) return 1;
        long profit = fair - Math.round(fair * 0.01) - listFee(fair) - (long) c.extraFeeCoins - buy;
        double margin = 100.0 * profit / buy;
        if (profit < c.minProfit || margin < c.minMarginPct) {
            if (profit > missProfit) { missProfit = profit; missText = String.format("%s (buy %,d, fair %,d, profit %,d, %.1f%%)", a.name(), buy, fair, profit, margin); }
            return 2;
        }
        if (margin > c.maxMarginPct) return 3;
        double score = profit * Math.min(1, ph / 2.0) / (1 + htc);   // profit, weighted by how fast it sells
        if (bid) score *= 0.3;                                        // BIN buys always rank above bids
        out.add(new Flip(a, buy, fair, profit, margin, ph, htc, score, bid));
        return 0;
    }

    /** Creation fee tiers; verify against current game values. Claim tax is taken as 1%. */
    public static long listFee(long p) { return Math.round(p * (p < 10_000_000 ? 0.01 : p < 100_000_000 ? 0.02 : 0.025)); }
    static boolean ignored(Config c, Auction a) { return c.ignoredNames.stream().anyMatch(a.name()::contains); }
}
