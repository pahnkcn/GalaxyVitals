package app.galaxyvitals.wear.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * The three faces this app ships to, in dp.
 *
 * Measured, not assumed. The SM-L350 — the device PROTOCOL.md validates against
 * — reports 480 × 480 px at 340 dpi, a density of 2.125 rather than the 2.0 a
 * 480 px panel suggests, which makes its face 225 dp and not 240. The other two
 * are derived at the same density and are *not* device-confirmed.
 */
private const val WATCH9_40MM = 206f
private const val WATCH9_44MM = 225f
private const val WATCH_ULTRA2 = 234f

private val FACES = listOf(WATCH9_40MM, WATCH9_44MM, WATCH_ULTRA2)

/**
 * The layout rests on one claim: three fractions of the face clear the glass on
 * every watch the app runs on. That is arithmetic, so it is checked here rather
 * than trusted to a preview someone remembers to look at.
 */
class PlateGeometryTest {

    /** The whole rule: a plate is as wide as its own furthest corner allows. */
    @Test
    fun `every tier corner lands inside the safe circle on every face`() {
        for (face in FACES) {
            val safeRadius = PlateGeometry.safeRadius(face)
            for (plate in Plate.entries) {
                val halfWidth = PlateGeometry.width(face, plate) / 2f
                val reach = PlateGeometry.reach(face, plate)
                val corner = hypot(halfWidth, reach)
                assertEquals(
                    "$plate on a ${face.toInt()} dp face should touch the safe circle",
                    safeRadius.toDouble(),
                    corner.toDouble(),
                    0.01,
                )
            }
        }
    }

    /** The bezel margin is real, not a rounding artefact. */
    @Test
    fun `the safe radius holds a bezel margin back on every face`() {
        for (face in FACES) {
            val margin = face / 2f - PlateGeometry.safeRadius(face)
            assertTrue(
                "a ${face.toInt()} dp face should hold at least 5 dp back, held ${margin}",
                margin >= 5f,
            )
        }
    }

    /** A wider plate cannot ride as close to the edge. The ladder must be monotone. */
    @Test
    fun `wider tiers reach less far than narrower ones`() {
        for (face in FACES) {
            val core = PlateGeometry.reach(face, Plate.Core)
            val action = PlateGeometry.reach(face, Plate.Action)
            val band = PlateGeometry.reach(face, Plate.Band)
            assertTrue("Core should be the widest tier on ${face.toInt()} dp",
                PlateGeometry.width(face, Plate.Core) > PlateGeometry.width(face, Plate.Action))
            assertTrue("Action should be wider than Band on ${face.toInt()} dp",
                PlateGeometry.width(face, Plate.Action) > PlateGeometry.width(face, Plate.Band))
            assertTrue("Core should reach least far on ${face.toInt()} dp", core < action)
            assertTrue("Band should reach furthest on ${face.toInt()} dp", action < band)
        }
    }

    /**
     * Home's fixed parts have to fit without scrolling on the *smallest* face.
     *
     * Type and touch targets do not scale with the glass — 48 dp is 48 dp on
     * every wrist — so the small face is where the stack is tightest. What must
     * always be visible at rest is the status band, its rule, the rate, and the
     * pinned action bar; History and Settings are allowed to fall below the fold
     * on the 40 mm and be reached by scrolling.
     */
    @Test
    fun `the home essentials fit the smallest face without scrolling`() {
        val essentials = STATUS_BAND + RULE + HERO_READOUT + TOUCH_TARGET + 3 * PLATE_GAP
        val usable = PlateGeometry.usableHeight(WATCH9_40MM)
        assertTrue(
            "40 mm has $usable dp usable but the essentials need $essentials dp",
            essentials <= usable,
        )
    }

    /**
     * And the base face has room for the menu row on top of them.
     *
     * Note what is absent: a rule under the status band. Home carries none,
     * because with this stack it does not fit — adding one back costs 11 dp
     * (the hairline plus its gap) and pushes the menu row off a 240 dp face.
     * The other screens have shorter stacks and keep theirs.
     */
    @Test
    fun `the base face fits the whole home stack`() {
        val whole =
            STATUS_BAND + HERO_READOUT + TOUCH_TARGET + TOUCH_TARGET + 3 * PLATE_GAP
        val usable = PlateGeometry.usableHeight(WATCH9_44MM)
        assertTrue(
            "44 mm has $usable dp usable but the whole stack needs $whole dp",
            whole <= usable,
        )
    }

    /** The published numbers, so a change to a fraction has to be a deliberate one. */
    @Test
    fun `the base face resolves to the documented tier widths`() {
        assertEquals(159.8, PlateGeometry.width(WATCH9_44MM, Plate.Core).toDouble(), 0.5)
        assertEquals(130.5, PlateGeometry.width(WATCH9_44MM, Plate.Action).toDouble(), 0.5)
        assertEquals(112.5, PlateGeometry.width(WATCH9_44MM, Plate.Band).toDouble(), 0.5)
        assertEquals(106.4, PlateGeometry.safeRadius(WATCH9_44MM).toDouble(), 0.5)
    }

    // Measured heights of the fixed parts of Home, in dp.
    private companion object {
        const val STATUS_BAND = 16f
        const val RULE = 1f
        const val HERO_READOUT = 34f
        const val TOUCH_TARGET = 48f
        const val PLATE_GAP = 10f
    }
}
