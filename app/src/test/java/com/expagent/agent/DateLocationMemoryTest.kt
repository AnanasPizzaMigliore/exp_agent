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

import java.io.File

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
* Retrieval of date locations from reference packages.
*
* The properties that matter are the refusals and the uncertainty: an unknown
* package gets nothing presented as retrieval, a thin match says it is thin, a
* clear look lowers a location's weight without ruling it out, a view that cannot
* tell front from back does not pretend to, and nothing a case carries can end
* up as a date the planner repeats.
*/
class DateLocationMemoryTest {

    private fun case(id: String, geometry: String, geometryClass: String, location: String, surface: String, vararg tokens: String) =
    DateLocationCase(id, geometry, geometryClass, location, surface, tokens.toList())

    private val memory=DateLocationMemory(DateLocationIndex(
        schema=DateLocationMemory.SCHEMA,
        geometry_classes=mapOf(
            "carton" to "box",
            "multipack" to "box",
            "jar" to "rigid_round",
            "can" to "rigid_round",
            "bottle" to "rigid_round",
            "bag" to "flexible",
            ),
        cases=listOf(
            case("P01", "carton", "box", "END", "end_flap", "kellogg"),
            case("P02", "carton", "box", "END", "end_flap"),
            case("P03", "carton", "box", "SIDE", "narrow_side"),
            case("P04", "carton", "box", "SIDE", "narrow_side"),
            case("P05", "carton", "box", "BASE", "base"),
            case("P06", "carton", "box", "BACK", "back_label"),
            case("P07", "multipack", "box", "TOP", "top_labels"),
            case("P08", "jar", "rigid_round", "TOP", "lid"),
            case("P09", "bottle", "rigid_round", "TOP", "neck"),
            case("P10", "can", "rigid_round", "BASE", "base"),
            case("P11", "bag", "flexible", "BACK", "back_label", "kellogg"),
            ),
        ), "test")

    private fun target(result: LocationRetrieval, location: String) =
    result.targets.first { it.location==location }

    @Test
    fun enoughSameGeometryCasesAreUsedDirectly() {
        val result=memory.retrieve(RetrievalQuery("carton", null))!!

        assertEquals("SAME_GEOMETRY", result.match)
        assertEquals(6, result.cases.size)
        assertFalse(result.sparse)
        assertEquals(listOf("END", "SIDE", "BACK", "BASE"), result.targets.map { it.location })
        assertEquals(mapOf("end_flap" to 2), target(result, "END").details)
        }

    @Test
    fun aThinGeometryBacksOffToItsClassAndSaysSo() {
        val result=memory.retrieve(RetrievalQuery("jar", null))!!

        assertEquals("SIMILAR_GEOMETRY", result.match)
        assertEquals(setOf("P08", "P09", "P10"), result.cases.map { it.product_id }.toSet())
        assertTrue(result.sparse)
        }

    @Test
    fun anUnknownPackageRetrievesNothing() {
        assertNull(memory.retrieve(RetrievalQuery("unknown", "some printed words")))
        assertNull(memory.retrieve(RetrievalQuery("", null)))
        }

    @Test
    fun brandWordsMatchWithinTheGeometryClassOnly() {
        // The Kellogg bag must not steer the search on a Kellogg box.
        val result=memory.retrieve(RetrievalQuery("carton", "KELLOGG'S Corn Flakes"))!!

        assertEquals(listOf("P01"), result.nameMatches.map { it.product_id })
        assertEquals(setOf("kellogg"), result.matchedWords)
        }

    @Test
    fun brandWordsAloneStillRetrieveWhenGeometryIsUnknown() {
        val result=memory.retrieve(RetrievalQuery("unknown", "Kellogg's"))!!

        assertEquals("NAME_ONLY", result.match)
        assertTrue(result.cases.isEmpty())
        assertEquals(setOf("P01", "P11"), result.nameMatches.map { it.product_id }.toSet())
        }

    @Test
    fun theGeometryOnlyConditionIgnoresPrintedWords() {
        val result=memory.retrieve(RetrievalQuery("carton", "Kellogg's"), matchNames=false)!!

        assertTrue(result.nameMatches.isEmpty())
        assertNull(memory.retrieve(RetrievalQuery("unknown", "Kellogg's"), matchNames=false))
        }

    @Test
    fun accentsAndCaseDoNotPreventAMatch() {
        assertEquals(setOf("colacao", "cafe"), DateLocationMemory.tokens("ColaCáo CAFÉ"))
        }

