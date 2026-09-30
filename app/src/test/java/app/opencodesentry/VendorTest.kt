package app.opencodesentry

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Brand detection for the vendor keep-alive rows. The values are the ones
 * `Build.MANUFACTURER` / `Build.BRAND` actually report on each family.
 */
class VendorTest {

    @Test
    fun `target brands are recognised`() {
        assertEquals(VendorShortcuts.Vendor.XIAOMI, VendorShortcuts.vendorOf("Xiaomi Redmi"))
        assertEquals(VendorShortcuts.Vendor.XIAOMI, VendorShortcuts.vendorOf("Xiaomi POCO"))
        assertEquals(VendorShortcuts.Vendor.HUAWEI, VendorShortcuts.vendorOf("HUAWEI HONOR"))
        assertEquals(VendorShortcuts.Vendor.HUAWEI, VendorShortcuts.vendorOf("HONOR HONOR"))
        assertEquals(VendorShortcuts.Vendor.OPPO, VendorShortcuts.vendorOf("OPPO OPPO"))
        assertEquals(VendorShortcuts.Vendor.OPPO, VendorShortcuts.vendorOf("OnePlus OnePlus"))
        assertEquals(VendorShortcuts.Vendor.OPPO, VendorShortcuts.vendorOf("realme realme"))
        assertEquals(VendorShortcuts.Vendor.VIVO, VendorShortcuts.vendorOf("vivo vivo"))
        assertEquals(VendorShortcuts.Vendor.VIVO, VendorShortcuts.vendorOf("vivo iQOO"))
        assertEquals(VendorShortcuts.Vendor.SAMSUNG, VendorShortcuts.vendorOf("samsung samsung"))
        assertEquals(VendorShortcuts.Vendor.MEIZU, VendorShortcuts.vendorOf("Meizu Meizu"))
    }

    @Test
    fun `unknown brands fall back to OTHER`() {
        assertEquals(VendorShortcuts.Vendor.OTHER, VendorShortcuts.vendorOf("Google sdk_gphone"))
        assertEquals(VendorShortcuts.Vendor.OTHER, VendorShortcuts.vendorOf(""))
    }

    @Test
    fun `vendor labels are the user-facing names`() {
        assertEquals("小米 / Redmi", VendorShortcuts.Vendor.XIAOMI.label)
        assertEquals("华为 / 荣耀", VendorShortcuts.Vendor.HUAWEI.label)
        assertEquals("OPPO / 一加 / realme", VendorShortcuts.Vendor.OPPO.label)
        assertEquals("vivo / iQOO", VendorShortcuts.Vendor.VIVO.label)
        assertEquals("三星", VendorShortcuts.Vendor.SAMSUNG.label)
        assertEquals("", VendorShortcuts.Vendor.OTHER.label)
    }
}
