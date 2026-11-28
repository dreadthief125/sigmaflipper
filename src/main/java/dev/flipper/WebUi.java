package dev.flipper;

import com.google.gson.*;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * Local dashboard: a tiny HTTP server on java.net only (no extra modules), opened with /flip ui.
 * Every request needs the access token from the config, and without uiBindAll it only answers localhost.
 */
public final class WebUi {
    public static volatile boolean running = false;
    static ServerSocket server;
    static final Set<String> SETTINGS = new LinkedHashSet<>(List.of(
            "minProfit", "minMarginPct", "maxMarginPct", "minSalesPerDay", "maxHoursToClear", "minStability",
            "undercutPct", "maxFractionPerItem", "maxCommittedFraction", "reserveFraction", "maxOpenPositions",
            "minBuyPrice", "purseOverride", "autoRelist", "enableBids"));

    public static void start(Config cfg) {
        if (!cfg.uiEnabled) return;
        try {
            server = new ServerSocket(cfg.uiPort, 20, cfg.uiBindAll ? null : InetAddress.getLoopbackAddress());
        } catch (Exception e) {
            System.err.println("[ahflipper] dashboard could not start on port " + cfg.uiPort + ": " + e);
            return;
        }
        running = true;
        var pool = Executors.newCachedThreadPool(r -> { var t = new Thread(r, "ahflipper-ui"); t.setDaemon(true); return t; });
        var acc = new Thread(() -> {
            while (!server.isClosed()) {
                try { var s = server.accept(); pool.submit(() -> handle(s)); } catch (IOException e) { return; }
            }
        }, "ahflipper-ui-accept");
        acc.setDaemon(true);
        acc.start();
    }

