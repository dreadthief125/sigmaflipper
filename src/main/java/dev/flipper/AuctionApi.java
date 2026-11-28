package dev.flipper;

import com.google.gson.*;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

/** Public Hypixel endpoints, no API key needed. The server rebuilds its cache about once a minute. */
public final class AuctionApi {
    static final String BASE = "https://api.hypixel.net/v2/skyblock/";
    static final HttpClient H = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public record Sale(String uuid, long ts, long price, boolean bin) {}
    public record Page(long lastUpdated, int totalPages, List<Auction> auctions) {}
    /** Thrown when the cache refreshed in the middle of a fetch, so the pages don't match. */
    public static class Stale extends Exception {}

    static HttpRequest req(String path) {
        return HttpRequest.newBuilder(URI.create(BASE + path)).header("User-Agent", "ahflipper")
                .header("Accept-Encoding", "gzip").timeout(Duration.ofSeconds(20)).build();
    }

    static JsonObject decode(HttpResponse<byte[]> r) {
        try {
            if (r.statusCode() != 200) throw new RuntimeException("Hypixel API returned HTTP " + r.statusCode()
                    + (r.statusCode() == 429 ? " (rate limited, lower maxParallel in the config)" : ""));
            byte[] b = r.body();
            if (r.headers().firstValue("content-encoding").orElse("").contains("gzip"))
                b = new GZIPInputStream(new ByteArrayInputStream(b)).readAllBytes();
            return JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new RuntimeException(e); }
    }

    static JsonObject get(String path) throws Exception {
        return decode(H.send(req(path), HttpResponse.BodyHandlers.ofByteArray()));
    }

    static Page toPage(JsonObject o) {
        if (!o.has("auctions") || !o.has("lastUpdated"))
            throw new RuntimeException("Unexpected Hypixel reply: " + o.toString().substring(0, Math.min(120, o.toString().length())));
        return new Page(o.get("lastUpdated").getAsLong(), o.get("totalPages").getAsInt(), parse(o));
    }

    /** Page 0 doubles as the cheap "has the cache refreshed yet?" probe. */
    public static Page page0() throws Exception { return toPage(get("auctions?page=0")); }

    /** Every remaining page at once (up to maxParallel in flight), downloaded and parsed in parallel. */
    public static List<Auction> rest(Page first, int maxParallel, java.util.function.Consumer<List<Auction>> onPage) throws Exception {
        var out = new ArrayList<>(first.auctions());
        onPage.accept(first.auctions());
        var sem = new Semaphore(maxParallel);
        var futs = new ArrayList<CompletableFuture<Page>>();
        for (int p = 1; p < first.totalPages(); p++) {
            sem.acquire();
            futs.add(H.sendAsync(req("auctions?page=" + p), HttpResponse.BodyHandlers.ofByteArray())
                    .thenApply(r -> toPage(decode(r)))
                    .whenComplete((r, e) -> sem.release()));
        }
        for (var f : futs) {
            Page pg = f.get();
            if (pg.lastUpdated() != first.lastUpdated()) throw new Stale();
            onPage.accept(pg.auctions());
            out.addAll(pg.auctions());
        }
        return out;
    }

    static List<Auction> parse(JsonObject o) {
        var l = new ArrayList<Auction>();
        for (var e : o.getAsJsonArray("auctions")) {
            var a = e.getAsJsonObject();
            l.add(new Auction(a.get("uuid").getAsString(), a.get("item_name").getAsString(),
                    a.get("tier").getAsString(), a.get("starting_bid").getAsLong(),
                    a.has("highest_bid_amount") ? a.get("highest_bid_amount").getAsLong() : 0,
                    a.has("bin") && a.get("bin").getAsBoolean(), a.get("end").getAsLong(),
                    a.get("claimed").getAsBoolean()));
        }
        return l;
    }

    public static List<Sale> ended() throws Exception {
        var l = new ArrayList<Sale>();
        for (var e : get("auctions_ended").getAsJsonArray("auctions")) {
            var a = e.getAsJsonObject();
            l.add(new Sale(a.get("auction_id").getAsString(), a.get("timestamp").getAsLong(),
                    a.get("price").getAsLong(), a.has("bin") && a.get("bin").getAsBoolean()));
        }
        return l;
    }
}
