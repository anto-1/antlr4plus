import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildVariantsTest {
    @Test
    fun `release versions carry the ordered variant product code`() {
        assertEquals(
            listOf("2026.1.12.1", "2026.1.12.2", "2026.1.12.3"),
            BuildVariants.all.map { it.releaseVersion("2026.1.12") },
        )
    }

    @Test
    fun `IDE build ranges are contiguous`() {
        val variants = BuildVariants.all

        assertEquals("232", variants.first().sinceBuild)
        assertTrue(variants.last().untilBuild.isEmpty())

        variants.zipWithNext().forEach { (current, next) ->
            val currentUpperBound = current.untilBuild.removeSuffix(".*").toInt()
            assertEquals(
                currentUpperBound + 1,
                next.sinceBuild.toInt(),
                "Gap between ${current.name} and ${next.name}",
            )
        }
    }
}
