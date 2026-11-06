package dev.harness

import dev.harness.core.SessionManager
import dev.harness.data.session.SessionDao
import dev.harness.data.session.SessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SessionReuseTest {
    private class FakeDao : SessionDao {
        val rows = LinkedHashMap<String, SessionEntity>()
        private fun sorted() = rows.values.sortedByDescending { it.updatedAt }
        override fun observeAll(): Flow<List<SessionEntity>> = MutableStateFlow(sorted())
        override suspend fun all() = sorted()
        override suspend fun get(id: String) = rows[id]
        override suspend fun search(q: String, limit: Int) = sorted()
        override suspend fun byTag(tag: String) = sorted()
        override suspend fun upsert(session: SessionEntity) { rows[session.id] = session }
        override suspend fun rename(id: String, title: String, now: Long) { rows[id]?.let { rows[id] = it.copy(title = title) } }
        override suspend fun delete(id: String) { rows.remove(id) }
        override suspend fun deleteAll() { rows.clear() }
    }

    @Test fun tappingNewTwiceKeepsOneEmptySession() = runBlocking {
        val dao = FakeDao(); val m = SessionManager(dao)
        val a = m.create("m")
        val b = m.create("m")
        assertEquals(a.id, b.id)
        assertEquals(1, dao.rows.size)
    }

    @Test fun usedSessionIsNotReused() = runBlocking {
        val dao = FakeDao(); val m = SessionManager(dao)
        val a = m.create("m")
        dao.upsert(a.copy(messageCount = 2, messagesJson = "[{\"role\":\"user\"}]"))
        val b = m.create("m")
        assertNotEquals(a.id, b.id)
        assertEquals(2, dao.rows.size)
    }

    @Test fun leftoverEmptyDuplicatesAreCleanedUp() = runBlocking {
        val dao = FakeDao(); val m = SessionManager(dao)
        repeat(3) { m.create("m", reuseEmpty = false) }
        m.create("m")
        assertEquals(1, dao.rows.size)
    }
}
