package com.phonoteka.security
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
class OwnedQrEncodingTest {
    @Test fun ownedSignedInvitationSurvivesEncodeDecodeAndStillRequiresAuthority() {
        val roles = MockRoleAuthority { 1000L }
        val endpoint = MockInviteAuthority(roles, InviteMac { data -> Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(ByteArray(32) { it.toByte() }, "HmacSHA256")); doFinal(data)
        } }, { 1000L })
        repeat(32) {
        val wire = endpoint.issue(roles.issue(DemoRole.ADMIN), 42)
        val matrix = QRCodeWriter().encode(wire, BarcodeFormat.QR_CODE, 512, 512)
        val pixels = IntArray(512 * 512) { i -> if (matrix[i % 512, i / 512]) 0xff000000.toInt() else 0xffffffff.toInt() }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(512, 512, pixels))), mapOf(DecodeHintType.TRY_HARDER to true)).text
        assertEquals(wire, decoded)
        assertEquals(42L, endpoint.scan(roles.issue(DemoRole.SECURITY), decoded).trackId)
        }
    }
}
