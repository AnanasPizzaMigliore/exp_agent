/*
* Copyright (C) 2026 exp agent contributors
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

package com.expagent.agent

/**
* The next instruction, when it does not need a model to work it out.
*
* Measured on the phone, the planner call costs 5-6 seconds of a blind user's
* time and is almost exactly a third of the wait between pressing Capture and
* hearing anything. On the turns where the frame is simply bad - too far away,
* or so close the printing is cut off - what came back for that six seconds was
* "move the phone closer to the package". That is a lookup table, and it is
* served here instead.
*
* The line this draws is between fixing the photograph and deciding where to
* look, and it matters:
*
*   - Frame quality is mechanical. One visible defect, one obvious remedy, no
*     knowledge of the package required. Handled locally.
*   - Everything else is judgement. Which face to try next, whether a jar's code
*     is on the lid, whether to answer, whether to give up. Those stay with the
*     planner, which has the belief state and the history and is the only thing
*     that can abstain.
*
* So this deliberately declines more often than it could. It never speaks when a
* date is legible, never runs twice in a row beyond [MAX_CONSECUTIVE], and never
* runs when the user did not carry out what was last asked - all three are cases
* where the planner's memory changes what should be said, and none of them are
* worth saving six seconds on.
*/
object LocalGuidance {

    /**
    * How many turns in a row may be answered locally before the planner gets
    * the next one regardless.
    *
    * The session has no other way out: ABSTAIN is the planner's decision alone,
    * so a fast path that never yielded could keep a user turning a package over
    * forever. Two is enough to cover "closer" and then "closer again" - the
    * common pair - while guaranteeing the planner sees every third turn.
    */
    const val MAX_CONSECUTIVE=2

    /**
    * Problems in the order they block a reading, most blocking first, and each
    * to be carried out by someone who cannot see the package or the result.
    *
    * Distance comes first because that is what the planner itself chose on
    * frames reporting it alongside anything else, and moving closer usually
    * fixes the blur too.
    *
    * The first versions failed that test. "Tilt until the shine is gone" and
    * "move back until all of it fits" both ask the user to judge something
    * visual - they cannot see the glare or the frame, so they have no way to
    * know when to stop. They are relative movements now, and whether the
    * movement worked is decided from the next photograph, which is this
    * program's job rather than the user's.
    *
    * Glare and a finger over the print still have no approved command at all -
    * the words for them have to be written, and the only thing that writes words
    * is the planner. This path cannot serve those frames, so it declines them; a
    * fast path that invented its own sentence would be reintroducing exactly the
    * unchecked wording [GuidanceTemplates] exists to prevent.
    *
    * Blur is the one that came back. It was answered here with "hold still"
    * once, and that was wrong twice over: the words were invented, and low light
    * depresses sharpness in the same way an unsteady hand does, so half the
    * frames it answered needed a different remedy entirely. Both objections are
    * now answerable. [GuidanceAction.ELBOWS_IN] is approved wording, and the
    * capture gate measures the hand directly, so the cause is known rather than
    * assumed - which is why this remedy is the only one that carries a
    * condition. Without the measurement it stays the planner's.
    */
    private val REMEDIES=listOf(
        Remedy("too_far", GuidanceAction.MOVE_CLOSER),
        Remedy("cut_off", GuidanceAction.MOVE_FARTHER),
        Remedy("blur", GuidanceAction.ELBOWS_IN, needsUnsteadyHand=true),
        )

    private class Remedy(
        val problem: String,
        val action: GuidanceAction,

        /** Served only when the gate has measured the hand, never on the frame alone. */
        val needsUnsteadyHand: Boolean=false,
        )

    /**
    * Statuses that leave a movement outstanding, and so belong to the planner:
    * only it knows what it asked for and can ask for the remainder.
    *
    * Public because the brace guard in InspectionController needs the same
    * answer. A second list of "still waiting on the user" would be a second
    * definition of the phrase, free to drift from this one.
    */
    val UNFINISHED_MOVEMENT=setOf(
        ActionStatus.NOT_COMPLETED,
        ActionStatus.PARTIAL,

        // The asked-for face still has not been seen, and the package is now
        // turned away from it. Which face to ask for next is a decision this
        // cannot make: it would read "too far away" off a perfectly good
        // photograph of the wrong end and ask for that same wrong end, closer.
        ActionStatus.WRONG_FACE,
        )

