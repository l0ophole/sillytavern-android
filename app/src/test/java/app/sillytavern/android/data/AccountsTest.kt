package app.sillytavern.android.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AccountsTest {
    private fun base() = Files.createTempDirectory("acc").toFile()

    @Test fun defaultUserKeepsLegacyFolder() {
        val b = base(); val r = AccountRegistry(b)
        assertEquals("default", r.active().handle)
        assertEquals(File(b, "data"), r.pathsFor("default").root)
        assertEquals(File(b, "users/ann/data"), r.pathsFor("ann").root)
    }

    @Test fun createUniqueHandlesAndPersist() {
        val b = base(); val r = AccountRegistry(b)
        val a = r.create("Ann Lee"); val a2 = r.create("Ann Lee"); val d = r.create("Default")
        assertEquals(listOf("ann-lee", "ann-lee-2", "default-2"), listOf(a.handle, a2.handle, d.handle))
        assertTrue(File(b, "users/ann-lee/data/characters").isDirectory)
        r.setActive("ann-lee")
        val again = AccountRegistry(b)
        assertEquals("ann-lee", again.active().handle)
        assertEquals(4, again.list().size)
        assertEquals("user", r.create("!!!").handle)
        try { r.create("  "); fail() } catch (_: AccountException) {}
        try { r.setActive("nobody"); fail() } catch (_: AccountException) {}
    }

    @Test fun passcodeSetVerifyChangeClear() {
        val r = AccountRegistry(base()); val a = r.create("Bob")
        assertTrue(r.verify(a.handle, "anything"))
        assertTrue(r.setPasscode(a.handle, "1234"))
        assertTrue(r.get(a.handle)!!.locked)
        assertFalse(r.get(a.handle)!!.passHash.contains("1234"))
        assertTrue(r.verify(a.handle, "1234")); assertFalse(r.verify(a.handle, "0000")); assertFalse(r.verify(a.handle, ""))
        assertFalse("changing needs the current code", r.setPasscode(a.handle, "9", null))
        assertFalse(r.setPasscode(a.handle, "9", "bad"))
        assertTrue(r.setPasscode(a.handle, "5678", "1234")); assertTrue(r.verify(a.handle, "5678"))
        assertTrue(r.setPasscode(a.handle, "", "5678")); assertFalse(r.get(a.handle)!!.locked)
        assertTrue(r.verify(a.handle, ""))
    }

    @Test fun samePasscodeGetsDifferentHashes() {
        val (s1, h1) = Passcode.hash("x"); val (s2, h2) = Passcode.hash("x")
        assertNotEquals(s1, s2); assertNotEquals(h1, h2)
        assertTrue(Passcode.verify("x", s1, h1)); assertFalse(Passcode.verify("y", s1, h1))
    }

    @Test fun deleteRules() {
        val b = base(); val r = AccountRegistry(b)
        val a = r.create("Cy"); r.setPasscode(a.handle, "42")
        File(r.pathsFor(a.handle).characters, "c.png").writeBytes(byteArrayOf(1))
        try { r.delete("default"); fail() } catch (_: AccountException) {}
        r.setActive(a.handle)
        try { r.delete(a.handle, "42"); fail() } catch (_: AccountException) {}
        r.setActive("default")
        assertFalse("wrong passcode", r.delete(a.handle, "nope"))
        assertTrue(File(b, "users/${a.handle}").exists())
        assertTrue(r.delete(a.handle, "42"))
        assertFalse(File(b, "users/${a.handle}").exists())
        assertNull(r.get(a.handle))
    }

    @Test fun corruptRegistryFallsBackToDefault() {
        val b = base(); File(b, "accounts.json").writeText("{not json")
        assertEquals("default", AccountRegistry(b).active().handle)
    }
}
