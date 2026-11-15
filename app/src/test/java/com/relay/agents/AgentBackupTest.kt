package com.relay.agents

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentBackupTest {
    private val a1 = AgentConfig("id-1", "Gemini 1", "key-AAAA", "gemini-flash-latest", true, Provider.GEMINI)
    private val a2 = AgentConfig("id-2", "Groq", "key-BBBB", "llama-3.3-70b-versatile", false, Provider.GROQ)
    private val web = AgentConfig("id-3", "Claude (ويب)", "", "https://claude.ai", true, Provider.WEB)

    private fun kind(block: () -> Unit): BackupException.Kind? =
        try { block(); null } catch (e: BackupException) { e.kind }

    @Test
    fun plainRoundTrip() {
        val text = AgentBackup.encode(listOf(a1, a2, web), "")
        assertFalse(AgentBackup.isEncrypted(text))
        assertEquals(listOf(a1, a2, web), AgentBackup.decode(text, ""))
    }

    @Test
    fun encryptedRoundTripHidesKeys() {
        val text = AgentBackup.encode(listOf(a1, a2), "كلمة سر 123")
        assertTrue(AgentBackup.isEncrypted(text))
        assertFalse(text.contains("key-AAAA"))
        assertFalse(text.contains("Gemini 1"))
        assertEquals(listOf(a1, a2), AgentBackup.decode(text, "كلمة سر 123"))
    }

    @Test
    fun encryptedNeedsPassphrase() {
        val text = AgentBackup.encode(listOf(a1), "secret")
        assertEquals(BackupException.Kind.NEED_PASSPHRASE, kind { AgentBackup.decode(text, "") })
    }

    @Test
    fun wrongPassphraseIsDetected() {
        val text = AgentBackup.encode(listOf(a1), "secret")
        assertEquals(BackupException.Kind.WRONG_PASSPHRASE, kind { AgentBackup.decode(text, "other") })
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val o = JSONObject(AgentBackup.encode(listOf(a1), "secret"))
        val d = o.getString("data")
        val flipped = (if (d[0] == 'A') 'B' else 'A') + d.substring(1)
        o.put("data", flipped)
        assertEquals(BackupException.Kind.WRONG_PASSPHRASE, kind { AgentBackup.decode(o.toString(), "secret") })
    }

    @Test
    fun garbageIsInvalid() {
        assertEquals(BackupException.Kind.INVALID, kind { AgentBackup.decode("هذا ليس json", "") })
        assertEquals(BackupException.Kind.INVALID, kind { AgentBackup.decode("{\"x\":1}", "") })
    }

    @Test
    fun corruptEntriesAreSkipped() {
        val arr = JSONArray()
            .put(a1.toJson())
            .put(JSONObject().put("id", "broken"))
            .put(a2.toJson())
        val text = JSONObject().put("version", 1).put("agents", arr).toString()
        assertEquals(listOf(a1, a2), AgentBackup.decode(text, ""))
    }

    @Test
    fun emptyListRoundTrips() {
        val text = AgentBackup.encode(emptyList(), "")
        assertTrue(AgentBackup.decode(text, "").isEmpty())
    }

    @Test
    fun mergeAddsOnlyMissingAgents() {
        val sameButNewId = a1.copy(id = "other-id")
        val merged = AgentBackup.merge(listOf(a1), listOf(sameButNewId, a1, a2, web))
        assertEquals(listOf(a1, a2, web), merged)
    }

    @Test
    fun mergeKeepsLocalChanges() {
        val local = a1.copy(model = "gemini-pro-latest", enabled = false)
        val merged = AgentBackup.merge(listOf(local), listOf(a1))
        assertEquals(listOf(local), merged)
    }
}