    static String readLine(InputStream in) throws IOException {
        var sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') break;
            if (ch != '\r') sb.append((char) ch);
            if (sb.length() > 8192) throw new IOException("line too long");
        }
        return (ch == -1 && sb.length() == 0) ? null : sb.toString();
    }

    static String param(String query, String key) {
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(key)) return URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
        }
        return "";
    }

    static void respond(Socket s, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String status = switch (code) { case 200 -> "OK"; case 403 -> "Forbidden"; case 404 -> "Not Found"; case 413 -> "Payload Too Large"; default -> "Error"; };
        var out = s.getOutputStream();
        out.write(("HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type + "; charset=utf-8\r\nContent-Length: "
                + b.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    static void handle(Socket s) {
        try (s) {
            s.setSoTimeout(5000);
            var in = new BufferedInputStream(s.getInputStream());
            String line = readLine(in);
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String method = parts[0], target = parts[1];
            var headers = new HashMap<String, String>();
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int i = h.indexOf(':');
                if (i > 0) headers.put(h.substring(0, i).trim().toLowerCase(), h.substring(i + 1).trim());
            }
            int len = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            if (len > 65536) { respond(s, 413, "text/plain", "too large"); return; }
            byte[] body = in.readNBytes(len);
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            var cfg = FlipperMod.cfg;
            String host = headers.getOrDefault("host", "");
            if (!cfg.uiBindAll && !(host.startsWith("localhost") || host.startsWith("127.0.0.1"))) {
                respond(s, 403, "text/plain", "forbidden"); return;
            }
            if (!MessageDigest.isEqual(cfg.uiToken.getBytes(StandardCharsets.UTF_8), param(query, "t").getBytes(StandardCharsets.UTF_8))) {
                respond(s, 403, "text/plain", "Open the link printed by /flip ui in the game chat (it contains your access token).");
                return;
            }
            if (method.equals("GET") && path.equals("/")) respond(s, 200, "text/html", PAGE);
            else if (method.equals("GET") && path.equals("/api/state")) respond(s, 200, "application/json", state().toString());
            else if (method.equals("POST") && path.equals("/api/cmd")) {
                respond(s, 200, "application/json", cmd(JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject()));
            } else if (method.equals("POST") && path.equals("/api/config")) {
                applyConfig(JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject());
                respond(s, 200, "application/json", "{\"ok\":true}");
            } else respond(s, 404, "text/plain", "not found");
        } catch (Exception ignored) {}
    }

    static JsonObject state() {
        var e = FlipperMod.engine; var c = FlipperMod.cfg; var b = FlipperMod.budget;
        var o = new JsonObject();
        long purse = FlipperMod.purseCache;
        o.addProperty("on", FlipperMod.on);
        o.addProperty("auto", c.autoExecute);
        o.addProperty("purse", purse);
        o.addProperty("committed", b.committed());
        o.addProperty("sessionProfit", b.sessionProfit);
        o.addProperty("busy", FlipperMod.bot.statusText);
        o.addProperty("lastScanAgo", e.lastScanAt == 0 ? -1 : (System.currentTimeMillis() - e.lastScanAt) / 1000);
        o.addProperty("scanned", e.lastCount);
        o.addProperty("error", e.lastError);
        o.addProperty("debug", e.debug);
        var flips = new JsonArray();
        int n = 0;
        for (var f : e.flips) {
            if (n++ >= 40) break;
            var j = new JsonObject();
            j.addProperty("uuid", f.a().uuid());
            j.addProperty("name", f.a().name());
            j.addProperty("tier", f.a().tier());
            j.addProperty("buy", f.buy());
            j.addProperty("list", FlipperMod.recommended(f));
            j.addProperty("profit", f.profit());
            j.addProperty("margin", f.margin());
            j.addProperty("perHour", f.salesPerHour());
            j.addProperty("hours", f.hoursToClear());
            j.addProperty("ok", b.allows(c, f, purse));
            flips.add(j);
        }
        o.add("flips", flips);
        var pos = new JsonArray();
        for (var p : b.open) {
            var j = new JsonObject();
            j.addProperty("name", p.name); j.addProperty("cost", p.cost);
            j.addProperty("state", p.state.name()); j.addProperty("list", p.listPrice);
            pos.add(j);
        }
        o.add("positions", pos);
        var tr = new JsonArray();
        var all = b.trades;
        for (int i = all.size() - 1; i >= 0 && tr.size() < 25; i--) {
            var t = all.get(i);
            var j = new JsonObject();
            j.addProperty("name", t.name()); j.addProperty("cost", t.cost());
            j.addProperty("sold", t.sold()); j.addProperty("profit", t.profit());
            tr.add(j);
        }
        o.add("trades", tr);
        var set = new JsonObject();
        for (String k : SETTINGS) {
            try {
                Object v = Config.class.getField(k).get(c);
                if (v instanceof Boolean bo) set.addProperty(k, bo);
                else if (v instanceof Number nu) set.addProperty(k, nu);
            } catch (Exception ignored) {}
        }
        o.add("settings", set);
        var ign = new JsonArray();
        for (String s : c.ignoredNames) ign.add(s);
        o.add("ignored", ign);
        return o;
    }

    static String cmd(JsonObject j) {
        String cmd = j.get("cmd").getAsString();
        var mc = GameBridge.mc();
        switch (cmd) {
            case "on", "off", "stop" -> mc.execute(() -> FlipperMod.handle(cmd));
            case "auto" -> { boolean v = j.get("value").getAsBoolean(); mc.execute(() -> FlipperMod.handle("auto " + (v ? "on" : "off"))); }
            case "buy" -> FlipperMod.manualBuys.add(j.get("uuid").getAsString());
            case "ignore" -> {
                String uuid = j.get("uuid").getAsString();
                for (var f : FlipperMod.engine.flips) if (f.a().uuid().equals(uuid)) { setIgnored(f.a().name(), true); break; }
            }
            case "unignore" -> setIgnored(j.get("name").getAsString(), false);
            default -> { return "{\"ok\":false}"; }
        }
        return "{\"ok\":true}";
    }

    static void setIgnored(String name, boolean add) {
        var c = FlipperMod.cfg;
        var n = new ArrayList<>(c.ignoredNames);      // copy, so the scan thread never sees a half-edited list
        if (add) { if (!n.contains(name)) n.add(name); } else n.remove(name);
        c.ignoredNames = n;
        c.save();
    }

    static void applyConfig(JsonObject j) {
        var c = FlipperMod.cfg;
        for (var en : j.entrySet()) {
            if (!SETTINGS.contains(en.getKey())) continue;
            try {
                Field f = Config.class.getField(en.getKey());
                var v = en.getValue();
                if (f.getType() == boolean.class) f.setBoolean(c, v.getAsBoolean());
                else if (f.getType() == double.class) f.setDouble(c, Math.max(0, v.getAsDouble()));
                else if (f.getType() == long.class) f.setLong(c, Math.max(0, v.getAsLong()));
                else if (f.getType() == int.class) f.setInt(c, Math.max(0, v.getAsInt()));
            } catch (Exception ignored) {}
        }
        c.save();
    }

    static final String PAGE = """
<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>AH Flipper</title>
<style>
:root{--bg:#0f1117;--card:#181b24;--line:#262b38;--txt:#e8eaf0;--mut:#8b93a7;--ok:#3ddc84;--bad:#ff5d5d}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--txt);font:15px/1.4 system-ui,sans-serif}
header{position:sticky;top:0;z-index:5;background:var(--bg);border-bottom:1px solid var(--line);padding:12px 16px}
.row{display:flex;flex-wrap:wrap;gap:10px;align-items:center}
h1{font-size:18px;margin:0;flex:1}
.dot{width:10px;height:10px;border-radius:50%;background:var(--bad);display:inline-block}
.dot.ok{background:var(--ok)}
.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(130px,1fr));gap:10px;margin-top:10px}
.stat{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:8px 12px}
.stat b{display:block;font-size:17px}.stat span{color:var(--mut);font-size:12px}
button{font:inherit;cursor:pointer}
.btn{background:var(--card);color:var(--txt);border:1px solid var(--line);border-radius:10px;padding:8px 14px}
.btn.go{background:var(--ok);color:#06210f;border-color:var(--ok);font-weight:600}
.btn.stop{background:var(--bad);color:#2a0606;border-color:var(--bad);font-weight:600}
.btn.sm{padding:4px 10px;font-size:13px}
nav{display:flex;gap:6px;padding:10px 16px;overflow-x:auto}
.tab{background:none;border:0;color:var(--mut);padding:8px 14px;border-radius:999px;white-space:nowrap}
.tab.on{background:var(--card);color:var(--txt);border:1px solid var(--line)}
main{padding:0 16px 40px;max-width:1100px;margin:auto}
.banner{border-radius:10px;padding:10px 14px;margin:10px 0;background:var(--card);border:1px solid var(--line)}
.banner.err{border-color:var(--bad);color:var(--bad)}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:12px 14px;margin-bottom:10px}
.flip{display:grid;grid-template-columns:1fr auto;gap:8px}
h3{margin:0;font-size:16px}.mut{color:var(--mut);font-size:13px}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(105px,1fr));gap:6px;margin-top:6px}
.grid div span{display:block;color:var(--mut);font-size:11px}
.pos{color:var(--ok)}.neg{color:var(--bad)}
.tag{font-size:11px;padding:2px 8px;border-radius:999px;border:1px solid var(--line);color:var(--mut)}
table{width:100%;border-collapse:collapse}td,th{padding:6px 4px;border-bottom:1px solid var(--line);text-align:left;font-size:14px}
label.set{display:flex;justify-content:space-between;gap:10px;align-items:center;padding:8px 0;border-bottom:1px solid var(--line)}
input[type=number]{width:140px;background:var(--bg);color:var(--txt);border:1px solid var(--line);border-radius:8px;padding:6px}
pre{white-space:pre-wrap;color:var(--mut);margin:6px 0 0}
.chip{display:inline-block;margin:3px;padding:3px 10px;border:1px solid var(--line);border-radius:999px}
</style></head>
<body>
<header>
 <div class="row"><span id="conn" class="dot"></span><h1>AH Flipper</h1>
  <button id="bOn" class="btn" onclick="toggleOn()">Scanner</button>
  <button id="bAuto" class="btn" onclick="toggleAuto()">Auto-buy</button>
  <button class="btn" onclick="cmd('stop')">Stop task</button></div>
 <div class="stats">
  <div class="stat"><b id="purse">-</b><span>Purse</span></div>
  <div class="stat"><b id="held">-</b><span>Tied up in flips</span></div>
  <div class="stat"><b id="profit">-</b><span>Profit this session</span></div>
  <div class="stat"><b id="scan">-</b><span>Last scan</span></div>
 </div>
</header>
<nav id="nav"></nav>
<main><div id="banner"></div><div id="view"></div></main>
<script>
const T = new URLSearchParams(location.search).get('t') || '';
let S = null, tab = 'flips', built = false;
const $ = id => document.getElementById(id);
const fmt = n => n == null ? '-' : Math.round(n).toLocaleString();
const esc = s => String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
async function api(path, body) {
  const r = await fetch(path + '?t=' + encodeURIComponent(T), body ? {method:'POST', body: JSON.stringify(body)} : {});
  if (!r.ok) throw new Error(r.status);
  return r.json();
}
async function cmd(c, extra) { try { await api('/api/cmd', Object.assign({cmd:c}, extra || {})); } catch(e) {} refresh(); }
function toggleOn() { cmd(S && S.on ? 'off' : 'on'); }
function toggleAuto() {
  if (!S) return;
  if (!S.auto && !confirm('Auto-buy lets the mod click menus and spend coins by itself. Hypixel can ban accounts for automated clicking. Turn it on?')) return;
  cmd('auto', {value: !S.auto});
}
const TABS = [['flips','Flips'],['hold','Holdings'],['trades','Trades'],['set','Settings'],['dbg','Why nothing?']];
const FIELDS = [
 ['minProfit','Minimum profit per flip','coins. Flips below this are skipped','n'],
 ['minMarginPct','Minimum margin','percent of the buy price','n'],
 ['maxMarginPct','Maximum margin (safety cap)','very high margins are usually a different item (stars, enchants)','n'],
 ['minSalesPerDay','Minimum sales per day','items that sell less often are skipped','n'],
 ['maxHoursToClear','Max hours to sell the market','listings divided by sales per hour','n'],
 ['minStability','Price stability needed','0 to 1. Share of recent sales near the median','n'],
 ['undercutPct','Undercut when relisting','percent below the fair price','n'],
 ['maxFractionPerItem','Max share of portfolio per item','0 to 1. Portfolio = purse + coins in open flips','n'],
 ['maxCommittedFraction','Max share tied up in open flips','0 to 1','n'],
 ['reserveFraction','Always keep in purse','0 to 1','n'],
 ['maxOpenPositions','Max open flips','','n'],
 ['minBuyPrice','Ignore items cheaper than','coins','n'],
 ['purseOverride','Purse override','coins. 0 = read it from the game','n'],
 ['autoRelist','Relist bought items automatically','','b'],
 ['enableBids','Also bid on ending auctions','BIN buys always come first','b']];
function empty(t, d) { return `<div class="card"><h3>${t}</h3><div class="mut">${d}</div></div>`; }
function vFlips() {
  if (!S.on) return empty('Scanner is off', 'Press Scanner at the top, or type /flip on in game, to start looking for flips.');
  if (!S.flips.length) return empty('No flips right now', 'This is normal: good flips are rare. Open Why nothing? to see what is being filtered out. Past sales load from SkyCofl in the first minutes.');
  return S.flips.map(f => `<div class="card flip"><div><h3>${esc(f.name)} <span class="tag">${esc(f.tier)}</span></h3>
   <div class="grid"><div><span>Buy at</span>${fmt(f.buy)}</div><div><span>Sell at</span>${fmt(f.list)}</div>
   <div><span>Profit</span><b class="pos">+${fmt(f.profit)}</b></div><div><span>Margin</span>${f.margin.toFixed(1)}%</div>
   <div><span>Sales per hour</span>${f.perHour.toFixed(1)}</div><div><span>Market clears in</span>${f.hours.toFixed(1)} h</div></div>
   ${f.ok ? '' : '<div class="mut">Blocked by your budget rules right now</div>'}</div>
   <div><button class="btn sm go" onclick="cmd('buy',{uuid:'${f.uuid}'})">Buy</button><br><br><button class="btn sm" onclick="cmd('ignore',{uuid:'${f.uuid}'})">Ignore item</button></div></div>`).join('');
}
function vHold() {
  if (!S.positions.length) return empty('Nothing held', 'Items you bought and have not sold yet show up here.');
  return S.positions.map(p => `<div class="card"><h3>${esc(p.name)} <span class="tag">${p.state}</span></h3><div class="mut">Paid ${fmt(p.cost)}${p.list ? ' / listed at ' + fmt(p.list) : ''}</div></div>`).join('');
}
function vTrades() {
  if (!S.trades.length) return empty('No sales yet', 'Completed flips and their profit appear here once something sells.');
  return `<div class="card"><table><tr><th>Item</th><th>Paid</th><th>Sold</th><th>Profit</th></tr>` + S.trades.map(t => `<tr><td>${esc(t.name)}</td><td>${fmt(t.cost)}</td><td>${fmt(t.sold)}</td><td class="${t.profit>=0?'pos':'neg'}">${t.profit>=0?'+':''}${fmt(t.profit)}</td></tr>`).join('') + `</table></div>`;
}
function vDbg() {
  return `<div class="card"><h3>Last scan breakdown</h3><pre>${esc(S.debug)}</pre></div><div class="card"><h3>Ignored items</h3>` +
   (S.ignored.map(n => `<span class="chip">${esc(n)} <button class="btn sm" data-n="${esc(n)}" onclick="cmd('unignore',{name:this.dataset.n})">x</button></span>`).join('') || '<span class="mut">None</span>') + `</div>`;
}
function renderSettings() {
  if (!built) {
    $('view').innerHTML = `<div class="card">` + FIELDS.map(f => f[3] === 'b'
      ? `<label class="set"><span>${f[1]}<br><span class="mut">${f[2]}</span></span><input type="checkbox" id="f_${f[0]}" onchange="setv('${f[0]}', this.checked)"></label>`
      : `<label class="set"><span>${f[1]}<br><span class="mut">${f[2]}</span></span><input type="number" step="any" id="f_${f[0]}" onchange="setv('${f[0]}', Number(this.value))"></label>`).join('') + `</div>`;
    built = true;
  }
  FIELDS.forEach(f => {
    const el = $('f_' + f[0]);
    if (!el || el === document.activeElement || S.settings[f[0]] === undefined) return;
    if (f[3] === 'b') el.checked = S.settings[f[0]]; else el.value = S.settings[f[0]];
  });
}
function setv(k, v) { api('/api/config', {[k]: v}).catch(() => {}); }
function render() {
  if (!S) return;
  $('purse').textContent = S.purse < 0 ? 'unknown' : fmt(S.purse);
  $('held').textContent = fmt(S.committed);
  const p = S.sessionProfit;
  $('profit').textContent = (p >= 0 ? '+' : '') + fmt(p);
  $('profit').className = p >= 0 ? 'pos' : 'neg';
  $('scan').textContent = S.lastScanAgo < 0 ? 'none yet' : S.lastScanAgo + 's ago';
  $('bOn').textContent = S.on ? 'Scanner: ON' : 'Scanner: OFF';
  $('bOn').className = 'btn ' + (S.on ? 'go' : '');
  $('bAuto').textContent = S.auto ? 'Auto-buy: ON' : 'Auto-buy: OFF';
  $('bAuto').className = 'btn ' + (S.auto ? 'stop' : '');
  $('nav').innerHTML = TABS.map(t => `<button class="tab ${tab === t[0] ? 'on' : ''}" onclick="tab='${t[0]}';built=false;render()">${t[1]}</button>`).join('');
  let b = `<div class="banner">${esc(S.busy)} / ${fmt(S.scanned)} auctions in the last scan</div>`;
  if (S.error) b += `<div class="banner err">Scan problem: ${esc(S.error)}</div>`;
  $('banner').innerHTML = b;
  if (tab === 'set') { renderSettings(); return; }
  $('view').innerHTML = ({flips: vFlips, hold: vHold, trades: vTrades, dbg: vDbg})[tab]();
}
async function refresh() {
  try { S = await api('/api/state'); $('conn').className = 'dot ok'; render(); } catch (e) { $('conn').className = 'dot'; }
}
refresh();
setInterval(refresh, 1000);
</script></body></html>
""";
}
