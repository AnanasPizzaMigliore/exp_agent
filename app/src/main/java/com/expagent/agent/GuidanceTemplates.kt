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
* The things the agent is allowed to ask for, and the exact words for each.
*
* The planner used to write its own sentence, and three faults followed from
* that which no amount of prompt wording removed. It called the same object a
* cup on one turn and a can on the next. It asked users to judge things they
* could not see - "until the shine is gone". And it padded instructions to
* thirteen words and three and a half seconds of speech on turns that took
* eight.
*
* Now the model chooses an action and this renders it. The three faults become
* impossible rather than discouraged: the noun is chosen once per session from
* geometry, the wording is fixed and testable, and nothing here can ask for a
* visual judgement because no template does.
*
* Eleven of the twelve are one movement of the PACKAGE in the user's free hand,
* with the phone staying where it is: six ways to move it and five ways to turn
* it. A direction is something the user can carry out without knowing anything
* about the object they are holding, which is the whole difficulty - see POLICY
* in [Prompts].
*
* [ELBOWS_IN] is the twelfth and is not a direction. It was added when the
* capture gate learned to measure a tremor, because a defect the phone can now
* recognise every turn deserves a remedy the phone can name every turn, and
* nothing in eleven directions addresses a hand that shakes wherever it puts
* the package.
*
* The cost is expressiveness. Glare, a finger over the print and an empty frame
* are still not actions. A situation outside this set cannot be phrased here at
* all, which is why the planner still returns an utterance and the caller falls
* back to it when the action is null or unknown. Losing precision is acceptable;
* losing the ability to say anything is not.
*
* The spoken words are the command and nothing more - "Turn left." - so the
* whole vocabulary is twelve phrases the user hears the same way every time.
*
* TURN_* names where the face now pointing at the phone travels, so the side
* that comes into view is always the opposite one. Turning the package to the
* left brings the right-hand side to the phone; tipping it down brings the top
* to the phone. One convention for all five, stated the same way in the prompt,
* because a planner and a template that disagree about which way is "up" ask
* for the face they have already seen.
*/
enum class GuidanceAction {

    /** Straight across, to your left; the package keeps facing the phone. */
    MOVE_LEFT,

    MOVE_RIGHT,

    /** Straight up and straight down: a different part of it fills the frame. */
    MOVE_UP,

    MOVE_DOWN,

    MOVE_CLOSER,

    MOVE_FARTHER,

    /** Quarter turn about the upright axis; the right-hand side comes round. */
    TURN_LEFT,

    /** The same the other way; the left-hand side comes round. */
    TURN_RIGHT,

    /** Tipped backwards, until the base faces the phone. */
    TURN_UP,

    /** Tipped forwards, until the top faces the phone. */
    TURN_DOWN,

    /** Half turn, for the opposite broad face of a box or a bag. */
    TURN_BACK,

    /**
    * Elbows brought in to the sides, against a hand that will not hold still.
    *
    * The twelfth command, and the only one that is not a direction. It is here
    * because a tremor is the one frame defect this set could not address at
    * all: eleven ways to move a package do not help a hand that shakes wherever
    * it puts it, and the fallback - the planner writing its own sentence - is
    * the fallback for things that happen once, not for a condition that affects
    * every turn of every session that user ever runs.
    *
    * Telling someone to hold still is not it either. A hand with a tremor is
    * already trying; "hold still" asks for effort that is not the missing
    * ingredient and says, wrongly, that the failure was theirs. Bracing is
    * mechanical: elbows against the body couple both hands to the same mass, so
    * the phone and the package tremble together and what the matcher measures -
    * the movement BETWEEN them - falls even though neither hand is stiller.
    *
    * It keeps the package roughly where it was, which is why it is the brace to
    * ask for. Bringing the hands together would work as well and would change
    * the distance, undoing the framing the session had already reached.
    *
    * Named so that the mechanical two-word rendering is itself the instruction,
    * like the other ten: "Elbows in." needs no face named and no explanation.
    */
    ELBOWS_IN,
    }

object GuidanceTemplates {

    /**
    * The words for a command: the command itself, and nothing else - except for
    * the two tips, which name the face they want.
    *
    * These used to be feelable sentences - "Tip the base of the jar towards the
    * phone, then hold still." A two-word command does not need explaining, and
    * every word spoken is another second before the user can start moving on a
    * turn that already costs several seconds of model latency.
    *
    * What that sentence bought is not free to lose. It named the package the
    * same way every turn, and it repeated that stopping is what takes the
    * photograph. The noun is gone because there is no longer anywhere to put it;
    * the stopping rule is now said once, at the start, by
    * [Prompts.OPENING_INSTRUCTION], and never again.
    *
    * TURN_UP and TURN_DOWN are the exception, and they were the exception in a
    * real session. On 2026-09-16 a user hearing "Turn up." tipped a can the
    * other way and produced its top; the planner wanted the base, asked four
    * times, and the base was reached only by a retry capture. Ten of the twelve
    * commands say what the hand should do from the words alone. The two tips do
    * not: nothing about "up" says whether it is the face at the phone
    * that travels up or the face that arrives, and the convention that decides
    * it - a turn is named for where the face at the phone GOES - is stated in
    * POLICY, where only the model can read it. Naming the face costs about a
    * second of speech. Not naming it cost that session four turns.
    */
    fun render(action: GuidanceAction, nameTheFace: Boolean=true): String {
        if (!nameTheFace) return when (action) {
            // The wording every build before 16 September 2026 spoke, kept so
            // that the comparison has something to compare against. Not a
            // fallback: nothing should reach this except the control arm.
            GuidanceAction.TURN_UP -> "Turn up."
            GuidanceAction.TURN_DOWN -> "Turn down."
            else -> render(action)
            }

        return grounded(action)
        }