    // ------------------------------------------------------------------
    // Faces already looked at lower weight; they do not rule anything out.
    // ------------------------------------------------------------------

    @Test
    fun aClearLookLowersWeightButKeepsTheLocation() {
        val result=memory.retrieve(RetrievalQuery("carton", null, "base", listOf("base")))!!
        val base=target(result, "BASE")

        assertEquals(1, base.cases)
        assertEquals(1, base.onFacesLookedAt)
        }

    @Test
    fun aLookAtTheTopDoesNotCoverTheNeck() {
        val neck=case("X", "bottle", "rigid_round", "TOP", "neck")
        val lid=case("Y", "jar", "rigid_round", "TOP", "lid")

        assertFalse(DateLocationMemory.coveredBy(neck, setOf("top")))
        assertTrue(DateLocationMemory.coveredBy(lid, setOf("top")))
        }

    @Test
    fun anEndFlapIsCoveredOnlyOnceBothEndsHaveBeenSeen() {
        val flap=case("X", "carton", "box", "END", "end_flap")

        assertFalse(DateLocationMemory.coveredBy(flap, setOf("top")))
        assertTrue(DateLocationMemory.coveredBy(flap, setOf("top", "base")))
        }

    @Test
    fun sidesAndLabelsCoverNothing() {
        val side=case("X", "carton", "box", "SIDE", "narrow_side")
        val back=case("Y", "carton", "box", "BACK", "back_label")

        assertFalse(DateLocationMemory.coveredBy(side, setOf("side", "label")))
        assertFalse(DateLocationMemory.coveredBy(back, setOf("side", "label")))
        }

    // ------------------------------------------------------------------
    // Reachability depends on the view, and says so when the view is ambiguous.
    // ------------------------------------------------------------------

    @Test
    fun aBroadFaceInViewIsOnlyPossiblyTheRightOne() {
        // "label" cannot tell front from back: improving this view may be
        // looking harder at the wrong face.
        val (inView, actions)=DateLocationMemory.reach("BACK", "box", "label")

        assertEquals("possibly", inView)
        assertEquals(listOf("TURN_BACK"), actions)
        assertEquals("possibly", DateLocationMemory.reach("FRONT", "box", "label").first)
        }

    @Test
    fun anUnclearViewKeepsItsUncertainty() {
        assertEquals("unknown", DateLocationMemory.reach("BACK", "box", "unclear").first)
        assertEquals("unknown", DateLocationMemory.reach("BASE", "rigid_round", "unclear").first)
        }

    @Test
    fun onlyAnUnambiguousFaceIsReportedAsInView() {
        assertEquals(Pair("yes", listOf<String>()), DateLocationMemory.reach("BASE", "rigid_round", "base"))
        assertEquals(Pair("no", listOf("TURN_UP")), DateLocationMemory.reach("BASE", "rigid_round", "label"))
        assertEquals(Pair("possibly", listOf("TURN_DOWN")), DateLocationMemory.reach("END", "box", "base"))
        }

    @Test
    fun aBoxOrBagSeenSideOnSaysNothingAboutItsEnds() {
        // Measured 2026-09-17: ends of boxes were named in eight of 20
        // photographs and of bags in two of 20, the rest almost all "side". So
        // on a non-round package a side reading is uninformative about the ends
        // rather than evidence they are turned away, and the belief must say
        // "unknown" instead of asserting what perception did not establish.
        for (surface in listOf("side", "label")) {
            assertEquals("unknown", DateLocationMemory.reach("BASE", "box", surface).first)
            assertEquals("unknown", DateLocationMemory.reach("TOP", "box", surface).first)
            assertEquals("unknown", DateLocationMemory.reach("END", "flexible", surface).first)
            }

        // The movement that would reach the face is still offered; only the
        // claim about what is in view now is withdrawn.
        assertEquals(listOf("TURN_UP"), DateLocationMemory.reach("BASE", "box", "side").second)

        // A round package is unaffected: its ends are circles and were named in
        // 27 of 30 photographs, so a side reading there does mean the ends are
        // turned away.
        assertEquals("no", DateLocationMemory.reach("BASE", "rigid_round", "label").first)

        // And a face actually reported is still reported, on any geometry.
        assertEquals("yes", DateLocationMemory.reach("BASE", "box", "base").first)
        assertEquals("possibly", DateLocationMemory.reach("END", "box", "base").first)
        }

