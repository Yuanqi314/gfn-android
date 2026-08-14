package dev.gfn.webrtc

import android.view.KeyEvent
import dev.gfn.input.GfnKey

/** Android KEYCODE -> Windows VK + Set-1 scan code。不能直接把 Android scanCode 当 Windows scan code。 */
object AndroidKeyboardMapper {
    const val MOD_SHIFT = 0x0001
    const val MOD_CONTROL = 0x0002
    const val MOD_ALT = 0x0004
    const val MOD_META = 0x0008

    private fun k(vk: Int, scan: Int, modifier: Int = 0) = GfnKey(vk, scan, modifier)

    private val keys: Map<Int, GfnKey> = mapOf(
        KeyEvent.KEYCODE_A to k(0x41, 0x1E), KeyEvent.KEYCODE_B to k(0x42, 0x30),
        KeyEvent.KEYCODE_C to k(0x43, 0x2E), KeyEvent.KEYCODE_D to k(0x44, 0x20),
        KeyEvent.KEYCODE_E to k(0x45, 0x12), KeyEvent.KEYCODE_F to k(0x46, 0x21),
        KeyEvent.KEYCODE_G to k(0x47, 0x22), KeyEvent.KEYCODE_H to k(0x48, 0x23),
        KeyEvent.KEYCODE_I to k(0x49, 0x17), KeyEvent.KEYCODE_J to k(0x4A, 0x24),
        KeyEvent.KEYCODE_K to k(0x4B, 0x25), KeyEvent.KEYCODE_L to k(0x4C, 0x26),
        KeyEvent.KEYCODE_M to k(0x4D, 0x32), KeyEvent.KEYCODE_N to k(0x4E, 0x31),
        KeyEvent.KEYCODE_O to k(0x4F, 0x18), KeyEvent.KEYCODE_P to k(0x50, 0x19),
        KeyEvent.KEYCODE_Q to k(0x51, 0x10), KeyEvent.KEYCODE_R to k(0x52, 0x13),
        KeyEvent.KEYCODE_S to k(0x53, 0x1F), KeyEvent.KEYCODE_T to k(0x54, 0x14),
        KeyEvent.KEYCODE_U to k(0x55, 0x16), KeyEvent.KEYCODE_V to k(0x56, 0x2F),
        KeyEvent.KEYCODE_W to k(0x57, 0x11), KeyEvent.KEYCODE_X to k(0x58, 0x2D),
        KeyEvent.KEYCODE_Y to k(0x59, 0x15), KeyEvent.KEYCODE_Z to k(0x5A, 0x2C),

        KeyEvent.KEYCODE_1 to k(0x31, 0x02), KeyEvent.KEYCODE_2 to k(0x32, 0x03),
        KeyEvent.KEYCODE_3 to k(0x33, 0x04), KeyEvent.KEYCODE_4 to k(0x34, 0x05),
        KeyEvent.KEYCODE_5 to k(0x35, 0x06), KeyEvent.KEYCODE_6 to k(0x36, 0x07),
        KeyEvent.KEYCODE_7 to k(0x37, 0x08), KeyEvent.KEYCODE_8 to k(0x38, 0x09),
        KeyEvent.KEYCODE_9 to k(0x39, 0x0A), KeyEvent.KEYCODE_0 to k(0x30, 0x0B),

        KeyEvent.KEYCODE_ENTER to k(0x0D, 0x1C),
        KeyEvent.KEYCODE_ESCAPE to k(0x1B, 0x01),
        KeyEvent.KEYCODE_DEL to k(0x08, 0x0E),
        KeyEvent.KEYCODE_TAB to k(0x09, 0x0F),
        KeyEvent.KEYCODE_SPACE to k(0x20, 0x39),
        KeyEvent.KEYCODE_CAPS_LOCK to k(0x14, 0x3A),

        KeyEvent.KEYCODE_MINUS to k(0xBD, 0x0C), KeyEvent.KEYCODE_EQUALS to k(0xBB, 0x0D),
        KeyEvent.KEYCODE_LEFT_BRACKET to k(0xDB, 0x1A), KeyEvent.KEYCODE_RIGHT_BRACKET to k(0xDD, 0x1B),
        KeyEvent.KEYCODE_BACKSLASH to k(0xDC, 0x2B), KeyEvent.KEYCODE_SEMICOLON to k(0xBA, 0x27),
        KeyEvent.KEYCODE_APOSTROPHE to k(0xDE, 0x28), KeyEvent.KEYCODE_GRAVE to k(0xC0, 0x29),
        KeyEvent.KEYCODE_COMMA to k(0xBC, 0x33), KeyEvent.KEYCODE_PERIOD to k(0xBE, 0x34),
        KeyEvent.KEYCODE_SLASH to k(0xBF, 0x35),

        KeyEvent.KEYCODE_F1 to k(0x70, 0x3B), KeyEvent.KEYCODE_F2 to k(0x71, 0x3C),
        KeyEvent.KEYCODE_F3 to k(0x72, 0x3D), KeyEvent.KEYCODE_F4 to k(0x73, 0x3E),
        KeyEvent.KEYCODE_F5 to k(0x74, 0x3F), KeyEvent.KEYCODE_F6 to k(0x75, 0x40),
        KeyEvent.KEYCODE_F7 to k(0x76, 0x41), KeyEvent.KEYCODE_F8 to k(0x77, 0x42),
        KeyEvent.KEYCODE_F9 to k(0x78, 0x43), KeyEvent.KEYCODE_F10 to k(0x79, 0x44),
        KeyEvent.KEYCODE_F11 to k(0x7A, 0x57), KeyEvent.KEYCODE_F12 to k(0x7B, 0x58),

        KeyEvent.KEYCODE_INSERT to k(0x2D, 0xE052), KeyEvent.KEYCODE_MOVE_HOME to k(0x24, 0xE047),
        KeyEvent.KEYCODE_PAGE_UP to k(0x21, 0xE049), KeyEvent.KEYCODE_FORWARD_DEL to k(0x2E, 0xE053),
        KeyEvent.KEYCODE_MOVE_END to k(0x23, 0xE04F), KeyEvent.KEYCODE_PAGE_DOWN to k(0x22, 0xE051),
        KeyEvent.KEYCODE_DPAD_RIGHT to k(0x27, 0xE04D), KeyEvent.KEYCODE_DPAD_LEFT to k(0x25, 0xE04B),
        KeyEvent.KEYCODE_DPAD_DOWN to k(0x28, 0xE050), KeyEvent.KEYCODE_DPAD_UP to k(0x26, 0xE048),
        KeyEvent.KEYCODE_SYSRQ to k(0x2C, 0xE037), KeyEvent.KEYCODE_SCROLL_LOCK to k(0x91, 0x46),
        KeyEvent.KEYCODE_BREAK to k(0x13, 0x45), KeyEvent.KEYCODE_MENU to k(0x5D, 0xE05D),

        KeyEvent.KEYCODE_NUM_LOCK to k(0x90, 0xE045), KeyEvent.KEYCODE_NUMPAD_DIVIDE to k(0x6F, 0xE035),
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY to k(0x6A, 0x37), KeyEvent.KEYCODE_NUMPAD_SUBTRACT to k(0x6D, 0x4A),
        KeyEvent.KEYCODE_NUMPAD_ADD to k(0x6B, 0x4E), KeyEvent.KEYCODE_NUMPAD_ENTER to k(0x0D, 0xE01C),
        KeyEvent.KEYCODE_NUMPAD_1 to k(0x61, 0x4F), KeyEvent.KEYCODE_NUMPAD_2 to k(0x62, 0x50),
        KeyEvent.KEYCODE_NUMPAD_3 to k(0x63, 0x51), KeyEvent.KEYCODE_NUMPAD_4 to k(0x64, 0x4B),
        KeyEvent.KEYCODE_NUMPAD_5 to k(0x65, 0x4C), KeyEvent.KEYCODE_NUMPAD_6 to k(0x66, 0x4D),
        KeyEvent.KEYCODE_NUMPAD_7 to k(0x67, 0x47), KeyEvent.KEYCODE_NUMPAD_8 to k(0x68, 0x48),
        KeyEvent.KEYCODE_NUMPAD_9 to k(0x69, 0x49), KeyEvent.KEYCODE_NUMPAD_0 to k(0x60, 0x52),
        KeyEvent.KEYCODE_NUMPAD_DOT to k(0x6E, 0x53),

        KeyEvent.KEYCODE_CTRL_LEFT to k(0xA2, 0x1D, MOD_CONTROL),
        KeyEvent.KEYCODE_CTRL_RIGHT to k(0xA3, 0xE01D, MOD_CONTROL),
        KeyEvent.KEYCODE_SHIFT_LEFT to k(0xA0, 0x2A, MOD_SHIFT),
        KeyEvent.KEYCODE_SHIFT_RIGHT to k(0xA1, 0x36, MOD_SHIFT),
        KeyEvent.KEYCODE_ALT_LEFT to k(0xA4, 0x38, MOD_ALT),
        KeyEvent.KEYCODE_ALT_RIGHT to k(0xA5, 0xE038, MOD_ALT),
        KeyEvent.KEYCODE_META_LEFT to k(0x5B, 0xE05B, MOD_META),
        KeyEvent.KEYCODE_META_RIGHT to k(0x5C, 0xE05C, MOD_META),
    )

    fun map(keyCode: Int): GfnKey? = keys[keyCode]

    /**
     * C3 OpenNOW CapsLock probe lock-state.
     *
     * Keep the C2 isolation invariant so only CapsLock affects type 19 during this test:
     * - Caps OFF -> 0x70
     * - Caps ON  -> 0x71
     *
     * NumLock/ScrollLock remain intentionally excluded to avoid the 0x74/0x75 interference
     * seen in the previous device log. The exact base-bit semantics remain undocumented.
     */
    fun lockKeysState(metaState: Int): Int =
        if (metaState and KeyEvent.META_CAPS_LOCK_ON != 0) 0x71 else 0x70

    fun modifiers(metaState: Int): Int {
        var result = 0
        if (metaState and KeyEvent.META_SHIFT_ON != 0) result = result or MOD_SHIFT
        if (metaState and KeyEvent.META_CTRL_ON != 0) result = result or MOD_CONTROL
        if (metaState and KeyEvent.META_ALT_ON != 0) result = result or MOD_ALT
        if (metaState and KeyEvent.META_META_ON != 0) result = result or MOD_META
        return result
    }
}
