/*
* Copyright (C) 2026 VScan contributors
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, version 3.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package com.rastislavkish.vscan.agent

/**
* The capture controllers a session can be run under, by name.
*
* These are the same configurations [ShadowArms] evaluates alongside the live
* one. Naming them in one place means an arm measured in shadow and the same arm
* measured live are the same controller, rather than two constructions that
* drifted apart.
*
* Why this exists at all: the shadow arms are paired by construction but honest
* only up to the live capture, because an arm that fired at a different moment
* would have changed everything after it. That is a fair objection to resting a
* mechanism claim on them. Selecting the live gate lets each ablation be the
* thing that actually drives the camera, so the comparison is between real
* sessions and the counterfactual caveat does not apply.
*
* Q_quality_aware uses the same ticket-owning controller as E_full, with only
* its readiness and freshness rules changed. It can therefore drive a real
* session with the same camera and lifecycle safeguards instead of existing
* only as a counterfactual shadow decision.
*/
object GateConditions {

    /** The controller as shipped. */
    const val FULL="E_full"

    /** Stable, non-duplicate capture without instruction-relative state. */
    const val QUALITY="Q_quality_aware"

    val names: Set<String>
    get() = setOf(FULL, QUALITY, "E_no_onset", "E_no_settling_hold", "E_no_match_hold",
        "E_no_tremor_tolerance")

    /**
    * The scripted behaviours a session can be run under, for the settings
    * screen. Empty means the session is not part of the ablation and its
    * captures get no verdict, which is the right answer for ordinary use.
    *
    * The scorer matches on these prefixes, so a label that is not one of them
    * produces counted-but-unscored captures rather than a wrong verdict.
    */
    val blocks: List<String>
    get() = listOf(
        "",
        "A_idle",
        "B_pause",
        "C_normal",
        "D_slow_turn",
        "E_partial_completion",
        "F_turn_and_return",
        "G_harmless_pending",
        "H_new_view_pending",
        )

    /**
    * The study package identifiers offered on the settings screen.
    *
    * R4 pairs a product's grounded run against its control run, and the only
    * thing in the log that says which package was in the hand is the run label.
    * Before this list it could be set only from run_config.json, which meant a
    * laptop push between every session - with a blindfolded participant waiting,
    * and a wrong or stale label silently costing the pair.
    *
    * Deliberately not product_hint: this is written to the log and read by
    * nothing else, so setting it cannot tell the agent where the date is or
    * disturb the retrieval condition.
    *
    * V-prefixed so a study package can never collide with a P-numbered corpus
    * product, and twelve because a paired within-subject design needs more
    * packages than participants have patience for anyway.
    */
    /**
    * The participant pseudonyms offered on the settings screen.
    *
    * The log records which package was in the hand but nothing about who was
    * holding it, so a within-subject analysis would otherwise depend on
    * transcribing a paper sheet across sixty sessions, where one slip silently
    * reassigns a session to the wrong person and the clustering is wrong with no
    * symptom. S-prefixed to keep subjects distinct from V-numbered packages.
    *
    * A pseudonym, never a name: it is written to a log that leaves the phone.
    */
    val participants: List<String>
    get() = listOf("") + (1..12).map { "S%02d".format(it) }

    val studyLabels: List<String>
    get() = listOf("") + (1..12).map { "V%02d".format(it) }

    /**
    * The identity a session is compared against to catch a stale package label.
    *
    * The product spinner is sticky and nothing on screen shows it, so starting
    * the next package without changing it records that session under the
    * previous package's name - with nothing in the events to say so, and no way
    * afterwards to recover which package was in the hand. Comparing this key
    * against the previous session's does not prevent that; it makes it visible.
    *
    * The participant is part of the key, and that is the whole subtlety. A
    * counterbalanced order legitimately has the next participant beginning on
    * the package the last one finished with, so a key of the package alone
    * would flag every participant boundary - and a warning that fires when
    * nothing is wrong is one nobody reads by the third day.
    *
    * Empty when neither is set, which is ordinary non-study use and has nothing
    * to be stale.
    */
    fun runKey(participant: String, runLabel: String?): String =
    if (runLabel!=null||participant.isNotEmpty()) "$participant/${runLabel ?: ""}" else ""

    /**
    * The controller for a condition name, or null if the name is not one.
    *
    * Each ablation zeroes exactly one hold, so a session run under it isolates
    * what that separation was doing:
    *
    *   E_no_onset          no sustained-change requirement, so any flicker can
    *                       start an event
    *   E_no_settling_hold  no settling requirement, so a movement can be
    *                       captured before it stops
    *   E_no_match_hold     no returned-view hold, so a momentary match counts
    *   E_no_tremor_tolerance
    *                       no grace inside the quiet hold, so any single break
    *                       restarts it - the behaviour before the tolerance was
    *                       added, and the arm a tremulous session has to be
    *                       compared against for the tolerance to have been
    *                       measured rather than asserted
    *
    * E_no_freshness is not here: freshness is applied by the caller around the
    * gate rather than inside it, so it is not a constructor parameter.
    */
    fun of(name: String?): EventCaptureController? = when (name) {
        FULL -> EventCaptureController()
        QUALITY -> EventCaptureController(
            mode=CaptureGateMode.QUALITY_AWARE,
            checkResponseFreshness=false,
            )
        "E_no_onset" -> EventCaptureController(onsetNs=0L)
        "E_no_settling_hold" -> EventCaptureController(actionHoldNs=0L)
        "E_no_match_hold" -> EventCaptureController(responseHoldNs=0L)
        "E_no_tremor_tolerance" -> EventCaptureController(quietGraceNs=0L)
        else -> null
        }
    }
