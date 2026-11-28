package dev.flipper;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Past sold-auction data from SkyCofl (sky.coflnet.com), used to skip the warm-up.
 * Free tier limits are 30 requests / 10 s and 100 / min per IP, so callers must pace requests (FlipEngine does).
 */
public final class Coflnet {
    static final String BASE = "https://sky.coflnet.com/api/";
    static final HttpClient H = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    static JsonElement get(String path) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(BASE + path)).header("User-Agent", "ahflipper")
                .header("Accept", "application/json").timeout(Duration.ofSeconds(20)).build();
        var r = H.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 429) {
            long wait = r.headers().firstValue("retry-after").map(Long::parseLong).orElse(10L);
            Thread.sleep(Math.min(wait, 60) * 1000);
            throw new RuntimeException("rate limited");
        }
        if (r.statusCode() != 200) throw new RuntimeException("HTTP " + r.statusCode());
        return JsonParser.parseString(r.body());
    }

    /** Item tag (e.g. HYPERION) of an auction. */
    public static String tagOf(String auctionUuid) throws Exception {
        return get("auction/" + auctionUuid).getAsJsonObject().get("tag").getAsString();
    }

    /** Recent BIN sales for this tag whose item name matches, as [soldAtMs, price]. */
    public static List<long[]> sold(String tag, String name) throws Exception {
        var arr = get("auctions/tag/" + tag + "/sold?page=0&pageSize=500").getAsJsonArray();
        var out = new ArrayList<long[]>();
        for (var e : arr) {
            var a = e.getAsJsonObject();
            if (!a.has("bin") || !a.get("bin").getAsBoolean()) continue;
            String n = a.has("itemName") ? a.get("itemName").getAsString().replaceAll("§.", "").trim() : "";
            if (!n.equalsIgnoreCase(name)) continue;
            long price = a.has("highestBidAmount") && a.get("highestBidAmount").getAsLong() > 0
                    ? a.get("highestBidAmount").getAsLong() : a.get("startingBid").getAsLong();
            String end = a.get("end").getAsString();
            if (!end.endsWith("Z") && !end.contains("+")) end += "Z";
            out.add(new long[]{OffsetDateTime.parse(end).toInstant().toEpochMilli(), price});
        }
        return out;
    }
}
