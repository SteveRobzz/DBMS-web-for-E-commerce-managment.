import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PROCESSING LOGIC of the application.
 *
 * Input  : products + quantities chosen by the customer, coupon code,
 *          payment mode and delivery city.
 * Process: stock check, quantity limit, loyalty-tier discount (based on the
 *          customer's past spend), coupon validation, category-wise GST,
 *          shipping rule, COD rule.
 * Output : an itemised bill and a decision - can the order be placed or not
 *          (with reasons).
 *
 * Business rules
 *  - Max 10 units of any one product per order.
 *  - Loyalty tier from lifetime spend (non-cancelled orders):
 *        >= 50,000 PLATINUM 10%  | >= 20,000 GOLD 7% | >= 5,000 SILVER 3% | else REGULAR 0%
 *  - Coupon: must exist, be active, not expired and order subtotal >= minimum;
 *        coupon discount = min(subtotal * pct, max discount).
 *  - GST is charged per category on the discounted value of each item.
 *  - Shipping: free if (subtotal - discount) >= 999, else Rs.40 metro / Rs.80 other cities.
 *  - Cash on Delivery: Rs.30 extra, not allowed for orders above Rs.20,000.
 */
public class Billing {

    public static final int    MAX_QTY_PER_ITEM    = 10;
    public static final double FREE_SHIP_THRESHOLD = 999;
    public static final double METRO_SHIPPING      = 40;
    public static final double NON_METRO_SHIPPING  = 80;
    public static final double COD_CHARGE          = 30;
    public static final double COD_LIMIT           = 20000;

    public static final Set<String> METRO_CITIES = Set.of(
            "BANGALORE", "BENGALURU", "CHENNAI", "MUMBAI", "DELHI", "NEW DELHI",
            "HYDERABAD", "KOLKATA", "PUNE", "AHMEDABAD");
    public static final Set<String> PAYMENT_MODES = Set.of("UPI", "CARD", "NETBANKING", "COD");

    public static class Line {
        public int productId;
        public String name;
        public String category;
        public double unitPrice;
        public double gstRate;
        public int quantity;
        public int stock;
        public double lineTotal;   // quantity * unitPrice (before discount / tax)
        public double lineGst;
    }

    public static class Bill {
        public final List<Line> lines = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();    // block the order
        public final List<String> notes = new ArrayList<>();     // information only
        public double lifetimeSpend;
        public String tier;
        public double tierPct;
        public double subtotal, tierDiscount, couponDiscount, discount, gst, shipping, codCharge, total;
        public String couponCode;   // only set when the coupon was applied
        public String paymentMode;
        public String city;
        public boolean metro;

        public boolean canPlaceOrder() {
            return errors.isEmpty() && !lines.isEmpty();
        }
    }

    // ---------------------------------------------------------------------
    public static String tierFor(double spend) {
        if (spend >= 50000) return "PLATINUM";
        if (spend >= 20000) return "GOLD";
        if (spend >= 5000)  return "SILVER";
        return "REGULAR";
    }

    public static double tierPct(String tier) {
        switch (tier) {
            case "PLATINUM": return 10;
            case "GOLD":     return 7;
            case "SILVER":   return 3;
            default:         return 0;
        }
    }

    public static double lifetimeSpend(Connection con, int userId) throws SQLException {
        String sql = "SELECT NVL(SUM(GRAND_TOTAL), 0) FROM ORDERS "
                   + "WHERE USER_ID = ? AND ORDER_STATUS <> 'CANCELLED'";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getDouble(1);
            }
        }
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ---------------------------------------------------------------------
    public static Bill compute(Connection con, int userId, Map<Integer, Integer> cart,
                               String couponInput, String paymentMode, String city) throws SQLException {
        Bill b = new Bill();
        b.paymentMode = paymentMode == null ? "" : paymentMode.trim().toUpperCase(Locale.ROOT);
        b.city = city == null ? "" : city.trim();
        b.metro = METRO_CITIES.contains(b.city.toUpperCase(Locale.ROOT));

        if (cart.isEmpty()) b.errors.add("Your cart is empty. Enter a quantity for at least one product.");
        if (b.city.isEmpty()) b.errors.add("Delivery city is required.");
        if (!PAYMENT_MODES.contains(b.paymentMode)) b.errors.add("Choose a valid payment mode.");

        // 1. Load each product and check stock / quantity limits
        String sql = "SELECT p.PRODUCT_ID, p.PRODUCT_NAME, c.CATEGORY_NAME, p.PRICE, c.GST_RATE, p.STOCK, p.IS_ACTIVE "
                   + "FROM PRODUCTS p JOIN CATEGORIES c ON c.CATEGORY_ID = p.CATEGORY_ID WHERE p.PRODUCT_ID = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (Map.Entry<Integer, Integer> e : cart.entrySet()) {
                ps.setInt(1, e.getKey());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !"Y".equals(rs.getString("IS_ACTIVE"))) {
                        b.errors.add("Product #" + e.getKey() + " is not available.");
                        continue;
                    }
                    Line l = new Line();
                    l.productId = rs.getInt("PRODUCT_ID");
                    l.name      = rs.getString("PRODUCT_NAME");
                    l.category  = rs.getString("CATEGORY_NAME");
                    l.unitPrice = rs.getDouble("PRICE");
                    l.gstRate   = rs.getDouble("GST_RATE");
                    l.stock     = rs.getInt("STOCK");
                    l.quantity  = e.getValue();
                    l.lineTotal = round2(l.unitPrice * l.quantity);
                    if (l.quantity > MAX_QTY_PER_ITEM)
                        b.errors.add(l.name + ": maximum " + MAX_QTY_PER_ITEM + " units per order.");
                    if (l.quantity > l.stock)
                        b.errors.add(l.name + ": only " + l.stock + " left in stock (you asked for " + l.quantity + ").");
                    b.lines.add(l);
                    b.subtotal += l.lineTotal;
                }
            }
        }
        b.subtotal = round2(b.subtotal);

        // 2. Loyalty tier discount
        b.lifetimeSpend = lifetimeSpend(con, userId);
        b.tier = tierFor(b.lifetimeSpend);
        b.tierPct = tierPct(b.tier);
        b.tierDiscount = round2(b.subtotal * b.tierPct / 100);
        if (b.tierPct > 0)
            b.notes.add(b.tier + " member (lifetime spend " + WebUtil.money(b.lifetimeSpend) + "): "
                    + WebUtil.num(b.tierPct) + "% loyalty discount applied.");

        // 3. Coupon
        String code = couponInput == null ? "" : couponInput.trim().toUpperCase(Locale.ROOT);
        if (!code.isEmpty()) {
            String csql = "SELECT DISCOUNT_PCT, MIN_ORDER_VALUE, MAX_DISCOUNT, IS_ACTIVE, "
                        + "CASE WHEN VALID_TILL >= TRUNC(SYSDATE) THEN 1 ELSE 0 END AS IS_VALID "
                        + "FROM COUPONS WHERE COUPON_CODE = ?";
            try (PreparedStatement ps = con.prepareStatement(csql)) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        b.notes.add("Coupon " + code + " does not exist - ignored.");
                    } else if (!"Y".equals(rs.getString("IS_ACTIVE")) || rs.getInt("IS_VALID") == 0) {
                        b.notes.add("Coupon " + code + " has expired or is inactive - ignored.");
                    } else if (b.subtotal < rs.getDouble("MIN_ORDER_VALUE")) {
                        b.notes.add("Coupon " + code + " needs a minimum order of "
                                + WebUtil.money(rs.getDouble("MIN_ORDER_VALUE")) + " - ignored.");
                    } else {
                        double pct = rs.getDouble("DISCOUNT_PCT");
                        b.couponDiscount = round2(Math.min(b.subtotal * pct / 100, rs.getDouble("MAX_DISCOUNT")));
                        b.couponCode = code;
                        b.notes.add("Coupon " + code + " applied: " + WebUtil.num(pct) + "% off (max "
                                + WebUtil.money(rs.getDouble("MAX_DISCOUNT")) + ").");
                    }
                }
            }
        }
        b.discount = round2(Math.min(b.tierDiscount + b.couponDiscount, b.subtotal));

        // 4. GST per category on the discounted value of each line
        double ratio = b.subtotal > 0 ? (b.subtotal - b.discount) / b.subtotal : 0;
        double gst = 0;
        for (Line l : b.lines) {
            l.lineGst = round2(l.lineTotal * ratio * l.gstRate / 100);
            gst += l.lineGst;
        }
        b.gst = round2(gst);

        // 5. Shipping
        double afterDiscount = b.subtotal - b.discount;
        if (b.lines.isEmpty() || afterDiscount >= FREE_SHIP_THRESHOLD) {
            b.shipping = 0;
            if (!b.lines.isEmpty()) b.notes.add("Free delivery (order value above " + WebUtil.money(FREE_SHIP_THRESHOLD) + ").");
        } else {
            b.shipping = b.metro ? METRO_SHIPPING : NON_METRO_SHIPPING;
            b.notes.add("Add " + WebUtil.money(FREE_SHIP_THRESHOLD - afterDiscount)
                    + " more for free delivery. " + (b.metro ? "Metro" : "Non-metro") + " delivery charge applied.");
        }

        // 6. Payment mode rules
        if ("COD".equals(b.paymentMode)) b.codCharge = COD_CHARGE;
        b.total = round2(b.subtotal - b.discount + b.gst + b.shipping + b.codCharge);
        if ("COD".equals(b.paymentMode) && b.total > COD_LIMIT)
            b.errors.add("Cash on Delivery is not allowed for orders above " + WebUtil.money(COD_LIMIT)
                    + ". Please choose UPI, Card or Net Banking.");

        return b;
    }
}