    /**
    * Whether asking the user to brace their elbows is warranted right now.
    *
    * The first participant run asked for it twice in one session and both were
    * wrong: once on a frame reporting no problems at all, and once while the
    * user had a turn outstanding that they had just made the wrong way. The
    * cause was that the gate's tremor verdict is LATCHED - it must be, or the
    * camera would rebind mid-session - and that permanently true flag was handed
    * to the planner every turn. A sticky verdict is right for a camera and wrong
    * for a decision, so the decision uses this instead.
    *
    * Four conditions, one for each way it went wrong:
    *
    *   - the hand was measured unsteady, which no frame can say on its own
    *   - THIS frame is smeared, so there is something for bracing to fix
    *   - nothing is outstanding, because a half-made turn outranks posture
    *   - it has not been asked already, since bracing is done once or not at all
    *
    * Lives here, beside [UNFINISHED_MOVEMENT], so the fast path, the planner's
    * payload and the guard that overrules the planner all read one rule.
    */
    fun braceWarranted(
        handMeasuredUnsteady: Boolean,
        alreadyBraced: Boolean,
        perception: Perception,
        status: ActionStatus,
        ): Boolean =
    handMeasuredUnsteady&&
    !alreadyBraced&&
    perception.problems.any { it.trim().lowercase()=="blur" }&&
    status !in UNFINISHED_MOVEMENT

    /**
    * A CONTINUE policy for this frame, or null to let the planner decide.
    *
    * Null is the safe answer and is returned whenever anything is not plainly
    * mechanical. Never returns ANSWER or ABSTAIN: a date reaching the user, and
    * a session giving up, both stay with the model.
    */
    fun policyFor(
        perception: Perception,
        verification: Verification?,
        consecutive: Int,

        /**
        * Whether the capture gate has measured this user's hand as trembling.
        *
        * Defaults to false, which is the answer whenever nothing measured it -
        * a session with the stability monitor switched off, or one that has not
        * seen enough of the hand yet. Unmeasured must read the same as steady
        * here, because the remedy it unlocks is wrong for a dim shelf.
        */
        handUnsteady: Boolean=false,
        ): Policy? {
        // Something readable is on screen. Whether it may be spoken is exactly
        // the decision this must not make.
        if (perception.date_legible)
        return null

        if (consecutive>=MAX_CONSECUTIVE)
        return null

        // An outstanding movement is the planner's to follow up.
        //
        // not_completed: its prompt tells it to consider that its own phrasing
        // was the problem and try again differently. Repeating the same sentence
        // louder is what this would do instead.
        //
        // partial: it is told to ask for the REST of that movement rather than
        // starting a new one. This cannot - it has no idea what was asked. A
        // half-turned jar that also came out too far away would be told to move
        // closer, silently abandoning the turn the user was halfway through.
        if (verification!=null && verification.action_status in UNFINISHED_MOVEMENT)
        return null

        val problems=perception.problems.filter { it.isNotBlank() && it!="none" }.toSet()

        // A clean frame with nothing legible on it is not a photography problem.
        // It means the date is somewhere else, which is a question about the
        // package, not about the camera.
        if (problems.isEmpty())
        return null

        val remedy=REMEDIES.firstOrNull {
            it.problem in problems&&(handUnsteady||!it.needsUnsteadyHand)
            } ?: return null

        return Policy(
            // The command is the action: choosing MOVE_CLOSER is choosing to
            // continue by moving closer.
            action=remedy.action.name,
            // Rendered again by the caller. Filled in here anyway so that
            // nothing downstream can end up with no words at all.
            utterance=GuidanceTemplates.render(remedy.action),
            rationale="frame quality: ${remedy.problem}",
            expected_change=GuidanceTemplates.expectedChange(remedy.action),
            )
        }
    }
