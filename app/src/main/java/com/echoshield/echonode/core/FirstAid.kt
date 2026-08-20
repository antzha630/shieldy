package com.echoshield.echonode.core

import com.echoshield.echonode.core.contracts.FirstAidStep

/**
 * Bystander first-aid guidance shown during an active incident.
 *
 * Ordered by what saves lives soonest: uncontrolled haemorrhage is the leading
 * preventable cause of death from gunshot wounds, so bleeding control leads and
 * airway/shock follow. The self-protection step comes first while a threat is
 * still active because a second casualty helps nobody.
 *
 * This mirrors `firstAidGuidance()` in `server/src/server.js` so a student sees the
 * same instructions whether they are on the Android node or the Opius web client.
 */
object FirstAid {

    const val DISCLAIMER =
        "General guidance only, not a substitute for professional medical care. " +
            "Call emergency services when it is safe to do so."

    fun headline(injuredCount: Int): String = if (injuredCount > 0) {
        "$injuredCount injured reported — control bleeding first."
    } else {
        "If someone is hurt, control life-threatening bleeding first."
    }

    /**
     * @param threatActive while true, lead with self-protection and close with a
     *   shelter-in-place reporting step; once cleared those drop away.
     */
    fun steps(threatActive: Boolean): List<FirstAidStep> = buildList {
        if (threatActive) {
            add(
                FirstAidStep(
                    title = "Protect yourself first",
                    detail = "Do not move toward danger to reach an injured person. Only give aid if you " +
                        "are behind a locked or barricaded door, or the area is confirmed clear."
                )
            )
        }
        add(
            FirstAidStep(
                title = "Stop life-threatening bleeding",
                detail = "Press hard directly on the wound with a cloth or clothing and do not let up. " +
                    "For an arm or leg bleed you cannot control, apply a tourniquet 5-8 cm above the " +
                    "wound (not on a joint), tighten until the bleeding stops, and note the time."
            )
        )
        add(
            FirstAidStep(
                title = "Keep them breathing",
                detail = "If they are unconscious but breathing, roll them onto their side into the " +
                    "recovery position. If they are not breathing and you are trained, start CPR once " +
                    "it is safe to do so."
            )
        )
        add(
            FirstAidStep(
                title = "Prevent shock",
                detail = "Keep the person still, lying down, and warm with a jacket or blanket. " +
                    "Reassure them and keep checking that the bleeding stays controlled. " +
                    "Do not give food or water."
            )
        )
        if (threatActive) {
            add(
                FirstAidStep(
                    title = "Report while sheltering",
                    detail = "Silence your phone, stay low and out of line-of-sight, and send your room " +
                        "or nearest landmark and the number of injured so responders can prioritise."
                )
            )
        }
    }
}
