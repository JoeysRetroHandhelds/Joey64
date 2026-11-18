package com.joeysretro.joey64.controls

import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Several controls on one N64 button (approved multimap mockup A, 2026-11-17). */
class ControlsMultiTest {
    private val a = PadInput.Key(KeyEvent.KEYCODE_BUTTON_A)
    private val l3 = PadInput.Key(KeyEvent.KEYCODE_BUTTON_THUMBL)
    private val r3 = PadInput.Key(KeyEvent.KEYCODE_BUTTON_THUMBR)
    private val sel = PadInput.Key(KeyEvent.KEYCODE_BUTTON_SELECT)
    private val r2 = PadInput.Axis(MotionEvent.AXIS_RTRIGGER, true)
    private val base = ControlsSet.defaults().players[0]

    @Test fun addReplaceRemoveAndChangeGoesBackToOne() {
        val s = base.withAdded(N64Button.A, l3)
        assertEquals(listOf(a, l3), s.inputs(N64Button.A))
        assertEquals(a, s.buttons[N64Button.A])
        assertEquals(listOf(a, r3), s.withReplaced(N64Button.A, 1, r3).inputs(N64Button.A))
        assertEquals(listOf(l3), s.withRemoved(N64Button.A, 0).inputs(N64Button.A))
        assertEquals(listOf(r3), s.with(N64Button.A, r3).inputs(N64Button.A))
        assertEquals(emptyList<PadInput>(), s.with(N64Button.A, null).inputs(N64Button.A))
        // No twice, and four at most.
        assertEquals(listOf(a, l3), s.withAdded(N64Button.A, l3).inputs(N64Button.A))
        val four = s.withAdded(N64Button.A, r3).withAdded(N64Button.A, sel).withAdded(N64Button.A, r2)
        assertEquals(listOf(a, l3, r3, sel), four.inputs(N64Button.A))
        assertEquals(setOf("A"), s.differences(base))
    }

    @Test fun extrasSurviveJsonAndOldFilesKeepWhatTheyHad() {
        val s = base.withAdded(N64Button.A, l3).withAdded(N64Button.A, r2).withAdded(N64Button.Z, r3)
        val back = PlayerSetup.fromJson(JSONObject(s.toJson().toString()), base)
        assertEquals(s, back)
        assertEquals(listOf(a, l3, r2), back.inputs(N64Button.A))
        // A file from before has no "extra": its buttons are one each, not the fallback's extras.
        val old = JSONObject(base.toJson().toString())
        assertEquals(base, PlayerSetup.fromJson(old, s))
        assertFalse(base.toJson().has("extra"))
    }

    @Test fun anyOfAButtonsInputsPressesIt() {
        val p = PlayerInput(base.withAdded(N64Button.A, l3))
        p.key(KeyEvent.KEYCODE_BUTTON_THUMBL, true)
        assertEquals(1 shl N64Button.A.retroBit, p.buttons())
        assertTrue(p.uses(KeyEvent.KEYCODE_BUTTON_THUMBL))
        p.key(KeyEvent.KEYCODE_BUTTON_A, true)
        assertEquals(1 shl N64Button.A.retroBit, p.buttons())
        p.release()
        assertEquals(0, p.buttons())
    }

    @Test fun theRowSaysThemWithOr() {
        assertEquals(InputNames.NOT_SET, InputNames.list(emptyList(), null))
        val n = listOf(a, l3, r3, sel).map { InputNames.of(it, null) }
        assertEquals(n[0], InputNames.list(listOf(a), null))
        assertEquals("${n[0]} or ${n[1]}", InputNames.list(listOf(a, l3), null))
        assertEquals("${n[0]}, ${n[1]} or ${n[2]}", InputNames.list(listOf(a, l3, r3), null))
        assertEquals("${n[0]} or 3 more", InputNames.list(listOf(a, l3, r3, sel), null))
        // A D-pad direction with a second button isn't "D-pad" any more.
        assertEquals(null, InputNames.group(base.withAdded(N64Button.DUp, l3), N64Button.DPAD))
    }
}
