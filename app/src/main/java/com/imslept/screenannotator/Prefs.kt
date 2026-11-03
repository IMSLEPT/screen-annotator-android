package com.imslept.screenannotator

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

enum class Tool { PEN, PENCIL, HIGHLIGHTER, TEXT, SHAPE, ERASER }

enum class ShapeType { CIRCLE, ARROW, LINE, RECT }

/** Tutte le preferenze, salvate automaticamente a ogni modifica. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        }
    }

    // ---- Registrazione ----
    /** 0 nessuno, 1 microfono, 2 dispositivo, 3 entrambi */
    var audioMode: Int
        get() = sp.getInt("audioMode", 1)
        set(v) = sp.edit().putInt("audioMode", v).apply()

    var showTouches: Boolean
        get() = sp.getBoolean("showTouches", false)
        set(v) = sp.edit().putBoolean("showTouches", v).apply()

    /** Lato corto del video: 0 = nativo, altrimenti 720 / 1080 / 1440 */
    var resolution: Int
        get() = sp.getInt("resolution", 1080)
        set(v) = sp.edit().putInt("resolution", v).apply()

    var fps: Int
        get() = sp.getInt("fps", 60)
        set(v) = sp.edit().putInt("fps", v).apply()

    var bitrateMbps: Int
        get() = sp.getInt("bitrateMbps", 16)
        set(v) = sp.edit().putInt("bitrateMbps", v).apply()

    // ---- Strumenti ----
    fun color(t: Tool): Int = sp.getInt("color_${t.name}", defaultColor(t))
    fun setColor(t: Tool, v: Int) = sp.edit().putInt("color_${t.name}", v).apply()

    /** Spessore in dp (per il testo: dimensione in sp). */
    fun width(t: Tool): Int = sp.getInt("width_${t.name}", defaultWidth(t))
    fun setWidth(t: Tool, v: Int) = sp.edit().putInt("width_${t.name}", v).apply()

    /** Opacita' 10..100 */
    fun opacity(t: Tool): Int = sp.getInt("opacity_${t.name}", defaultOpacity(t))
    fun setOpacity(t: Tool, v: Int) = sp.edit().putInt("opacity_${t.name}", v).apply()

    var shapeType: ShapeType
        get() = ShapeType.valueOf(sp.getString("shapeType", ShapeType.CIRCLE.name)!!)
        set(v) = sp.edit().putString("shapeType", v.name).apply()

    var lastTool: Tool
        get() = Tool.valueOf(sp.getString("lastTool", Tool.PEN.name)!!)
        set(v) = sp.edit().putString("lastTool", v.name).apply()

    // ---- Scia a scomparsa ----
    /** true = modalita' temporanea (le annotazioni svaniscono) */
    var temporaryDefault: Boolean
        get() = sp.getBoolean("temporaryDefault", true)
        set(v) = sp.edit().putBoolean("temporaryDefault", v).apply()

    /** Quanto resta visibile il tratto prima di iniziare a svanire (ms) */
    var holdMs: Int
        get() = sp.getInt("holdMs", 1800)
        set(v) = sp.edit().putInt("holdMs", v).apply()

    /** Durata della dissolvenza (ms): piu' alta = piu' lenta */
    var fadeMs: Int
        get() = sp.getInt("fadeMs", 700)
        set(v) = sp.edit().putInt("fadeMs", v).apply()

    /** 0 = svanisce tutto il disegno insieme, 1 = svanisce il tratto punto per punto (scia) */
    var fadeMode: Int
        get() = sp.getInt("fadeMode", 0)
        set(v) = sp.edit().putInt("fadeMode", v).apply()

    // ---- Barra flottante ----
    /** 0 alto-sinistra, 1 alto-destra, 2 basso-sinistra, 3 basso-destra */
    var toolbarCorner: Int
        get() = sp.getInt("toolbarCorner", 1)
        set(v) = sp.edit().putInt("toolbarCorner", v).apply()

    /** true = la barra si chiude da sola e diventa invisibile (cosi' non finisce nel video) */
    var hideBar: Boolean
        get() = sp.getBoolean("hideBar", true)
        set(v) = sp.edit().putBoolean("hideBar", v).apply()

    /** Scala della barra in percentuale 70..150 */
    var toolbarScale: Int
        get() = sp.getInt("toolbarScale", 100)
        set(v) = sp.edit().putInt("toolbarScale", v).apply()

    private fun defaultColor(t: Tool): Int = when (t) {
        Tool.PEN -> Color.parseColor("#E53935")
        Tool.PENCIL -> Color.parseColor("#212121")
        Tool.HIGHLIGHTER -> Color.parseColor("#FFEB3B")
        Tool.TEXT -> Color.parseColor("#E53935")
        Tool.SHAPE -> Color.parseColor("#E53935")
        Tool.ERASER -> Color.WHITE
    }

    private fun defaultWidth(t: Tool): Int = when (t) {
        Tool.PEN -> 5
        Tool.PENCIL -> 3
        Tool.HIGHLIGHTER -> 16
        Tool.TEXT -> 24
        Tool.SHAPE -> 5
        Tool.ERASER -> 24
    }

    private fun defaultOpacity(t: Tool): Int = when (t) {
        Tool.PEN -> 100
        Tool.PENCIL -> 85
        Tool.HIGHLIGHTER -> 40
        Tool.TEXT -> 100
        Tool.SHAPE -> 100
        Tool.ERASER -> 100
    }
}
