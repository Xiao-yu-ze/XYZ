package com.pvz2.auto.core

import kotlin.random.Random

/** Injects touches at physical screen coordinates. */
interface TouchInjector {

    fun tap(x: Float, y: Float)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long)

    /** Releases backend resources. Safe to call multiple times. */
    fun release() = Unit
}

/**
 * +/-5 px of random offset applied to every synthetic tap.
 *
 * Pixel-identical repeated taps are trivially fingerprintable by anti-automation checks,
 * and a small offset also mirrors how a real finger lands.
 */
object TapJitter {
    private const val RADIUS = 5

    fun dx(): Int = Random.nextInt(-RADIUS, RADIUS + 1)

    fun dy(): Int = Random.nextInt(-RADIUS, RADIUS + 1)
}
