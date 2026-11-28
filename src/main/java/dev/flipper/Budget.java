package dev.flipper;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.*;

public final class Budget {
    public enum State { BOUGHT, BID, LISTED }
    public static final class Position {
        public final String uuid, key, name; public final long cost, endMs;
        public volatile State state; public volatile long listPrice; public int failures;
        public Position(String uuid, String key, String name, long cost, State s, long endMs) {
            this.uuid = uuid; this.key = key; this.name = name; this.cost = cost; this.state = s; this.endMs = endMs;
        }
    }
    public record Trade(long time, String name, long cost, long soldFor, long profit) {}

    // copy-on-write: the dashboard thread reads these while the game thread changes them
    public final List<Position> open = new CopyOnWriteArrayList<>();
    public final List<Trade> history = new CopyOnWriteArrayList<>();
    public volatile long sessionProfit = 0;
    static final Pattern FOR_COINS = Pattern.compile("for ([\\d,]+) coins");

    public long committed() { return open.stream().mapToLong(p -> p.cost).sum(); }

    /** Portfolio = purse + coins sitting in open flips, so a big purchase can't hide by shrinking the purse. */
    public boolean allows(Config c, FlipEngine.Flip f, long purse) {
        if (purse <= 0) return false;
        long committed = committed(), port = purse + committed, price = f.buy();
        if (price > port * c.maxFractionPerItem) return false;
        if (purse - price < port * c.reserveFraction) return false;
        if (committed + price > port * c.maxCommittedFraction) return false;
        if (open.size() >= c.maxOpenPositions) return false;
        return open.stream().filter(p -> p.key.equals(f.a().key())).count() < c.maxPerItemKey;
    }

    /** "[Auction] Name bought Item for 1,234 coins": book the profit and free the slot. */
    public void sold(String msg) {
        for (var p : open) {
            if (p.state != State.LISTED || !msg.contains(p.name)) continue;
            long price = p.listPrice;
            Matcher m = FOR_COINS.matcher(msg);
            if (m.find()) price = Long.parseLong(m.group(1).replace(",", ""));
            long net = price - Math.round(price * 0.01) - FlipEngine.listFee(price) - p.cost;
            history.add(new Trade(System.currentTimeMillis(), p.name, p.cost, price, net));
            sessionProfit += net;
            open.remove(p);
            GameBridge.say(String.format("§a§lSOLD §r§a%s for %,d (profit ~%,d)", p.name, price, net));
            return;
        }
    }
}
