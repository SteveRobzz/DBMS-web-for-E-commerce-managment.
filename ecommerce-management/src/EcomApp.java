import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;

/**
 * E-Commerce Management System - web application (Java + JDBC + Oracle).
 *
 * Uses the HTTP server that is built into the JDK (com.sun.net.httpserver),
 * so no Tomcat / servlet container is needed. Every page talks to Oracle through
 * JDBC using the 6 steps from the lab:
 *   1 load driver, 2 get connection (DBConnection), 3 create Statement / PreparedStatement,
 *   4 execute SQL, 5 process ResultSet, 6 close (try-with-resources).
 *
 * Roles
 *   ADMIN    : dashboard, product CRUD, order status, customers, coupons, reports
 *   CUSTOMER : browse/search products, build a cart, get a computed bill, place / cancel orders
 */
public class EcomApp {

    public static void main(String[] args) throws Exception {
        String propsFile = args.length > 0 ? args[0] : "db.properties";
        DBConnection.init(propsFile);

        Properties props = new Properties();
        try (InputStream in = new FileInputStream(propsFile)) { props.load(in); }
        int port = Integer.parseInt(props.getProperty("server.port", "8080").trim());

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", EcomApp::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("==================================================");
        System.out.println(" E-Commerce Management System is running");
        System.out.println(" Open  http://localhost:" + port + "  in your browser");
        System.out.println(" Press Ctrl+C to stop");
        System.out.println("==================================================");
    }