    private fun grounded(action: GuidanceAction): String = when (action) {
        GuidanceAction.MOVE_LEFT -> "Move left."
        GuidanceAction.MOVE_RIGHT -> "Move right."
        GuidanceAction.MOVE_UP -> "Move up."
        GuidanceAction.MOVE_DOWN -> "Move down."
        GuidanceAction.MOVE_CLOSER -> "Move closer."
        GuidanceAction.MOVE_FARTHER -> "Move farther."

        // A quarter turn about the upright axis. "Left" is the user's own left
        // and the hand can do it without knowing which face arrives, so these
        // stay as they are.
        GuidanceAction.TURN_LEFT -> "Turn left."
        GuidanceAction.TURN_RIGHT -> "Turn right."

        // The face is named because the direction alone does not determine the
        // movement. Both are faces a hand can find without sight.
        GuidanceAction.TURN_UP -> "Turn the bottom towards the phone."
        GuidanceAction.TURN_DOWN -> "Turn the top towards the phone."

        GuidanceAction.TURN_BACK -> "Turn back."

        // Two words, like the ten that say a direction, and for the same
        // reason: a hand can carry it out from the words alone.
        GuidanceAction.ELBOWS_IN -> "Elbows in."
        }

    /**
    * The face a tip is meant to bring to the camera, or null if the action is
    * not a tip.
    *
    * One definition, used by everything that needs to know what was asked for:
    * the wording spoken to the user, the verification call, the wrong-face
    * check, and the repeat detector in [InspectionMemory]. It was previously
    * written out separately in each, which is how the spoken form came to
    * disagree with the rest.
    *
    * Values are [Perception.surface_in_view] vocabulary, so they can be compared
    * with what perception reports without translation.
    */
    fun targetSurface(action: GuidanceAction): String? = when (action) {
        // Named for where the face now at the phone travels: tipping the package
        // backwards sends its front upwards and brings the base round.
        GuidanceAction.TURN_UP -> "base"
        GuidanceAction.TURN_DOWN -> "top"
        else -> null
        }

    /**
    * The face that arrives when a tip is carried out backwards, or null when the
    * action is not a tip.
    *
    * This is the one mistake worth detecting on its own. Any other surface may
    * mean a partial turn, a package that rolled, or perception being unsure; the
    * exact opposite face means the movement happened and went the wrong way,
    * which is a fact about the instruction rather than about the user.
    */
    fun invertedSurface(action: GuidanceAction): String? = when (action) {
        GuidanceAction.TURN_UP -> "top"
        GuidanceAction.TURN_DOWN -> "base"
        else -> null
        }

    /**
    * True when the face that arrived is the exact opposite of the one the
    * outstanding instruction asked for.
    *
    * Deliberately narrow. "side" or "unclear" coming back from a tip can mean a
    * half-finished turn, a package that rolled in the hand, or perception being
    * unsure, and the verification call is the right judge of those. The opposite
    * face is not ambiguous: the movement was made, and it was made the other way.
    *
    * [asked] is null whenever nothing with a named face is outstanding - the
    * opening instruction, a recovery prompt, a sentence the planner wrote - and
    * then nothing can be the wrong face.
    */
    fun tipWentTheWrongWay(asked: GuidanceAction?, surfaceInView: String): Boolean {
        val inverted=asked?.let { invertedSurface(it) } ?: return false

        return surfaceInView.trim().lowercase()==inverted
        }

    /**
    * What the capture gate should wait for after each action.
    *
    * Eleven of the twelve ask for a movement. ELBOWS_IN is the exception and has
    * to be: it asks the user to change how they hold, not where the package is,
    * so a gate told to wait for movement would wait for one that is never
    * coming and deadlock until the stall deadline - which is the failure the
    * command exists to prevent, arriving by another route.
    *
    * NONE is also reached when the planner is answering, when a grounding guard
    * blocks an answer, and on an explicit retry.
    */
    fun expectedChange(action: GuidanceAction): String = when (action) {
        GuidanceAction.ELBOWS_IN -> ExpectedChange.NONE

        GuidanceAction.MOVE_CLOSER,
        GuidanceAction.MOVE_FARTHER -> ExpectedChange.SCALE

        GuidanceAction.MOVE_LEFT,
        GuidanceAction.MOVE_RIGHT,
        GuidanceAction.MOVE_UP,
        GuidanceAction.MOVE_DOWN -> ExpectedChange.POSITION

        else -> ExpectedChange.VIEWPOINT
        }

    fun parse(value: String?): GuidanceAction? =
    GuidanceAction.entries.firstOrNull { it.name==value?.trim()?.uppercase() }

    val names: Set<String>
    get() = GuidanceAction.entries.map { it.name }.toSet()
    }
