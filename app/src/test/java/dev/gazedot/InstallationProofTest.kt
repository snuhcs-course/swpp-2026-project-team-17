package dev.gazedot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
class InstallationProofTest {
 @Test fun canonicalUsesExactUtf8BytesAndFixedEndpoint() {
  assertEquals("POST\n/v2/predictions\n1700000000000\n0123456789abcdef0123456789abcdef\n2c68318e352971113645cbc72861e1ec23f48d5baa5f9b405fed9dddca893eb4",
    InstallationProof.canonical("1700000000000", "0123456789abcdef0123456789abcdef", "안녕하세요".toByteArray(Charsets.UTF_8)))
 }
 @Test fun whitespaceChangesTheSignedPayload() {
  assertNotEquals(InstallationProof.canonical("1","n","{}".toByteArray()), InstallationProof.canonical("1","n","{ }".toByteArray()))
 }
}