    // =====================================================================
    //  ROUTING
    // =====================================================================
    private static void handle(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            boolean post = "POST".equalsIgnoreCase(ex.getRequestMethod());
            Map<String, String> p = WebUtil.params(ex);
            WebUtil.SessionUser u = WebUtil.currentUser(ex);

            switch (path) {
                case "/":            WebUtil.redirect(ex, u == null ? "/login" : "/dashboard"); return;
                case "/login":       if (post) doLogin(ex, p); else showLogin(ex, p, null); return;
                case "/register":    if (post) doRegister(ex, p); else showRegister(ex, p, null); return;
                case "/logout":      WebUtil.endSession(ex); WebUtil.redirect(ex, "/login", "msg", "You have been logged out."); return;
                case "/favicon.ico": ex.sendResponseHeaders(404, -1); ex.close(); return;
                default: break;
            }

            if (u == null) { WebUtil.redirect(ex, "/login", "err", "Please login first."); return; }

            switch (path) {
                case "/dashboard":    if (u.isAdmin()) adminDashboard(ex, u, p); else WebUtil.redirect(ex, "/shop"); return;
                case "/orders":       orders(ex, u, p); return;
                case "/order":        orderDetail(ex, u, p); return;
                case "/order/cancel": if (post) cancelOrderAction(ex, u, p); else WebUtil.redirect(ex, "/orders"); return;
                default: break;
            }

            if (!u.isAdmin()) {
                switch (path) {
                    case "/shop":        shop(ex, u, p); return;
                    case "/checkout":    if (post) checkout(ex, u, p); else WebUtil.redirect(ex, "/shop"); return;
                    case "/place-order": if (post) placeOrder(ex, u, p); else WebUtil.redirect(ex, "/shop"); return;
                    default: notFound(ex, u); return;
                }
            }

            switch (path) {
                case "/admin/products":        adminProducts(ex, u, p); return;
                case "/admin/products/add":    if (post) addProduct(ex, p); else WebUtil.redirect(ex, "/admin/products"); return;
                case "/admin/products/update": if (post) updateProduct(ex, p); else WebUtil.redirect(ex, "/admin/products"); return;
                case "/admin/products/delete": if (post) deleteProduct(ex, p); else WebUtil.redirect(ex, "/admin/products"); return;
                case "/admin/orders/status":   if (post) updateOrderStatus(ex, p); else WebUtil.redirect(ex, "/orders"); return;
                case "/admin/customers":       adminCustomers(ex, u, p); return;
                case "/admin/coupons":         adminCoupons(ex, u, p); return;
                case "/admin/coupons/add":     if (post) addCoupon(ex, p); else WebUtil.redirect(ex, "/admin/coupons"); return;
                case "/admin/coupons/delete":  if (post) deleteCoupon(ex, p); else WebUtil.redirect(ex, "/admin/coupons"); return;
                case "/admin/reports":         adminReports(ex, u, p); return;
                default: notFound(ex, u);
            }
        } catch (Exception e) {
            e.printStackTrace();
            try {
                String body = "<div class='card'><h1>Something went wrong</h1><div class='msg err'>"
                        + WebUtil.esc(e.getClass().getSimpleName() + ": " + e.getMessage())
                        + "</div><a class='btn sec' href='/'>Back to home</a></div>";
                WebUtil.send(ex, 500, WebUtil.page("Error", WebUtil.currentUser(ex), null, body));
            } catch (Exception ignored) { }
        }
    }

    private static void notFound(HttpExchange ex, WebUtil.SessionUser u) throws Exception {
        WebUtil.send(ex, 404, WebUtil.page("Not found", u, null,
                "<div class='card'><h1>Page not found</h1><p class='muted'>The page does not exist or you do not have access to it.</p>"
                + "<a class='btn sec' href='/'>Home</a></div>"));
    }

    // =====================================================================
    //  LOGIN / REGISTER
    // =====================================================================
    private static void showLogin(HttpExchange ex, Map<String, String> p, String error) throws Exception {
        String body = "<div class='login card'><h1>Sign in</h1><p class='sub'>E-Commerce Management System</p>"
                + (error != null ? "<div class='msg err'>" + WebUtil.esc(error) + "</div>" : "")
                + "<form method='post' action='/login'>"
                + "<label>Username</label><input name='username' required autofocus value='" + WebUtil.esc(p.getOrDefault("username", "")) + "'>"
                + "<label>Password</label><input name='password' type='password' required>"
                + "<p><button class='btn' style='width:100%'>Login</button></p></form>"
                + "<p class='muted'>New customer? <a href='/register'>Create an account</a></p>"
                + "<div class='msg warn' style='font-size:13px'><b>Demo logins</b><br>Admin: admin / admin123<br>Customer: arjun / arjun123</div></div>";
        WebUtil.send(ex, error == null ? 200 : 401, WebUtil.page("Login", null, p, body));
    }

    private static void doLogin(HttpExchange ex, Map<String, String> p) throws Exception {
        String username = p.getOrDefault("username", "").toLowerCase(Locale.ROOT);
        String password = p.getOrDefault("password", "");
        if (username.isEmpty() || password.isEmpty()) { showLogin(ex, p, "Enter username and password."); return; }

        // PreparedStatement prevents SQL injection on the login form
        String sql = "SELECT USER_ID, USERNAME, FULL_NAME, USER_ROLE, CITY FROM APP_USERS "
                   + "WHERE USERNAME = ? AND PASSWORD_HASH = ?";
        try (Connection con = DBConnection.getConnection();               // Step 2
             PreparedStatement ps = con.prepareStatement(sql)) {          // Step 3
            ps.setString(1, username);
            ps.setString(2, WebUtil.sha256(password));
            try (ResultSet rs = ps.executeQuery()) {                      // Step 4
                if (rs.next()) {                                          // Step 5
                    WebUtil.SessionUser u = new WebUtil.SessionUser(rs.getInt("USER_ID"), rs.getString("USERNAME"),
                            rs.getString("FULL_NAME"), rs.getString("USER_ROLE"), rs.getString("CITY"));
                    WebUtil.startSession(ex, u);
                    WebUtil.redirect(ex, u.isAdmin() ? "/dashboard" : "/shop");
                    return;
                }
            }
        }                                                                 // Step 6 (auto close)
        showLogin(ex, p, "Invalid username or password.");
    }

    private static void showRegister(HttpExchange ex, Map<String, String> p, String error) throws Exception {
        String body = "<div class='login card' style='max-width:520px'><h1>Create account</h1><p class='sub'>Register as a customer</p>"
                + (error != null ? "<div class='msg err'>" + WebUtil.esc(error) + "</div>" : "")
                + "<form method='post' action='/register'>"
                + field("Full name", "full_name", "text", p, "required")
                + field("Username (letters, numbers, _ only)", "username", "text", p, "required pattern='[A-Za-z0-9_]{3,30}'")
                + field("Email", "email", "email", p, "required")
                + field("Phone (10 digits)", "phone", "text", p, "required pattern='[0-9]{10}'")
                + field("City", "city", "text", p, "required")
                + "<label>Password (min 6 characters)</label><input name='password' type='password' minlength='6' required>"
                + "<label>Confirm password</label><input name='confirm' type='password' minlength='6' required>"
                + "<p><button class='btn' style='width:100%'>Register</button></p></form>"
                + "<p class='muted'>Already registered? <a href='/login'>Login</a></p></div>";
        WebUtil.send(ex, error == null ? 200 : 400, WebUtil.page("Register", null, null, body));
    }

    private static String field(String label, String name, String type, Map<String, String> p, String attrs) {
        return "<label>" + label + "</label><input name='" + name + "' type='" + type + "' " + attrs
                + " value='" + WebUtil.esc(p.getOrDefault(name, "")) + "'>";
    }

    private static void doRegister(HttpExchange ex, Map<String, String> p) throws Exception {
        String fullName = p.getOrDefault("full_name", "");
        String username = p.getOrDefault("username", "").toLowerCase(Locale.ROOT);
        String email    = p.getOrDefault("email", "").toLowerCase(Locale.ROOT);
        String phone    = p.getOrDefault("phone", "");
        String city     = p.getOrDefault("city", "");
        String pass     = p.getOrDefault("password", "");

        if (fullName.isEmpty() || city.isEmpty()) { showRegister(ex, p, "All fields are required."); return; }
        if (!username.matches("[a-z0-9_]{3,30}")) { showRegister(ex, p, "Username must be 3-30 letters, numbers or _."); return; }
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) { showRegister(ex, p, "Enter a valid email address."); return; }
        if (!phone.matches("[0-9]{10}")) { showRegister(ex, p, "Phone must be 10 digits."); return; }
        if (pass.length() < 6) { showRegister(ex, p, "Password must be at least 6 characters."); return; }
        if (!pass.equals(p.getOrDefault("confirm", ""))) { showRegister(ex, p, "Passwords do not match."); return; }

        try (Connection con = DBConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT COUNT(*) FROM APP_USERS WHERE USERNAME = ? OR EMAIL = ?")) {
                ps.setString(1, username);
                ps.setString(2, email);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) { showRegister(ex, p, "Username or email already registered."); return; }
                }
            }
            // DML: INSERT
            String sql = "INSERT INTO APP_USERS (USERNAME, PASSWORD_HASH, FULL_NAME, EMAIL, PHONE, CITY, USER_ROLE) "
                       + "VALUES (?, ?, ?, ?, ?, ?, 'CUSTOMER')";
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, username);
                ps.setString(2, WebUtil.sha256(pass));
                ps.setString(3, fullName);
                ps.setString(4, email);
                ps.setString(5, phone);
                ps.setString(6, city);
                ps.executeUpdate();
            }
        }
        WebUtil.redirect(ex, "/login", "msg", "Account created. Please login.");
    }

    // =====================================================================
    //  CUSTOMER: SHOP -> CHECKOUT (bill) -> PLACE ORDER
    // =====================================================================
    private static void shop(HttpExchange ex, WebUtil.SessionUser u, Map<String, String> p) throws Exception {
        int cat = WebUtil.toInt(p.getOrDefault("cat", ""), 0);
        String q = p.getOrDefault("q", "");
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>Shop</h1><p class='sub'>Enter quantities, choose payment and delivery, then calculate your bill.</p>");

        try (Connection con = DBConnection.getConnection()) {
            // Loyalty information
            double spend = Billing.lifetimeSpend(con, u.id);
            String tier = Billing.tierFor(spend);
            double next = spend < 5000 ? 5000 : spend < 20000 ? 20000 : spend < 50000 ? 50000 : 0;
            sb.append("<div class='grid g2'><div class='card'><h2>Your membership</h2><p>")
              .append(WebUtil.badge(tier)).append(" &nbsp; ").append(WebUtil.num(Billing.tierPct(tier)))
              .append("% loyalty discount on every order</p><p class='muted'>Lifetime spend: <b>").append(WebUtil.money(spend)).append("</b>");
            if (next > 0) sb.append(" &middot; spend ").append(WebUtil.money(next - spend)).append(" more to reach ")
                            .append(Billing.tierFor(next));
            sb.append("</p></div>");

            // Valid coupons
            sb.append("<div class='card'><h2>Available coupons</h2><table><tr><th>Code</th><th>Offer</th><th>Min order</th><th>Valid till</th></tr>");
            String csql = "SELECT COUPON_CODE, DISCOUNT_PCT, MAX_DISCOUNT, MIN_ORDER_VALUE, TO_CHAR(VALID_TILL, 'DD-MON-YYYY') AS VT "
                        + "FROM COUPONS WHERE IS_ACTIVE = 'Y' AND VALID_TILL >= TRUNC(SYSDATE) ORDER BY DISCOUNT_PCT";
            try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(csql)) {
                while (rs.next()) {
                    sb.append("<tr><td><b>").append(WebUtil.esc(rs.getString(1))).append("</b></td><td>")
                      .append(WebUtil.num(rs.getDouble(2))).append("% off, up to ").append(WebUtil.money(rs.getDouble(3)))
                      .append("</td><td>").append(WebUtil.money(rs.getDouble(4))).append("</td><td>")
                      .append(WebUtil.esc(rs.getString(5))).append("</td></tr>");
                }
            }
            sb.append("</table></div></div>");

            // Search / filter form
            sb.append("<div class='card'><form method='get' action='/shop' class='inline' style='flex-wrap:wrap'>")
              .append("<input name='q' placeholder='Search products' value='").append(WebUtil.esc(q)).append("' style='min-width:220px'>")
              .append("<select name='cat'><option value='0'>All categories</option>");
            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("SELECT CATEGORY_ID, CATEGORY_NAME FROM CATEGORIES ORDER BY CATEGORY_NAME")) {
                while (rs.next()) {
                    int id = rs.getInt(1);
                    sb.append("<option value='").append(id).append("'").append(id == cat ? " selected" : "").append(">")
                      .append(WebUtil.esc(rs.getString(2))).append("</option>");
                }
            }
            sb.append("</select><button class='btn sec'>Filter</button></form></div>");

            // Product list with quantity inputs
            String sql = "SELECT p.PRODUCT_ID, p.PRODUCT_NAME, p.BRAND, p.DESCRIPTION, c.CATEGORY_NAME, p.PRICE, c.GST_RATE, p.STOCK "
                       + "FROM PRODUCTS p JOIN CATEGORIES c ON c.CATEGORY_ID = p.CATEGORY_ID "
                       + "WHERE p.IS_ACTIVE = 'Y' AND (? = 0 OR p.CATEGORY_ID = ?) AND UPPER(p.PRODUCT_NAME) LIKE ? "
                       + "ORDER BY c.CATEGORY_NAME, p.PRODUCT_NAME";
            sb.append("<form method='post' action='/checkout'><div class='card'><h2>Products</h2><div class='tw'><table>")
              .append("<tr><th>Product</th><th>Category</th><th class='r'>Price</th><th class='r'>GST</th><th class='r'>In stock</th><th style='width:110px'>Quantity</th></tr>");
            int count = 0;
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, cat);
                ps.setInt(2, cat);
                ps.setString(3, "%" + q.toUpperCase(Locale.ROOT) + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        count++;
                        int stock = rs.getInt("STOCK");
                        int max = Math.min(stock, Billing.MAX_QTY_PER_ITEM);
                        sb.append("<tr><td><b>").append(WebUtil.esc(rs.getString("PRODUCT_NAME"))).append("</b><br><span class='muted' style='font-size:13px'>")
                          .append(WebUtil.esc(rs.getString("BRAND"))).append(rs.getString("BRAND") != null ? " &middot; " : "")
                          .append(WebUtil.esc(rs.getString("DESCRIPTION"))).append("</span></td><td>")
                          .append(WebUtil.esc(rs.getString("CATEGORY_NAME"))).append("</td><td class='r'>")
                          .append(WebUtil.money(rs.getDouble("PRICE"))).append("</td><td class='r'>")
                          .append(WebUtil.num(rs.getDouble("GST_RATE"))).append("%</td><td class='r'>")
                          .append(stock == 0 ? "<span class='badge b-cancelled'>Out of stock</span>"
                                  : stock < 10 ? "<span class='badge b-shipped'>Only " + stock + " left</span>" : String.valueOf(stock))
                          .append("</td><td><input type='number' name='qty_").append(rs.getInt("PRODUCT_ID"))
                          .append("' min='0' max='").append(max).append("' value='0'").append(stock == 0 ? " disabled" : "").append("></td></tr>");
                    }
                }
            }
            if (count == 0) sb.append("<tr><td colspan='6' class='muted'>No products match your search.</td></tr>");
            sb.append("</table></div></div>");

            sb.append("<div class='card'><h2>Delivery and payment</h2><div class='grid g4'>")
              .append("<div><label>Delivery city</label><input name='city' required value='").append(WebUtil.esc(u.city)).append("'></div>")
              .append("<div><label>Payment mode</label><select name='payment'>")
              .append("<option value='UPI'>UPI</option><option value='CARD'>Credit / Debit Card</option>")
              .append("<option value='NETBANKING'>Net Banking</option><option value='COD'>Cash on Delivery (+&#8377;30)</option></select></div>")
              .append("<div><label>Coupon code (optional)</label><input name='coupon' placeholder='e.g. WELCOME10'></div>")
              .append("<div><label>&nbsp;</label><button class='btn' style='width:100%'>Calculate bill &rarr;</button></div></div>")
              .append("<p class='muted' style='font-size:13px;margin-bottom:0'>Free delivery above &#8377;999 &middot; otherwise &#8377;40 in metro cities, &#8377;80 elsewhere &middot; ")
              .append("COD not available above &#8377;20,000 &middot; max 10 units per product.</p></div></form>");
        }
        WebUtil.send(ex, 200, WebUtil.page("Shop", u, p, sb.toString()));
    }

    /** Reads all qty_<productId> fields with a positive value. */
    private static Map<Integer, Integer> readCart(Map<String, String> p) {
        Map<Integer, Integer> cart = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (e.getKey().startsWith("qty_")) {
                int id = WebUtil.toInt(e.getKey().substring(4), -1);
                int qty = WebUtil.toInt(e.getValue(), 0);
                if (id > 0 && qty > 0) cart.put(id, qty);
            }
        }
        return cart;
    }

    private static void checkout(HttpExchange ex, WebUtil.SessionUser u, Map<String, String> p) throws Exception {
        Map<Integer, Integer> cart = readCart(p);
        if (cart.isEmpty()) { WebUtil.redirect(ex, "/shop", "err", "Enter a quantity for at least one product."); return; }
        Billing.Bill bill;
        try (Connection con = DBConnection.getConnection()) {
            bill = Billing.compute(con, u.id, cart, p.get("coupon"), p.get("payment"), p.get("city"));
        }
        WebUtil.send(ex, 200, WebUtil.page("Your bill", u, p, renderBill(bill, p)));
    }

    private static String renderBill(Billing.Bill b, Map<String, String> p) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>Order summary</h1><p class='sub'>Calculated from your inputs, current stock and the store rules.</p>");
        if (b.canPlaceOrder())
            sb.append("<div class='decision ok'>&#10004; Order can be placed &mdash; amount payable ").append(WebUtil.money(b.total)).append("</div>");
        else
            sb.append("<div class='decision err'>&#10008; Order cannot be placed</div>");
        for (String e : b.errors) sb.append("<div class='msg err'>").append(WebUtil.esc(e)).append("</div>");
        for (String n : b.notes)  sb.append("<div class='msg warn'>").append(WebUtil.esc(n)).append("</div>");

        sb.append("<div class='grid g2'><div class='card'><h2>Items</h2><div class='tw'><table><tr><th>Product</th><th class='r'>Qty</th>")
          .append("<th class='r'>Unit price</th><th class='r'>Amount</th><th class='r'>GST</th></tr>");
        for (Billing.Line l : b.lines) {
            sb.append("<tr><td>").append(WebUtil.esc(l.name)).append("<br><span class='muted' style='font-size:12px'>")
              .append(WebUtil.esc(l.category)).append(" &middot; GST ").append(WebUtil.num(l.gstRate)).append("%</span></td><td class='r'>")
              .append(l.quantity).append("</td><td class='r'>").append(WebUtil.money(l.unitPrice)).append("</td><td class='r'>")
              .append(WebUtil.money(l.lineTotal)).append("</td><td class='r'>").append(WebUtil.money(l.lineGst)).append("</td></tr>");
        }
        sb.append("</table></div></div><div class='card'><h2>Bill</h2><table>")
          .append(row("Subtotal", WebUtil.money(b.subtotal)))
          .append(row("Loyalty discount (" + b.tier + ", " + WebUtil.num(b.tierPct) + "%)", "&minus; " + WebUtil.money(b.tierDiscount)))
          .append(row("Coupon discount" + (b.couponCode != null ? " (" + WebUtil.esc(b.couponCode) + ")" : ""), "&minus; " + WebUtil.money(b.couponDiscount)))
          .append(row("GST", WebUtil.money(b.gst)))
          .append(row("Delivery (" + WebUtil.esc(b.city) + (b.metro ? ", metro" : ", non-metro") + ")", WebUtil.money(b.shipping)))
          .append(row("COD charge", WebUtil.money(b.codCharge)))
          .append("<tr class='total'><td>Total payable</td><td class='r'>").append(WebUtil.money(b.total)).append("</td></tr></table>")
          .append("<p class='muted'>Payment: <b>").append(WebUtil.esc(b.paymentMode)).append("</b></p>");

        // Hidden fields carry the inputs (not the totals) - the server recalculates on "Place order"
        sb.append("<form method='post' action='/place-order'>");
        for (Billing.Line l : b.lines)
            sb.append("<input type='hidden' name='qty_").append(l.productId).append("' value='").append(l.quantity).append("'>");
        sb.append(hidden("coupon", p.get("coupon"))).append(hidden("payment", p.get("payment"))).append(hidden("city", p.get("city")));
        sb.append("<div class='inline'>");
        if (b.canPlaceOrder()) sb.append("<button class='btn'>Place order</button>");
        sb.append("<a class='btn sec' href='/shop'>Back to shop</a></div></form></div></div>");
        return sb.toString();
    }

    private static String row(String label, String value) {
        return "<tr><td>" + label + "</td><td class='r'>" + value + "</td></tr>";
    }

    private static String hidden(String name, String value) {
        return "<input type='hidden' name='" + name + "' value='" + WebUtil.esc(value == null ? "" : value) + "'>";
    }

    /** Places the order inside ONE transaction: INSERT order, INSERT items (batch), UPDATE stock. */
    private static void placeOrder(HttpExchange ex, WebUtil.SessionUser u, Map<String, String> p) throws Exception {
        Map<Integer, Integer> cart = readCart(p);
        try (Connection con = DBConnection.getConnection()) {
            Billing.Bill b = Billing.compute(con, u.id, cart, p.get("coupon"), p.get("payment"), p.get("city"));
            if (!b.canPlaceOrder()) {
                WebUtil.send(ex, 200, WebUtil.page("Your bill", u, p, renderBill(b, p)));
                return;
            }
            con.setAutoCommit(false);                       // start transaction
            try {
                // 1. reduce stock - the WHERE STOCK >= ? guard stops overselling
                try (PreparedStatement ps = con.prepareStatement(
                        "UPDATE PRODUCTS SET STOCK = STOCK - ? WHERE PRODUCT_ID = ? AND STOCK >= ?")) {
                    for (Billing.Line l : b.lines) {
                        ps.setInt(1, l.quantity);
                        ps.setInt(2, l.productId);
                        ps.setInt(3, l.quantity);
                        if (ps.executeUpdate() == 0) throw new SQLException("Stock for " + l.name + " changed. Please try again.");
                    }
                }
                // 2. insert the order header and read back the generated ORDER_ID
                int orderId;
                String osql = "INSERT INTO ORDERS (USER_ID, SUBTOTAL, DISCOUNT, GST_AMOUNT, SHIPPING, COD_CHARGE, GRAND_TOTAL, "
                            + "COUPON_CODE, PAYMENT_MODE, SHIP_CITY, ORDER_STATUS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PLACED')";
                try (PreparedStatement ps = con.prepareStatement(osql, new String[]{"ORDER_ID"})) {
                    ps.setInt(1, u.id);
                    ps.setDouble(2, b.subtotal);
                    ps.setDouble(3, b.discount);
                    ps.setDouble(4, b.gst);
                    ps.setDouble(5, b.shipping);
                    ps.setDouble(6, b.codCharge);
                    ps.setDouble(7, b.total);
                    ps.setString(8, b.couponCode);
                    ps.setString(9, b.paymentMode);
                    ps.setString(10, b.city);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        orderId = keys.getInt(1);
                    }
                }
                // 3. insert all line items in one batch
                String isql = "INSERT INTO ORDER_ITEMS (ORDER_ID, PRODUCT_ID, QUANTITY, UNIT_PRICE, GST_RATE, LINE_TOTAL) VALUES (?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = con.prepareStatement(isql)) {
                    for (Billing.Line l : b.lines) {
                        ps.setInt(1, orderId);
                        ps.setInt(2, l.productId);
                        ps.setInt(3, l.quantity);
                        ps.setDouble(4, l.unitPrice);
                        ps.setDouble(5, l.gstRate);
                        ps.setDouble(6, l.lineTotal);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                con.commit();                               // COMMIT
                WebUtil.redirect(ex, "/order?id=" + orderId, "msg", "Order #" + orderId + " placed successfully!");
            } catch (SQLException e) {
                con.rollback();                             // ROLLBACK on any failure
                WebUtil.redirect(ex, "/shop", "err", "Order failed and was rolled back: " + e.getMessage());
            }
        }
    }

    // =====================================================================
    //  ORDERS (customer: own orders, admin: all orders)
    // =====================================================================
    private static void orders(HttpExchange ex, WebUtil.SessionUser u, Map<String, String> p) throws Exception {
        String status = p.getOrDefault("status", "").toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>").append(u.isAdmin() ? "All orders" : "My orders").append("</h1><p class='sub'>")
          .append(u.isAdmin() ? "Track and update every order in the store." : "Your order history.").append("</p>");
        sb.append("<div class='card'><form class='inline' method='get' action='/orders'><select name='status'><option value=''>All statuses</option>");
        for (String s : new String[]{"PLACED", "SHIPPED", "DELIVERED", "CANCELLED"})
            sb.append("<option").append(s.equals(status) ? " selected" : "").append(">").append(s).append("</option>");
        sb.append("</select><button class='btn sec'>Filter</button></form></div>");

        String sql = "SELECT o.ORDER_ID, TO_CHAR(o.ORDER_DATE, 'DD-MON-YYYY HH24:MI') AS OD, u.FULL_NAME, "
                   + "(SELECT SUM(QUANTITY) FROM ORDER_ITEMS i WHERE i.ORDER_ID = o.ORDER_ID) AS ITEMS, "
                   + "o.GRAND_TOTAL, o.PAYMENT_MODE, o.SHIP_CITY, o.ORDER_STATUS "
                   + "FROM ORDERS o JOIN APP_USERS u ON u.USER_ID = o.USER_ID "
                   + "WHERE (? = 1 OR o.USER_ID = ?) "
                   + (status.isEmpty() ? "" : "AND o.ORDER_STATUS = ? ")
                   + "ORDER BY o.ORDER_ID DESC";
        sb.append("<div class='card tw'><table><tr><th>Order</th><th>Date</th>").append(u.isAdmin() ? "<th>Customer</th>" : "")
          .append("<th class='r'>Items</th><th class='r'>Total</th><th>Payment</th><th>City</th><th>Status</th><th></th></tr>");
        int n = 0;
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, u.isAdmin() ? 1 : 0);
            ps.setInt(2, u.id);
            if (!status.isEmpty()) ps.setString(3, status);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    n++;
                    int id = rs.getInt("ORDER_ID");
                    sb.append("<tr><td><b>#").append(id).append("</b></td><td>").append(WebUtil.esc(rs.getString("OD"))).append("</td>")
                      .append(u.isAdmin() ? "<td>" + WebUtil.esc(rs.getString("FULL_NAME")) + "</td>" : "")
                      .append("<td class='r'>").append(rs.getInt("ITEMS")).append("</td><td class='r'>").append(WebUtil.money(rs.getDouble("GRAND_TOTAL")))
                      .append("</td><td>").append(WebUtil.esc(rs.getString("PAYMENT_MODE"))).append("</td><td>").append(WebUtil.esc(rs.getString("SHIP_CITY")))
                      .append("</td><td>").append(WebUtil.badge(rs.getString("ORDER_STATUS"))).append("</td><td>")
                      .append("<a class='btn sec sm' href='/order?id=").append(id).append("'>View</a></td></tr>");
                }
            }
        }
        if (n == 0) sb.append("<tr><td colspan='9' class='muted'>No orders found.</td></tr>");
        sb.append("</table></div>");
        WebUtil.send(ex, 200, WebUtil.page("Orders", u, p, sb.toString()));
    }

    private static void orderDetail(HttpExchange ex, WebUtil.SessionUser u, Map<String, String> p) throws Exception {
        int id = WebUtil.toInt(p.getOrDefault("id", ""), -1);
        StringBuilder sb = new StringBuilder();
        try (Connection con = DBConnection.getConnection()) {
            String hsql = "SELECT o.*, TO_CHAR(o.ORDER_DATE, 'DD-MON-YYYY HH24:MI') AS OD, u.FULL_NAME, u.EMAIL, u.PHONE "
                        + "FROM ORDERS o JOIN APP_USERS u ON u.USER_ID = o.USER_ID WHERE o.ORDER_ID = ?";
            String status;
            try (PreparedStatement ps = con.prepareStatement(hsql)) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || (!u.isAdmin() && rs.getInt("USER_ID") != u.id)) { notFound(ex, u); return; }
                    status = rs.getString("ORDER_STATUS");
                    sb.append("<h1>Order #").append(id).append(" ").append(WebUtil.badge(status)).append("</h1><p class='sub'>Placed on ")
                      .append(WebUtil.esc(rs.getString("OD"))).append(" by ").append(WebUtil.esc(rs.getString("FULL_NAME")))
                      .append(" &middot; ").append(WebUtil.esc(rs.getString("EMAIL"))).append(" &middot; ").append(WebUtil.esc(rs.getString("PHONE"))).append("</p>");
                    sb.append("<div class='grid g2'><div class='card'><h2>Bill</h2><table>")
                      .append(row("Subtotal", WebUtil.money(rs.getDouble("SUBTOTAL"))))
                      .append(row("Discount" + (rs.getString("COUPON_CODE") != null ? " (incl. coupon " + WebUtil.esc(rs.getString("COUPON_CODE")) + ")" : ""),
                              "&minus; " + WebUtil.money(rs.getDouble("DISCOUNT"))))
                      .append(row("GST", WebUtil.money(rs.getDouble("GST_AMOUNT"))))
                      .append(row("Delivery to " + WebUtil.esc(rs.getString("SHIP_CITY")), WebUtil.money(rs.getDouble("SHIPPING"))))
                      .append(row("COD charge", WebUtil.money(rs.getDouble("COD_CHARGE"))))
                      .append("<tr class='total'><td>Total</td><td class='r'>").append(WebUtil.money(rs.getDouble("GRAND_TOTAL"))).append("</td></tr></table>")
                      .append("<p class='muted'>Payment: <b>").append(WebUtil.esc(rs.getString("PAYMENT_MODE"))).append("</b></p>");
                }
            }
            // actions
            if (u.isAdmin() && !"CANCELLED".equals(status) && !"DELIVERED".equals(status)) {
                sb.append("<form method='post' action='/admin/orders/status' class='inline'><input type='hidden' name='id' value='").append(id)
                  .append("'><select name='status'>");
                if ("PLACED".equals(status)) sb.append("<option>SHIPPED</option><option>CANCELLED</option>");
                if ("SHIPPED".equals(status)) sb.append("<option>DELIVERED</option>");
                sb.append("</select><button class='btn sm'>Update status</button></form>");
            }
            if (!u.isAdmin() && "PLACED".equals(status)) {
                sb.append("<form method='post' action='/order/cancel' onsubmit=\"return confirm('Cancel this order?')\">")
                  .append("<input type='hidden' name='id' value='").append(id).append("'><button class='btn red sm'>Cancel order</button></form>");
            }
            sb.append("</div><div class='card'><h2>Items</h2><div class='tw'><table><tr><th>Product</th><th class='r'>Qty</th><th class='r'>Unit price</th><th class='r'>GST</th><th class='r'>Amount</th></tr>");
            String isql = "SELECT p.PRODUCT_NAME, i.QUANTITY, i.UNIT_PRICE, i.GST_RATE, i.LINE_TOTAL "
                        + "FROM ORDER_ITEMS i JOIN PRODUCTS p ON p.PRODUCT_ID = i.PRODUCT_ID WHERE i.ORDER_ID = ? ORDER BY i.ITEM_ID";
            try (PreparedStatement ps = con.prepareStatement(isql)) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        sb.append("<tr><td>").append(WebUtil.esc(rs.getString(1))).append("</td><td class='r'>").append(rs.getInt(2))
                          .append("</td><td class='r'>").append(WebUtil.money(rs.getDouble(3))).append("</td><td class='r'>")
                          .append(WebUtil.num(rs.getDouble(4))).append("%</td><td class='r'>").append(WebUtil.money(rs.getDouble(5))).append("</td></tr>");
                    }
                }
            }
            sb.append("</table></div><p><a class='btn sec sm' href='/orders'>&larr; All orders</a></p></div></div>");
        }
        WebUtil.send(ex, 200, WebUtil.page("Order #" + id, u, p, sb.toString()));
    }

    /** Cancels a PLACED order and puts the items back into stock (one transaction). */
    private static boolean cancelOrder(Connection con, int orderId, Integer onlyForUserId) throws SQLException {
        con.setAutoCommit(false);
        try {
            String sql = "UPDATE ORDERS SET ORDER_STATUS = 'CANCELLED' WHERE ORDER_ID = ? AND ORDER_STATUS = 'PLACED'"
                       + (onlyForUserId != null ? " AND USER_ID = ?" : "");
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, orderId);
                if (onlyForUserId != null) ps.setInt(2, onlyForUserId);
                if (ps.executeUpdate() == 0) { con.rollback(); return false; }
            }
            List<int[]> items = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement("SELECT PRODUCT_ID, QUANTITY FROM ORDER_ITEMS WHERE ORDER_ID = ?")) {
                ps.setInt(1, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) items.add(new int[]{rs.getInt(1), rs.getInt(2)});
                }
            }
            try (PreparedStatement ps = con.prepareStatement("UPDATE PRODUCTS SET STOCK = STOCK + ? WHERE PRODUCT_ID = ?")) {
                for (int[] it : items) {
                    ps.setInt(1, it[1]);
                    ps.setInt(2, it[0]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            con.commit();
            return true;
        } catch (SQLException e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(true);
        }
    }

    private static void cancelOrderAction(HttpExchange ex, WebUtil.SessionUser u, Map<String, String> p) throws Exception {
        int id = WebUtil.toInt(p.getOrDefault("id", ""), -1);
        boolean ok;
        try (Connection con = DBConnection.getConnection()) {
            ok = cancelOrder(con, id, u.isAdmin() ? null : u.id);
        }
        if (ok) WebUtil.redirect(ex, "/order?id=" + id, "msg", "Order cancelled and items returned to stock.");
        else    WebUtil.redirect(ex, "/order?id=" + id, "err", "Only orders with status PLACED can be cancelled.");
    }

    // =====================================================================
    //  ADMIN
    // =====================================================================
    private static void adminDashboard(HttpExchange ex, WebUtil.SessionUser u, Map<String, String> p) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>Dashboard</h1><p class='sub'>Store overview</p>");

        // Lab-style JDBC steps with a plain Statement (no user input in this query)
        String kpiSql = "SELECT "
                + "(SELECT COUNT(*) FROM PRODUCTS WHERE IS_ACTIVE = 'Y') AS PRODUCTS, "
                + "(SELECT COUNT(*) FROM APP_USERS WHERE USER_ROLE = 'CUSTOMER') AS CUSTOMERS, "
                + "(SELECT COUNT(*) FROM ORDERS WHERE ORDER_STATUS <> 'CANCELLED') AS ORDERS, "
                + "(SELECT NVL(SUM(GRAND_TOTAL), 0) FROM ORDERS WHERE ORDER_STATUS <> 'CANCELLED') AS REVENUE, "
                + "(SELECT COUNT(*) FROM ORDERS WHERE ORDER_STATUS = 'PLACED') AS PENDING, "
                + "(SELECT COUNT(*) FROM PRODUCTS WHERE STOCK < 10 AND IS_ACTIVE = 'Y') AS LOW_STOCK "
                + "FROM DUAL";
        try (Connection con = DBConnection.getConnection();               // Step 2: connection
             Statement stmt = con.createStatement();                      // Step 3: statement
             ResultSet rs = stmt.executeQuery(kpiSql)) {                  // Step 4: execute
            if (rs.next()) {                                              // Step 5: process
                sb.append("<div class='grid g4'>")
                  .append(kpi("Revenue", WebUtil.money(rs.getDouble("REVENUE"))))
                  .append(kpi("Orders", String.valueOf(rs.getInt("ORDERS"))))
                  .append(kpi("Pending shipment", String.valueOf(rs.getInt("PENDING"))))
                  .append(kpi("Customers", String.valueOf(rs.getInt("CUSTOMERS"))))
                  .append(kpi("Active products", String.valueOf(rs.getInt("PRODUCTS"))))
                  .append(kpi("Low-stock items", String.valueOf(rs.getInt("LOW_STOCK"))))
                  .append("</div><br>");
            }

            sb.append("<div class='grid g2'><div class='card'><h2>Low stock (below 10)</h2><table><tr><th>Product</th><th class='r'>Stock</th><th></th></tr>");
            try (Statement st = con.createStatement();
                 ResultSet r2 = st.executeQuery("SELECT PRODUCT_ID, PRODUCT_NAME, STOCK FROM PRODUCTS "
                         + "WHERE STOCK < 10 AND IS_ACTIVE = 'Y' ORDER BY STOCK")) {
                boolean any = false;
                while (r2.next()) {
                    any = true;
                    sb.append("<tr><td>").append(WebUtil.esc(r2.getString(2))).append("</td><td class='r'><b>").append(r2.getInt(3))
                      .append("</b></td><td><a class='btn sec sm' href='/admin/products#p").append(r2.getInt(1)).append("'>Restock</a></td></tr>");
                }
                if (!any) sb.append("<tr><td colspan='3' class='muted'>All products are well stocked.</td></tr>");
            }
            sb.append("</table></div><div class='card'><h2>Recent orders</h2><table><tr><th>Order</th><th>Customer</th><th class='r'>Total</th><th>Status</th></tr>");
            try (Statement st = con.createStatement();
                 ResultSet r3 = st.executeQuery("SELECT * FROM (SELECT o.ORDER_ID, u.FULL_NAME, o.GRAND_TOTAL, o.ORDER_STATUS FROM ORDERS o "
                         + "JOIN APP_USERS u ON u.USER_ID = o.USER_ID ORDER BY o.ORDER_ID DESC) WHERE ROWNUM <= 5")) {
                while (r3.next()) {
                    sb.append("<tr><td><a href='/order?id=").append(r3.getInt(1)).append("'>#").append(r3.getInt(1)).append("</a></td><td>")
                      .append(WebUtil.esc(r3.getString(2))).append("</td><td class='r'>").append(WebUtil.money(r3.getDouble(3)))
                      .append("</td><td>").append(WebUtil.badge(r3.getString(4))).append("</td></tr>");
                }
            }
            sb.append("</table></div></div>");
        }                                                                 // Step 6: close (automatic)
        WebUtil.send(ex, 200, WebUtil.page("Dashboard", u, p, sb.toString()));
    }

    private static String kpi(String label, String value) {
        return "<div class='card kpi' style='margin:0'><div class='l'>" + label + "</div><div class='v'>" + value + "</div></div>";
    }

    // ---------------------------------------------------------------- products
    private static void adminProducts(HttpExchange ex, WebUtil.SessionUser u, Map<String, String> p) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>Products</h1><p class='sub'>Add, update, deactivate or delete products.</p>");
        try (Connection con = DBConnection.getConnection()) {
            StringBuilder cats = new StringBuilder();
            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("SELECT CATEGORY_ID, CATEGORY_NAME, GST_RATE FROM CATEGORIES ORDER BY CATEGORY_NAME")) {
                while (rs.next())
                    cats.append("<option value='").append(rs.getInt(1)).append("'>").append(WebUtil.esc(rs.getString(2)))
                        .append(" (GST ").append(WebUtil.num(rs.getDouble(3))).append("%)</option>");
            }
            sb.append("<div class='card'><h2>Add new product</h2><form method='post' action='/admin/products/add'><div class='grid g4'>")
              .append("<div><label>Name</label><input name='name' required maxlength='100'></div>")
              .append("<div><label>Category</label><select name='category'>").append(cats).append("</select></div>")
              .append("<div><label>Brand</label><input name='brand' maxlength='40'></div>")
              .append("<div><label>Price (&#8377;, before GST)</label><input name='price' type='number' step='0.01' min='1' required></div>")
              .append("<div><label>Stock</label><input name='stock' type='number' min='0' required></div>")
              .append("<div style='grid-column:span 2'><label>Description</label><input name='description' maxlength='300'></div>")
              .append("<div><label>&nbsp;</label><button class='btn' style='width:100%'>Add product</button></div></div></form></div>");

            sb.append("<div class='card tw'><table><tr><th>ID</th><th>Product</th><th>Category</th><th class='r'>Units sold</th>")
              .append("<th>Price / Stock / Active</th><th></th></tr>");
            String sql = "SELECT p.PRODUCT_ID, p.PRODUCT_NAME, p.BRAND, c.CATEGORY_NAME, p.PRICE, p.STOCK, p.IS_ACTIVE, v.UNITS_SOLD "
                       + "FROM PRODUCTS p JOIN CATEGORIES c ON c.CATEGORY_ID = p.CATEGORY_ID "
                       + "JOIN V_PRODUCT_SALES v ON v.PRODUCT_ID = p.PRODUCT_ID ORDER BY p.PRODUCT_ID";
            try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    int id = rs.getInt("PRODUCT_ID");
                    boolean active = "Y".equals(rs.getString("IS_ACTIVE"));
                    int stock = rs.getInt("STOCK");
                    sb.append("<tr id='p").append(id).append("'").append(active ? "" : " style='opacity:.55'").append("><td>").append(id)
                      .append("</td><td><b>").append(WebUtil.esc(rs.getString("PRODUCT_NAME"))).append("</b><br><span class='muted' style='font-size:12px'>")
                      .append(WebUtil.esc(rs.getString("BRAND"))).append("</span></td><td>").append(WebUtil.esc(rs.getString("CATEGORY_NAME")))
                      .append("</td><td class='r'>").append(rs.getInt("UNITS_SOLD")).append("</td><td>")
                      .append("<form method='post' action='/admin/products/update' class='inline'><input type='hidden' name='id' value='").append(id).append("'>")
                      .append("<input name='price' type='number' step='0.01' min='1' style='width:110px' value='").append(rs.getDouble("PRICE")).append("'>")
                      .append("<input name='stock' type='number' min='0' style='width:80px").append(stock < 10 ? ";border-color:#f79009" : "")
                      .append("' value='").append(stock).append("'>")
                      .append("<select name='active'><option value='Y'").append(active ? " selected" : "").append(">Active</option>")
                      .append("<option value='N'").append(active ? "" : " selected").append(">Hidden</option></select>")
                      .append("<button class='btn sm'>Save</button></form></td><td>")
                      .append("<form method='post' action='/admin/products/delete' onsubmit=\"return confirm('Delete this product?')\">")
                      .append("<input type='hidden' name='id' value='").append(id).append("'><button class='btn red sm'>Delete</button></form></td></tr>");
                }
            }
            sb.append("</table></div>");
        }
        WebUtil.send(ex, 200, WebUtil.page("Products", u, p, sb.toString()));
    }

    private static void addProduct(HttpExchange ex, Map<String, String> p) throws Exception {
        String name = p.getOrDefault("name", "");
        double price = WebUtil.toDouble(p.getOrDefault("price", ""), -1);
        int stock = WebUtil.toInt(p.getOrDefault("stock", ""), -1);
        int cat = WebUtil.toInt(p.getOrDefault("category", ""), -1);
        if (name.isEmpty() || price <= 0 || stock < 0 || cat < 0) {
            WebUtil.redirect(ex, "/admin/products", "err", "Name, a positive price and a non-negative stock are required.");
            return;
        }
        String sql = "INSERT INTO PRODUCTS (PRODUCT_NAME, CATEGORY_ID, PRICE, STOCK, BRAND, DESCRIPTION) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection con = DBConnection.getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setInt(2, cat);
            ps.setDouble(3, Math.round(price * 100) / 100.0);
            ps.setInt(4, stock);
            ps.setString(5, p.getOrDefault("brand", ""));
            ps.setString(6, p.getOrDefault("description", ""));
            ps.executeUpdate();
        }
        WebUtil.redirect(ex, "/admin/products", "msg", "Product \"" + name + "\" added.");
    }

    private static void updateProduct(HttpExchange ex, Map<String, String> p) throws Exception {
        int id = WebUtil.toInt(p.getOrDefault("id", ""), -1);
        double price = WebUtil.toDouble(p.getOrDefault("price", ""), -1);
        int stock = WebUtil.toInt(p.getOrDefault("stock", ""), -1);
        String active = "N".equals(p.get("active")) ? "N" : "Y";
        if (price <= 0 || stock < 0) { WebUtil.redirect(ex, "/admin/products", "err", "Price must be positive and stock cannot be negative."); return; }
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE PRODUCTS SET PRICE = ?, STOCK = ?, IS_ACTIVE = ? WHERE PRODUCT_ID = ?")) {
            ps.setDouble(1, Math.round(price * 100) / 100.0);
            ps.setInt(2, stock);
            ps.setString(3, active);
            ps.setInt(4, id);
            int rows = ps.executeUpdate();
            WebUtil.redirect(ex, "/admin/products#p" + id, rows == 1 ? "msg" : "err",
                    rows == 1 ? "Product #" + id + " updated." : "Product not found.");
        }
    }

    private static void deleteProduct(HttpExchange ex, Map<String, String> p) throws Exception {
        int id = WebUtil.toInt(p.getOrDefault("id", ""), -1);
        try (Connection con = DBConnection.getConnection()) {
            int used;
            try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM ORDER_ITEMS WHERE PRODUCT_ID = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); used = rs.getInt(1); }
            }
            if (used > 0) {
                // Cannot DELETE (foreign key from ORDER_ITEMS) -> soft delete instead
                try (PreparedStatement ps = con.prepareStatement("UPDATE PRODUCTS SET IS_ACTIVE = 'N' WHERE PRODUCT_ID = ?")) {
                    ps.setInt(1, id);
                    ps.executeUpdate();
                }
                WebUtil.redirect(ex, "/admin/products", "msg",
                        "Product #" + id + " appears in past orders, so it was hidden from the shop instead of deleted.");
            } else {
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM PRODUCTS WHERE PRODUCT_ID = ?")) {
                    ps.setInt(1, id);
                    ps.executeUpdate();
                }
                WebUtil.redirect(ex, "/admin/products", "msg", "Product #" + id + " deleted.");
            }
        }
    }

    // ---------------------------------------------------------------- order status
    private static void updateOrderStatus(HttpExchange ex, Map<String, String> p) throws Exception {
        int id = WebUtil.toInt(p.getOrDefault("id", ""), -1);
        String to = p.getOrDefault("status", "").toUpperCase(Locale.ROOT);
        try (Connection con = DBConnection.getConnection()) {
            if ("CANCELLED".equals(to)) {
                boolean ok = cancelOrder(con, id, null);
                WebUtil.redirect(ex, "/order?id=" + id, ok ? "msg" : "err", ok ? "Order cancelled, stock restored." : "Only PLACED orders can be cancelled.");
                return;
            }
            String from = "SHIPPED".equals(to) ? "PLACED" : "DELIVERED".equals(to) ? "SHIPPED" : null;
            if (from == null) { WebUtil.redirect(ex, "/order?id=" + id, "err", "Invalid status."); return; }
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE ORDERS SET ORDER_STATUS = ? WHERE ORDER_ID = ? AND ORDER_STATUS = ?")) {
                ps.setString(1, to);
                ps.setInt(2, id);
                ps.setString(3, from);
                int rows = ps.executeUpdate();
                WebUtil.redirect(ex, "/order?id=" + id, rows == 1 ? "msg" : "err",
                        rows == 1 ? "Order #" + id + " marked " + to + "." : "Order must be " + from + " to become " + to + ".");
            }
        }
    }

    // ---------------------------------------------------------------- customers
    private static void adminCustomers(HttpExchange ex, WebUtil.SessionUser u, Map<String, String> p) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>Customers</h1><p class='sub'>Registered customers, their orders and loyalty tier.</p><div class='card tw'><table>")
          .append("<tr><th>Name</th><th>Username</th><th>Email</th><th>Phone</th><th>City</th><th class='r'>Orders</th><th class='r'>Total spent</th><th>Tier</th></tr>");
        String sql = "SELECT u.FULL_NAME, u.USERNAME, u.EMAIL, u.PHONE, u.CITY, COUNT(o.ORDER_ID) AS ORDERS, NVL(SUM(o.GRAND_TOTAL), 0) AS SPENT "
                   + "FROM APP_USERS u LEFT JOIN ORDERS o ON o.USER_ID = u.USER_ID AND o.ORDER_STATUS <> 'CANCELLED' "
                   + "WHERE u.USER_ROLE = 'CUSTOMER' "
                   + "GROUP BY u.FULL_NAME, u.USERNAME, u.EMAIL, u.PHONE, u.CITY ORDER BY SPENT DESC";
        try (Connection con = DBConnection.getConnection(); Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                double spent = rs.getDouble("SPENT");
                sb.append("<tr><td><b>").append(WebUtil.esc(rs.getString(1))).append("</b></td><td>").append(WebUtil.esc(rs.getString(2)))
                  .append("</td><td>").append(WebUtil.esc(rs.getString(3))).append("</td><td>").append(WebUtil.esc(rs.getString(4)))
                  .append("</td><td>").append(WebUtil.esc(rs.getString(5))).append("</td><td class='r'>").append(rs.getInt("ORDERS"))
                  .append("</td><td class='r'>").append(WebUtil.money(spent)).append("</td><td>").append(WebUtil.badge(Billing.tierFor(spent))).append("</td></tr>");
            }
        }
        sb.append("</table></div>");
        WebUtil.send(ex, 200, WebUtil.page("Customers", u, p, sb.toString()));
    }

    // ---------------------------------------------------------------- coupons
    private static void adminCoupons(HttpExchange ex, WebUtil.SessionUser u, Map<String, String> p) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>Coupons</h1><p class='sub'>Create and remove discount coupons.</p>");
        sb.append("<div class='card'><h2>New coupon</h2><form method='post' action='/admin/coupons/add'><div class='grid g4'>")
          .append("<div><label>Code</label><input name='code' required maxlength='20' pattern='[A-Za-z0-9]+' style='text-transform:uppercase'></div>")
          .append("<div><label>Discount %</label><input name='pct' type='number' step='0.01' min='1' max='90' required></div>")
          .append("<div><label>Minimum order (&#8377;)</label><input name='min' type='number' step='0.01' min='0' value='0'></div>")
          .append("<div><label>Maximum discount (&#8377;)</label><input name='max' type='number' step='0.01' min='1' required></div>")
          .append("<div><label>Valid for (days)</label><input name='days' type='number' min='1' value='30'></div>")
          .append("<div><label>&nbsp;</label><button class='btn' style='width:100%'>Create coupon</button></div></div></form></div>");
        sb.append("<div class='card tw'><table><tr><th>Code</th><th class='r'>Discount</th><th class='r'>Min order</th><th class='r'>Max discount</th><th>Valid till</th><th>Status</th><th class='r'>Times used</th><th></th></tr>");
        String sql = "SELECT c.COUPON_CODE, c.DISCOUNT_PCT, c.MIN_ORDER_VALUE, c.MAX_DISCOUNT, TO_CHAR(c.VALID_TILL, 'DD-MON-YYYY') AS VT, "
                   + "CASE WHEN c.IS_ACTIVE = 'Y' AND c.VALID_TILL >= TRUNC(SYSDATE) THEN 'VALID' ELSE 'EXPIRED' END AS ST, "
                   + "(SELECT COUNT(*) FROM ORDERS o WHERE o.COUPON_CODE = c.COUPON_CODE) AS USED "
                   + "FROM COUPONS c ORDER BY c.VALID_TILL DESC";
        try (Connection con = DBConnection.getConnection(); Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String code = rs.getString(1);
                sb.append("<tr><td><b>").append(WebUtil.esc(code)).append("</b></td><td class='r'>").append(WebUtil.num(rs.getDouble(2)))
                  .append("%</td><td class='r'>").append(WebUtil.money(rs.getDouble(3))).append("</td><td class='r'>").append(WebUtil.money(rs.getDouble(4)))
                  .append("</td><td>").append(WebUtil.esc(rs.getString(5))).append("</td><td>")
                  .append("VALID".equals(rs.getString(6)) ? "<span class='badge b-delivered'>Valid</span>" : "<span class='badge b-cancelled'>Expired</span>")
                  .append("</td><td class='r'>").append(rs.getInt(7)).append("</td><td>")
                  .append("<form method='post' action='/admin/coupons/delete'><input type='hidden' name='code' value='").append(WebUtil.esc(code))
                  .append("'><button class='btn red sm'>Delete</button></form></td></tr>");
            }
        }
        sb.append("</table></div>");
        WebUtil.send(ex, 200, WebUtil.page("Coupons", u, p, sb.toString()));
    }

    private static void addCoupon(HttpExchange ex, Map<String, String> p) throws Exception {
        String code = p.getOrDefault("code", "").toUpperCase(Locale.ROOT);
        double pct = WebUtil.toDouble(p.getOrDefault("pct", ""), -1);
        double min = WebUtil.toDouble(p.getOrDefault("min", "0"), 0);
        double max = WebUtil.toDouble(p.getOrDefault("max", ""), -1);
        int days = WebUtil.toInt(p.getOrDefault("days", "30"), 30);
        if (!code.matches("[A-Z0-9]{3,20}") || pct <= 0 || pct > 90 || max <= 0 || days < 1) {
            WebUtil.redirect(ex, "/admin/coupons", "err", "Check the coupon details (code 3-20 letters/digits, 1-90%).");
            return;
        }
        try (Connection con = DBConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM COUPONS WHERE COUPON_CODE = ?")) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) { WebUtil.redirect(ex, "/admin/coupons", "err", "Coupon " + code + " already exists."); return; }
                }
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO COUPONS (COUPON_CODE, DISCOUNT_PCT, MIN_ORDER_VALUE, MAX_DISCOUNT, VALID_TILL, IS_ACTIVE) "
                  + "VALUES (?, ?, ?, ?, ?, 'Y')")) {
                ps.setString(1, code);
                ps.setDouble(2, pct);
                ps.setDouble(3, min);
                ps.setDouble(4, max);
                ps.setDate(5, java.sql.Date.valueOf(java.time.LocalDate.now().plusDays(days)));
                ps.executeUpdate();
            }
        }
        WebUtil.redirect(ex, "/admin/coupons", "msg", "Coupon " + code + " created.");
    }

    private static void deleteCoupon(HttpExchange ex, Map<String, String> p) throws Exception {
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM COUPONS WHERE COUPON_CODE = ?")) {
            ps.setString(1, p.getOrDefault("code", ""));
            ps.executeUpdate();
        }
        WebUtil.redirect(ex, "/admin/coupons", "msg", "Coupon deleted.");
    }

    // ---------------------------------------------------------------- reports
    private static void adminReports(HttpExchange ex, WebUtil.SessionUser u, Map<String, String> p) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>Reports</h1><p class='sub'>Sales analysis using JOIN, GROUP BY, HAVING, sub-queries and a VIEW. Cancelled orders are excluded.</p>");
        try (Connection con = DBConnection.getConnection(); Statement st = con.createStatement()) {
            sb.append("<div class='grid g2'>");

            // Sales by category
            sb.append("<div class='card'><h2>Sales by category</h2><table><tr><th>Category</th><th class='r'>Units</th><th class='r'>Sales (pre-tax)</th><th style='width:30%'></th></tr>");
            List<Object[]> rows = new ArrayList<>();
            double maxSales = 0;
            try (ResultSet rs = st.executeQuery(
                    "SELECT c.CATEGORY_NAME, NVL(SUM(oi.QUANTITY), 0) AS UNITS, NVL(SUM(oi.LINE_TOTAL), 0) AS SALES "
                  + "FROM CATEGORIES c "
                  + "LEFT JOIN PRODUCTS p ON p.CATEGORY_ID = c.CATEGORY_ID "
                  + "LEFT JOIN ORDER_ITEMS oi ON oi.PRODUCT_ID = p.PRODUCT_ID "
                  + "  AND oi.ORDER_ID IN (SELECT ORDER_ID FROM ORDERS WHERE ORDER_STATUS <> 'CANCELLED') "
                  + "GROUP BY c.CATEGORY_NAME ORDER BY SALES DESC")) {
                while (rs.next()) {
                    rows.add(new Object[]{rs.getString(1), rs.getInt(2), rs.getDouble(3)});
                    maxSales = Math.max(maxSales, rs.getDouble(3));
                }
            }
            for (Object[] r : rows) {
                double s = (Double) r[2];
                int w = maxSales > 0 ? (int) Math.round(s * 100 / maxSales) : 0;
                sb.append("<tr><td>").append(WebUtil.esc(r[0])).append("</td><td class='r'>").append(r[1]).append("</td><td class='r'>")
                  .append(WebUtil.money(s)).append("</td><td><div class='bar'><i style='width:").append(w).append("%'></i></div></td></tr>");
            }
            sb.append("</table></div>");

            // Top products from the view
            sb.append("<div class='card'><h2>Top 5 products</h2><table><tr><th>Product</th><th class='r'>Units sold</th><th class='r'>Revenue</th></tr>");
            try (ResultSet rs = st.executeQuery("SELECT * FROM (SELECT PRODUCT_NAME, UNITS_SOLD, REVENUE FROM V_PRODUCT_SALES "
                    + "WHERE UNITS_SOLD > 0 ORDER BY UNITS_SOLD DESC, REVENUE DESC) WHERE ROWNUM <= 5")) {
                while (rs.next())
                    sb.append("<tr><td>").append(WebUtil.esc(rs.getString(1))).append("</td><td class='r'>").append(rs.getInt(2))
                      .append("</td><td class='r'>").append(WebUtil.money(rs.getDouble(3))).append("</td></tr>");
            }
            sb.append("</table></div>");

            // Orders by status
            sb.append("<div class='card'><h2>Orders by status</h2><table><tr><th>Status</th><th class='r'>Orders</th><th class='r'>Value</th></tr>");
            try (ResultSet rs = st.executeQuery("SELECT ORDER_STATUS, COUNT(*), SUM(GRAND_TOTAL) FROM ORDERS GROUP BY ORDER_STATUS ORDER BY 2 DESC")) {
                while (rs.next())
                    sb.append("<tr><td>").append(WebUtil.badge(rs.getString(1))).append("</td><td class='r'>").append(rs.getInt(2))
                      .append("</td><td class='r'>").append(WebUtil.money(rs.getDouble(3))).append("</td></tr>");
            }
            sb.append("</table></div>");

            // Payment modes
            sb.append("<div class='card'><h2>Payment modes</h2><table><tr><th>Mode</th><th class='r'>Orders</th><th class='r'>Value</th><th class='r'>Avg order</th></tr>");
            try (ResultSet rs = st.executeQuery("SELECT PAYMENT_MODE, COUNT(*), SUM(GRAND_TOTAL), ROUND(AVG(GRAND_TOTAL), 2) FROM ORDERS "
                    + "WHERE ORDER_STATUS <> 'CANCELLED' GROUP BY PAYMENT_MODE ORDER BY 3 DESC")) {
                while (rs.next())
                    sb.append("<tr><td>").append(WebUtil.esc(rs.getString(1))).append("</td><td class='r'>").append(rs.getInt(2))
                      .append("</td><td class='r'>").append(WebUtil.money(rs.getDouble(3))).append("</td><td class='r'>")
                      .append(WebUtil.money(rs.getDouble(4))).append("</td></tr>");
            }
            sb.append("</table></div>");

            // HAVING + sub-query
            sb.append("<div class='card'><h2>Customers spending above the average order value</h2><table><tr><th>Customer</th><th class='r'>Orders</th><th class='r'>Total spent</th></tr>");
            try (ResultSet rs = st.executeQuery("SELECT u.FULL_NAME, COUNT(o.ORDER_ID), SUM(o.GRAND_TOTAL) "
                    + "FROM APP_USERS u JOIN ORDERS o ON o.USER_ID = u.USER_ID WHERE o.ORDER_STATUS <> 'CANCELLED' "
                    + "GROUP BY u.FULL_NAME "
                    + "HAVING SUM(o.GRAND_TOTAL) > (SELECT AVG(GRAND_TOTAL) FROM ORDERS WHERE ORDER_STATUS <> 'CANCELLED') "
                    + "ORDER BY 3 DESC")) {
                while (rs.next())
                    sb.append("<tr><td>").append(WebUtil.esc(rs.getString(1))).append("</td><td class='r'>").append(rs.getInt(2))
                      .append("</td><td class='r'>").append(WebUtil.money(rs.getDouble(3))).append("</td></tr>");
            }
            sb.append("</table></div>");

            // Products never sold
            sb.append("<div class='card'><h2>Products never sold</h2><table><tr><th>Product</th><th class='r'>Stock</th></tr>");
            try (ResultSet rs = st.executeQuery("SELECT PRODUCT_NAME, STOCK FROM PRODUCTS p WHERE IS_ACTIVE = 'Y' AND NOT EXISTS "
                    + "(SELECT 1 FROM ORDER_ITEMS oi JOIN ORDERS o ON o.ORDER_ID = oi.ORDER_ID "
                    + " WHERE oi.PRODUCT_ID = p.PRODUCT_ID AND o.ORDER_STATUS <> 'CANCELLED') ORDER BY PRODUCT_NAME")) {
                while (rs.next())
                    sb.append("<tr><td>").append(WebUtil.esc(rs.getString(1))).append("</td><td class='r'>").append(rs.getInt(2)).append("</td></tr>");
            }
            sb.append("</table></div></div>");
        }
        WebUtil.send(ex, 200, WebUtil.page("Reports", u, p, sb.toString()));
    }
}
