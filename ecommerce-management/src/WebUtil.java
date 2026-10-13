import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.NumberFormat;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Small helpers: form parsing, HTML escaping, sessions, page layout, formatting. */
public class WebUtil {

    // ------------------------------------------------------------ sessions
    public static class SessionUser {
        public final int id;
        public final String username, fullName, role, city;

        public SessionUser(int id, String username, String fullName, String role, String city) {
            this.id = id; this.username = username; this.fullName = fullName; this.role = role; this.city = city;
        }
        public boolean isAdmin() { return "ADMIN".equals(role); }
    }

    private static final String COOKIE = "ECOM_SESSION";
    private static final Map<String, SessionUser> SESSIONS = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    public static SessionUser currentUser(HttpExchange ex) {
        String token = cookie(ex, COOKIE);
        return token == null ? null : SESSIONS.get(token);
    }

    public static void startSession(HttpExchange ex, SessionUser u) {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        String token = HexFormat.of().formatHex(b);
        SESSIONS.put(token, u);
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Lax");
    }

    public static void endSession(HttpExchange ex) {
        String token = cookie(ex, COOKIE);
        if (token != null) SESSIONS.remove(token);
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; Max-Age=0");
    }

    private static String cookie(HttpExchange ex, String name) {
        String header = ex.getRequestHeaders().getFirst("Cookie");
        if (header == null) return null;
        for (String part : header.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) return kv[1];
        }
        return null;
    }

    // ------------------------------------------------------------ request
    /** Query-string + url-encoded form body, merged into one map. */
    public static Map<String, String> params(HttpExchange ex) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        parseInto(m, ex.getRequestURI().getRawQuery());
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            parseInto(m, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void parseInto(Map<String, String> m, String s) {
        if (s == null || s.isEmpty()) return;
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            m.put(k, v.trim());
        }
    }

    public static int toInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    public static double toDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    // ------------------------------------------------------------ response
    public static void send(HttpExchange ex, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    public static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(303, -1);
        ex.close();
    }

    /** Redirect and show a green (msg) or red (err) banner on the next page. */
    public static void redirect(HttpExchange ex, String path, String key, String text) throws IOException {
        String anchor = "";
        int hash = path.indexOf('#');
        if (hash >= 0) { anchor = path.substring(hash); path = path.substring(0, hash); }
        String sep = path.contains("?") ? "&" : "?";
        redirect(ex, path + sep + key + "=" + URLEncoder.encode(text, StandardCharsets.UTF_8) + anchor);
    }

    // ------------------------------------------------------------ formatting
    private static final Locale INDIA = Locale.forLanguageTag("en-IN");

    public static String money(double v) {
        NumberFormat nf = NumberFormat.getCurrencyInstance(INDIA);
        return nf.format(v);
    }

    public static String num(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    public static String esc(Object o) {
        if (o == null) return "";
        String s = o.toString();
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String badge(String status) {
        return "<span class='badge b-" + esc(status == null ? "" : status.toLowerCase(Locale.ROOT)) + "'>" + esc(status) + "</span>";
    }

    // ------------------------------------------------------------ layout
    private static final String CSS = """
        :root{--bg:#f4f6fb;--card:#fff;--ink:#1d2433;--muted:#667085;--line:#e4e7ec;--brand:#4f46e5;--brand2:#4338ca;
              --ok:#067647;--okbg:#ecfdf3;--bad:#b42318;--badbg:#fef3f2;--warn:#b54708;--warnbg:#fffaeb}
        *{box-sizing:border-box} body{margin:0;font-family:Segoe UI,Roboto,Helvetica,Arial,sans-serif;background:var(--bg);color:var(--ink);font-size:15px}
        a{color:var(--brand)} header{background:#111827;color:#fff;padding:0 24px;display:flex;align-items:center;gap:20px;flex-wrap:wrap;min-height:58px}
        header .logo{font-weight:700;font-size:19px;letter-spacing:.3px} header .logo span{color:#a5b4fc}
        header nav{display:flex;gap:4px;flex-wrap:wrap;flex:1} header nav a{color:#d1d5db;text-decoration:none;padding:8px 12px;border-radius:8px}
        header nav a:hover{background:#1f2937;color:#fff} header .who{color:#9ca3af;font-size:13px} header .who b{color:#fff}
        main{max-width:1180px;margin:24px auto;padding:0 16px}
        h1{font-size:24px;margin:0 0 4px} h2{font-size:18px;margin:0 0 12px} .sub{color:var(--muted);margin:0 0 20px}
        .card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:20px;margin-bottom:20px;box-shadow:0 1px 2px rgba(16,24,40,.04)}
        .grid{display:grid;gap:16px} .g4{grid-template-columns:repeat(auto-fit,minmax(200px,1fr))} .g2{grid-template-columns:repeat(auto-fit,minmax(340px,1fr))}
        .kpi .v{font-size:26px;font-weight:700;margin-top:6px} .kpi .l{color:var(--muted);font-size:13px;text-transform:uppercase;letter-spacing:.5px}
        table{width:100%;border-collapse:collapse} th,td{padding:10px 8px;border-bottom:1px solid var(--line);text-align:left;vertical-align:middle}
        th{font-size:12px;text-transform:uppercase;letter-spacing:.5px;color:var(--muted);background:#f9fafb} td.r,th.r{text-align:right}
        .tw{overflow-x:auto}
        input,select,textarea{font:inherit;padding:9px 11px;border:1px solid #d0d5dd;border-radius:8px;background:#fff;width:100%}
        input:focus,select:focus{outline:2px solid #c7d2fe;border-color:var(--brand)}
        label{display:block;font-size:13px;font-weight:600;margin:12px 0 5px;color:#344054}
        .btn{display:inline-block;background:var(--brand);color:#fff;border:0;border-radius:8px;padding:10px 18px;font-weight:600;cursor:pointer;text-decoration:none;font-size:14px}
        .btn:hover{background:var(--brand2)} .btn.sec{background:#fff;color:var(--ink);border:1px solid #d0d5dd} .btn.red{background:#d92d20} .btn.sm{padding:6px 10px;font-size:13px}
        .inline{display:flex;gap:6px;align-items:center} .inline input,.inline select{width:auto}
        .msg{padding:12px 16px;border-radius:10px;margin-bottom:16px} .ok{background:var(--okbg);color:var(--ok)} .err{background:var(--badbg);color:var(--bad)} .warn{background:var(--warnbg);color:var(--warn)}
        .badge{display:inline-block;padding:3px 9px;border-radius:999px;font-size:12px;font-weight:600;background:#eef2ff;color:#3730a3}
        .b-placed{background:#eff8ff;color:#175cd3} .b-shipped{background:#fffaeb;color:#b54708} .b-delivered{background:#ecfdf3;color:#067647} .b-cancelled{background:#fef3f2;color:#b42318}
        .b-gold{background:#fef0c7;color:#93370d} .b-silver{background:#f2f4f7;color:#344054} .b-platinum{background:#e0eaff;color:#2d31a6} .b-regular{background:#f2f4f7;color:#667085}
        .login{max-width:420px;margin:60px auto} .muted{color:var(--muted)} .total td{font-weight:700;font-size:17px;border-top:2px solid var(--ink)}
        .decision{font-size:18px;font-weight:700;padding:14px 18px;border-radius:10px;margin-bottom:16px}
        .bar{height:8px;background:#eef2ff;border-radius:6px;overflow:hidden} .bar i{display:block;height:100%;background:var(--brand)}
        footer{text-align:center;color:var(--muted);font-size:12px;padding:24px}
        """;

    public static String page(String title, SessionUser u, Map<String, String> p, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang='en'><head><meta charset='utf-8'>")
          .append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
          .append("<title>").append(esc(title)).append(" | ShopEase</title><style>").append(CSS).append("</style></head><body>");
        sb.append("<header><div class='logo'>Shop<span>Ease</span></div>");
        if (u != null) {
            sb.append("<nav>");
            if (u.isAdmin()) {
                sb.append("<a href='/dashboard'>Dashboard</a><a href='/admin/products'>Products</a><a href='/orders'>Orders</a>")
                  .append("<a href='/admin/customers'>Customers</a><a href='/admin/coupons'>Coupons</a><a href='/admin/reports'>Reports</a>");
            } else {
                sb.append("<a href='/shop'>Shop</a><a href='/orders'>My Orders</a>");
            }
            sb.append("</nav><div class='who'>Logged in as <b>").append(esc(u.fullName)).append("</b> (")
              .append(esc(u.role)).append(") &nbsp; <a href='/logout' style='color:#a5b4fc'>Logout</a></div>");
        }
        sb.append("</header><main>");
        if (p != null) {
            if (p.get("msg") != null && !p.get("msg").isEmpty()) sb.append("<div class='msg ok'>").append(esc(p.get("msg"))).append("</div>");
            if (p.get("err") != null && !p.get("err").isEmpty()) sb.append("<div class='msg err'>").append(esc(p.get("err"))).append("</div>");
        }
        sb.append(body);
        sb.append("</main><footer>E-Commerce Management System &middot; Java + JDBC + Oracle Database</footer></body></html>");
        return sb.toString();
    }
}
