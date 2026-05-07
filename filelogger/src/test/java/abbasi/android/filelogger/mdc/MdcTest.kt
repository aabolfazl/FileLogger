/**
 * Covers `Mdc.with`, `Mdc.withSuspending`, and the empty-snapshot singleton invariant. Suspending
 * tests use `runTest` so dispatcher hops are exercised without real wall-clock waits.
 */
package abbasi.android.filelogger.mdc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MdcTest {

    @After
    fun tearDown() {
        Mdc.setInternal(emptyMap())
    }

    @Test
    fun `with pushes and pops`() {
        Mdc.with("a" to "1") {
            assertEquals(mapOf("a" to "1"), Mdc.currentSnapshot())
        }
        assertTrue(Mdc.currentSnapshot().isEmpty())
    }

    @Test
    fun `nested with merges and inner wins on collision`() {
        Mdc.with("a" to "1", "b" to "2") {
            Mdc.with("b" to "two") {
                val snap = Mdc.currentSnapshot()
                assertEquals("1", snap["a"])
                assertEquals("two", snap["b"])
            }
            assertEquals("2", Mdc.currentSnapshot()["b"])
        }
    }

    @Test
    fun `empty snapshot returns the singleton`() {
        val a = Mdc.currentSnapshot()
        val b = Mdc.currentSnapshot()
        assertSame(a, b)
        assertSame(emptyMap<String, String>(), a)
    }

    @Test
    fun `exception inside with restores previous map`() {
        val ex = runCatching {
            Mdc.with("a" to "1") {
                throw IllegalStateException("boom")
            }
        }.exceptionOrNull()
        assertTrue(ex is IllegalStateException)
        assertTrue(Mdc.currentSnapshot().isEmpty())
    }

    @Test
    fun `withSuspending propagates across dispatcher hop`() = runTest {
        Mdc.withSuspending("req" to "abc") {
            withContext(Dispatchers.Default) {
                assertEquals("abc", Mdc.currentSnapshot()["req"])
            }
        }
        assertTrue(Mdc.currentSnapshot().isEmpty())
    }

    @Test
    fun `parallel coroutines see independent mdc`() = runTest {
        val a = async {
            Mdc.withSuspending("k" to "A") {
                delay(1)
                Mdc.currentSnapshot()["k"]
            }
        }
        val b = async {
            Mdc.withSuspending("k" to "B") {
                delay(1)
                Mdc.currentSnapshot()["k"]
            }
        }
        assertEquals("A", a.await())
        assertEquals("B", b.await())
    }
}
