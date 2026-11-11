package io.freemytv;

/** The Chromecast with Google TV (Sabrina) Bluetooth remote. */
final class SabrinaRemote {
    private SabrinaRemote() {}

    static final int VENDOR_ID = 0x18d1, PRODUCT_ID = 0x9450;
    /** The public Vendor_18d1_Product_9450.kl maps scan usage 0x000c0077 (YouTube) to BUTTON_3. */
    static final int KEY_YOUTUBE = 190;

    static boolean supported(String device, String model) {
        return "sabrina".equals(device) && "Chromecast".equals(model);
    }

    static boolean isRemote(int vendorId, int productId) {
        return vendorId == VENDOR_ID && productId == PRODUCT_ID;
    }
}
