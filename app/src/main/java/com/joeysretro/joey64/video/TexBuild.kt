package com.joeysretro.joey64.video

import android.content.Context
import java.io.File

/**
 * The core's `joey64_texpack_build` (texture packs, approved mockup texpacks4): a PNG pack made into its .hts with no game
 * loaded. Only TexConvertService calls it, in its own process (:texconv), so the core is never opened twice in one
 * process with a game. The host side is cpp/texpack_build.cpp.
 */
object TexBuild {
    init { System.loadLibrary("joey64host") }

    /** Called by the core once per texture added (the count so far), on the building thread; it may hold that thread. */
    fun interface Listener { fun onTexture(n: Int) }

    /** -1: the core has no build call (an older core), -2: the core can't be opened; else the textures made (0: none). */
    @JvmStatic external fun build(corePath: String, dir: String, rom: String, listener: Listener): Int

    @JvmStatic external fun supported(corePath: String): Boolean

    /** The bundled core (as MainActivity finds it; older test builds carry it under upstream's file names). */
    fun corePath(context: Context): String =
        listOf("libjoey64_libretro_android.so", "libmupen64plus_next_libretro_android.so", "mupen64plus_next_libretro_android.so")
            .map { File(context.applicationInfo.nativeLibraryDir, it) }
            .firstOrNull { it.exists() }?.path ?: File(context.applicationInfo.nativeLibraryDir, "libjoey64_libretro_android.so").path
}