    @Test
    fun aRoundPackagesSideAndBackAreReachedByTurning() {
        assertEquals(Pair("possibly", listOf("TURN_LEFT", "TURN_RIGHT")), DateLocationMemory.reach("BACK", "rigid_round", "label"))
        assertEquals(Pair("possibly", listOf("TURN_LEFT", "TURN_RIGHT")), DateLocationMemory.reach("SIDE", "rigid_round", "side"))
        }

    @Test
    fun thePlannerIsNotToldWhichProductsTheCasesWere() {
        val result=memory.retrieve(RetrievalQuery("carton", "Kellogg", "label"))!!
        val policy=result.toPolicyJson().toString()
        val log=result.toLogJson().toString()

        assertFalse(policy.contains("P01"))
        assertTrue(policy.contains("name_matched_cases"))
        assertTrue(policy.contains("\"in_view\""))
        assertTrue(log.contains("P01"))
        }

    @Test
    fun anUnexpectedSchemaIsRefused() {
        val refused=try {
            DateLocationMemory.parse("""{"schema":"something-else","cases":[]}""")
            false
            }
        catch (e: IllegalArgumentException) {
            true
            }

        assertTrue(refused)
        }

    // ------------------------------------------------------------------
    // Prompts.
    // ------------------------------------------------------------------

    @Test
    fun theRetrievalPromptOnlyAddsToTheSharedPrompt() {
        assertFalse(Prompts.POLICY.contains("retrieved_cases"))
        assertTrue(Prompts.POLICY_WITH_RETRIEVAL.startsWith(Prompts.POLICY.substringBefore("Reply as JSON only:")))
        assertTrue(Prompts.POLICY_WITH_RETRIEVAL.contains(Prompts.RETRIEVAL_GUIDANCE))

        // The reply format must still be the last thing the planner reads.
        assertTrue(Prompts.POLICY_WITH_RETRIEVAL.indexOf(Prompts.RETRIEVAL_GUIDANCE)<Prompts.POLICY_WITH_RETRIEVAL.indexOf("Reply as JSON only:"))
        }

    @Test
    fun theSharedPromptMakesNoAbsolutePlacementClaim() {
        // Reference jars have dates on their sides; a prompt saying turning
        // "never reveals the code" would contradict every such retrieved case.
        assertFalse(Prompts.POLICY.contains("never reveals"))
        assertFalse(Prompts.POLICY.contains("do not waste turns rotating"))
        }

    // ------------------------------------------------------------------
    // The asset actually shipped in the APK.
    // ------------------------------------------------------------------

    /** Fails rather than skips: a build without the index silently has no retrieval. */
    private fun bundledIndex(): String {
        val file=File("src/main/assets/${DateLocationMemory.ASSET_PATH}")
        assertTrue("missing ${file.absolutePath}; run tools/build_retrieval_index.py", file.exists())
        return file.readText()
        }

    @Test
    fun theBundledIndexParsesAndCoversEveryPerceptionGeometry() {
        val bundled=DateLocationMemory.parse(bundledIndex())

        assertTrue(bundled.size>0)

        for (geometry in listOf("carton", "jar", "can", "bottle", "bag", "cup", "tray", "multipack"))
        assertNotNull("no class for $geometry", bundled.index.geometry_classes[geometry])

        val locations=setOf("BASE", "TOP", "END", "SIDE", "BACK", "FRONT")
        for (case in bundled.index.cases) {
            assertTrue("${case.product_id} has location ${case.location}", case.location in locations)
            assertTrue("${case.product_id} has no annotated surface", case.annotated_surface.isNotBlank())
            }
        }

    @Test
    fun theBundledIndexHoldsOutItsEvaluationProducts() {
        val bundled=DateLocationMemory.parse(bundledIndex())
        val evaluation=bundled.index.split?.evaluation_products ?: listOf()

        assertTrue("the bundled index was built without a split", evaluation.isNotEmpty())
        assertTrue(bundled.index.cases.none { it.product_id in evaluation })
        assertTrue(bundled.index.excluded_products.containsAll(evaluation))
        }

    @Test
    fun theBundledIndexCarriesNoDates() {
        val cases=Json.parseToJsonElement(bundledIndex()).jsonObject["cases"].toString()

        assertFalse(Regex("""\d{1,2}\s*[/.\-]\s*\d{1,2}\s*[/.\-]\s*\d{2,4}""").containsMatchIn(cases))
        assertFalse(Regex("""20\d\d-\d\d""").containsMatchIn(cases))
        assertFalse(cases.contains("date_text"))
        assertFalse(cases.contains("normalized_date"))
        }
    }
