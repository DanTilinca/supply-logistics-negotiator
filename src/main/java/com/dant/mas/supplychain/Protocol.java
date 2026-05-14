package com.dant.mas.supplychain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * FIPA-ACL message content keys and helpers (key=value;key=value format).
 */
public final class Protocol {

  private Protocol() {}

  public static final String PRODUCT_ELECTRONICS = "Electronics";
  public static final String PRODUCT_CLOTHES = "Clothes";
  public static final String PRODUCT_FRUITS = "Fruits";
  public static final String PRODUCT_GROCERIES = "Groceries";
  public static final String PRODUCT_HOUSEHOLD = "Household";

  /** Product lines sold to customers and procured in B2B flows. */
  public static final List<String> RETAIL_CATALOG =
      List.of(
          PRODUCT_ELECTRONICS,
          PRODUCT_CLOTHES,
          PRODUCT_FRUITS,
          PRODUCT_GROCERIES,
          PRODUCT_HOUSEHOLD);

  public static final String PRODUCT_DEFAULT = PRODUCT_ELECTRONICS;

  public static boolean isRetailProduct(String productId) {
    return productId != null && RETAIL_CATALOG.contains(productId);
  }

  public static final String DF_SUPPLIER_TYPE = "supply-chain-supplier";
  public static final String DF_COURIER_TYPE = "supply-chain-courier";

  public static final String K_ACTION = "action";
  public static final String K_ORDER_ID = "orderId";
  public static final String K_PRODUCT = "product";
  public static final String K_QTY = "qty";
  public static final String K_UNIT_PRICE = "unitPrice";
  public static final String K_TOTAL = "total";
  public static final String K_AVAILABLE = "available";
  public static final String K_MAX_QTY = "maxQty";
  public static final String K_DEPOT = "depot";
  public static final String K_WEIGHT_KG = "weightKg";
  public static final String K_SPEED = "speed";
  public static final String K_DELIVERY_FEE = "deliveryFee";
  public static final String K_ETA_MS = "etaMs";
  public static final String K_SUPPLIER_LOCAL = "supplierLocal";
  public static final String K_COURIER_LOCAL = "courierLocal";
  public static final String K_TOTAL_GOODS = "totalGoods";
  public static final String K_DELIVERY_AMOUNT = "deliveryAmount";
  public static final String K_CUSTOMER_LOCAL = "customerLocal";

  public static final String A_OPEN_ESCROW = "OPEN_ESCROW";
  public static final String A_SHIP_ORDER = "SHIP_ORDER";
  public static final String A_AWAIT_ORDER = "AWAIT_ORDER";
  public static final String A_GOODS_READY = "GOODS_READY";
  public static final String A_DELIVERY_COMPLETE = "DELIVERY_COMPLETE";
  public static final String A_PAYMENT_RELEASED = "PAYMENT_RELEASED";
  public static final String A_ESCROW_CLOSED = "ESCROW_CLOSED";

  public static Map<String, String> parseMap(String content) {
    Map<String, String> m = new LinkedHashMap<>();
    if (content == null || content.isBlank()) {
      return m;
    }
    for (String part : content.split(";")) {
      int i = part.indexOf('=');
      if (i > 0) {
        m.put(part.substring(0, i).trim(), part.substring(i + 1).trim());
      }
    }
    return m;
  }

  public static String formatMap(Map<String, String> map) {
    return map.entrySet().stream()
        .map(e -> e.getKey() + "=" + e.getValue())
        .collect(Collectors.joining(";"));
  }
}
