package com.pvz2.auto.task

import android.graphics.Rect

/**
 * The rule table the loop walks through, in order. The first rule whose template is
 * actually found on screen wins.
 *
 * Templates live in `app/src/main/assets/templates/` (see the README there). Rules whose
 * PNG is absent are skipped automatically, so this list can stay ahead of the artwork.
 *
 * `roi` values are 720p logical rectangles. Restricting the search region is what keeps
 * matching fast and stops a sprite being found somewhere it means something else.
 */
object Pvz2Tasks {

    /** Rows of the seed bank, roughly the bottom ninth of a 720p canvas. */
    private val SEED_BANK = Rect(0, 600, 760, 720)

    /** Sun tokens never appear over the seed bank, so skip that strip. */
    private val PLAY_FIELD = Rect(0, 0, 1280, 600)

    fun defaultActions(): List<BotAction> = listOf(
        // --- in battle ---------------------------------------------------------
        // Collect sun. Highest priority: it is the only time-critical resource.
        BotAction(
            template = "sun.png",
            roi = PLAY_FIELD,
            threshold = 0.75,
            afterTapDelayMs = 250
        ),
        // Plant the selected seed packet.
        BotAction(
            template = "seed_packet.png",
            roi = SEED_BANK,
            threshold = 0.78,
            afterTapDelayMs = 500
        ),
        // --- dialogs and menus -------------------------------------------------
        BotAction(template = "btn_continue.png", threshold = 0.80, afterTapDelayMs = 800),
        BotAction(template = "btn_close.png", threshold = 0.80, afterTapDelayMs = 500)
    )
}
