# Date-location retrieval

The planner (POLICY) can be given retrieved cases: where the expiry date was
printed on reference packages of a similar kind. This follows section 4 of the
research proposal. It is the search-memory idea, not `visual_rag.py`'s reading
exemplars. PERCEPTION, VERIFY and the date reader are unchanged.

It is a **structural-retrieval baseline**, not visual-similarity retrieval. Visual
similarity was evaluated offline and not adopted (see
[Appearance matching: evaluated, not adopted](#appearance-matching-evaluated-not-adopted)).

## What it is and is not

- **Cases** are annotated reference products. Each case has a package geometry, a
  target location (BASE / TOP / END / SIDE / FRONT / BACK) and the exact annotated
  surface within it (e.g. `neck` within TOP). Name matching also uses brand-like
  words from the product name.
- **No dates are included.** Date text, normalised date and date type are all left
  out, and a unit test checks the bundled asset for date patterns.
- **Labels are drafts.** Product-answer verification and date-location
  verification are counted separately (see below).
- **These are not transitions.** Static photographs show where dates were found.
  They do not show whether an instruction works or how long it takes.
- **Retrieval is structural, not visual.** It uses the geometry PERCEPTION reports,
  plus brand words from `text_read` when name matching is on. The phone has no
  image-embedding model. Only 14 of the 75 reference cases have any name words, so
  most retrieval is by geometry alone.

## Flow

```
tools/build_retrieval_index.py   annotations + frozen split -> app/src/main/assets/agent/date_location_index.json
DateLocationMemory.retrieve()    geometry (class back-off), brand words, current view, faces looked at
InspectionAgent.plan()           adds "retrieved_cases" and Prompts.RETRIEVAL_GUIDANCE
```

Only planner turns retrieve. LocalGuidance fast-path turns do not.

Retrieval uses the session's settled geometry: the most frequent reading across
turns, with the earliest reading winning a tie. It does not use each frame's own
reading, because one misread frame (a cup read as "tray") would otherwise switch
the reference cases mid-search.

For each target location the planner gets:

- `cases` and `details`: counts, never probabilities or product names/ids.
- `cases_on_faces_already_looked_at`: cases whose annotated surface this session
  has already seen clearly without finding a date. The prompt says to give these
  **less weight, not to rule them out**.
  - A "clear look" requires usable quality, no date region seen, and none of
    glare, blur, too_far, occluded_by_hand or cut_off. A face where a date region
    was ever seen never counts as searched.
  - A look at the top covers lid, cap, foil lid, top labels and top-near-cap. It
    does not cover neck, shoulder or lid rim. A look at the base covers base, base
    label and bottom fold. An end flap needs both ends. Side and label looks cover
    nothing, because they do not say which side.
- `in_view` (yes / possibly / no / unknown) and `reach_with` (approved guidance),
  derived from `surface_in_view`.
  - Perception cannot tell front from back or one side from another. A broad face
    in view therefore makes FRONT and BACK each only "possibly", and an unclear
    view stays "unknown".
  - The planner is told to use the turn history instead of assuming which face the
    camera sees.

### Prompt precedence

The shared POLICY prompt now gives placements as fallback heuristics. It no longer
says turning a jar or can "never reveals the code". The retrieval guidance makes
the order explicit:

1. Observations of this package.
2. Retrieved cases, unless sparse.
3. The common placements.

This change, and `clear_looks_without_date` in the belief, apply to **both**
conditions. Runs from before this change are not comparable with runs after it.

## Appearance matching: evaluated, not adopted

The option tested: embed each frame on the phone, find the reference packages it
looks most like, and give the planner their date locations next to
`name_matched_cases`. It was implemented end to end on 2026-09-15, then removed from
the app. Only the offline estimate remains: `tools/evaluate_appearance_retrieval.py`.

### The estimate

- **Model:** MediaPipe Image Embedder, `mobilenet_v3_large` (float32 v1, pinned by
  URL and SHA-256), on photos shrunk to a 512 px long edge.
- **Reference cases:** built from the annotations exactly as the phone's index is,
  from the reference side of the split.
- **Appearance retrieval:** the 5 most similar-looking cases within the geometry
  class. A reference product scores by its most similar view. "Per product" uses
  the mean similarity over all of the evaluation product's photos, as a session
  would.
- **Score:** whether the most common location among the retrieved cases is the
  annotated one, compared with the same vote over the geometry baseline.

It uses draft labels, the annotated geometry instead of perception's reading, and a
vote instead of a planner. It is an estimate, not the experiment.

Run on 2026-09-15, on 35 evaluation products (805 photos) against 75 reference cases:

| Majority location = annotated location | Geometry | Appearance (large) | Appearance (small) |
| --- | --- | --- | --- |
| Per photo | 0.514 | 0.457 | 0.466 |
| Per product (session mean) | 0.514 | 0.486 | 0.400 |

With the large model, the annotated location was among the appearance matches for
0.66 of products. The small model is `mobilenet_v3_small`, run with `--model`.

### Why it was not adopted

- **No detectable benefit.** Appearance did not beat the geometry majority. With 35
  products, one product is about 3 points, so the fair reading is "no benefit", not
  "worse".
- **The design excludes what the model is good at.** The model recognises products:
  in a probe over all 110 products, a photo's nearest other photo belonged to the
  same product 94.5% of the time (small model). The split keeps each product and its
  same-brand twins out of the reference set, so the only thing left to test is
  whether similar-looking packages share date locations. They did not, measurably.
- **Cost.** The debug APK went from 22.6 MB to 84.8 MB (the model, the vectors, and
  MediaPipe's native library for four ABIs). The prebuilt native library may also
  conflict with F-Droid. Each location-index rebuild would also have needed a
  matching visual-index rebuild.
- **Statistical power.** One or two more retrieval conditions would have split the
  trials of the planned comparison further.

### When to reconsider

- **Known products:** if deployment should recognise products already in the
  reference set, as shops repeat products. That is instance recognition, a
  different research question.
- **Unknown geometry:** if perception often fails to report a geometry. The estimate
  above used annotated geometry, so it does not cover this case.

```
pip install -r tools/requirements-visual.txt
python tools/evaluate_appearance_retrieval.py --split tools/retrieval_split.csv [--model path/to/other.tflite]
```

## Perception accuracy

Retrieval is keyed on what PERCEPTION reports, so its readings were measured
directly with `tools/perception_accuracy.py`.

- **Setup:** the app's PERCEPTION prompt verbatim, `gemini-3.5-flash`, photos
  reduced to a 1152 px long edge as the phone does.
- **Photos:** 105 dataset photos, the `ctr` (centred on the date), `top` and `bot`
  views of the 35 evaluation products. These are not CameraX stills.
- **Latency:** p50 5.1 s, p90 8.3 s.

Run on 2026-09-15:

| Reading | Result |
| --- | --- |
| Geometry per photo, exact (same class) | ctr 77% (86%), top 69% (86%), bot 69% (91%) |
| Settled geometry per product, exact (same class) | 26/35 = 74% (32/35 = 91%) |
| `surface_in_view` on `top`/`bot`, round packages | 28/30 |
| `surface_in_view` on `top`/`bot`, boxes | 6/20 (12 read `side`, 3 top/base swapped) |
| `surface_in_view` on `top`/`bot`, bags and trays | 1/20 (18 read `side`) |
| Date region visible / legible on `ctr` | 35/35 / 35/35 |
| `iso_date` on `ctr` | 28/33 correct, 5 wrong |

### Geometry

- **Cups are misread.** Tubs and plastic containers read as `jar` 8 times, and
  as `cup` only 3 times. Retrieval stays in the right class (`rigid_round`).
- **Class errors:** 5 carton photos read as `bag`. That is a class error and gives
  the planner the wrong reference cases.

### Faces

- **The ends of a box or bag look like a narrow side.** Perception mostly calls
  them `side`, and its vocabulary has no word for an end.
- **Consequences in the agent:**
  - `coveredBy` needs both top and base seen before an END case counts as looked
    at, so on these packages it almost never counts.
  - `reach` treats a `side` view as not showing END.
  - TURN_DOWN / TURN_UP tips on boxes may never register as reached.
- This should be settled before the planned comparison. Changing the prompt or
  the surface logic changes every condition.

### Dates

Of the 5 disagreements with the annotation, 4 are perception misreads of clearly
printed dates, and all 4 put the year 2 years early:

| Product | Printed | Read |
| --- | --- | --- |
| P17 | 12/05/28 | 12/05/26 |
| P33 | 02/2028 | 02/2026 |
| P90 | 17/10/27 | 17/10/25 |
| P101 | 30/11/26 | 30/11/24 |

- **The grounding guard cannot catch this.** It checks the planner against
  perception, and here perception itself is wrong.
- **P35 is probably an annotation error.** The sticker in `P35_ctr.jpg` appears to
  read 28/12/26, which is what perception read. The annotation says 28/11/26.
- **P21:** its `normalized_date` is `--09-24` (no year), so it was not scored.

**Resolution check (2026-09-15, 8 calls).** The 4 misread `ctr` photos were sent
again: once as the original file (3000-4160 px, 4-6 MB) and once more at 1152 px.

| Product | Printed | First run, 1152 px | Repeat, 1152 px | Full resolution |
| --- | --- | --- | --- | --- |
| P17 | 12/05/28 | 12/05/26 | 12/05/28 | 12/05/28 |
| P33 | 02/2028 | 02/2026 | 02/2026 | 02/2026 |
| P90 | 17/10/27 | 17/10/25 | 17/10/27 | 17/10/27 |
| P101 | 30/11/26 | 30/11/24 | 30/11/24 | 30/11/26 |

- **Readings are unstable.** P17 and P90 read correctly on a plain repeat at the
  same size, so much of the error is call-to-call variation, not resolution.
- **Resolution may help some prints:** P101 was wrong twice at 1152 px and right
  once at full size.
- **P33 is misread every time.** A repeat would agree on the wrong year.
- **Full resolution does not solve it.** It still got 1 of 4 wrong, and it cost
  10-14 s per call against 4-11 s, with a multi-megabyte upload.
- **Selection bias:** these photos were picked because they failed. Nothing here
  is an error rate.

What this means for the guard, taking each first misreading and requiring a
second reading to agree:

- **Second reading at 1152 px:** flags 2 of 4 (P17, P90). It misses P101 and P33,
  which repeated the same wrong year.
- **Second reading at full size:** flags 3 of 4. It misses P33.

A second reading from a different photo is what test 2 has to measure.

```
python tools/perception_accuracy.py run      # calls the model; resumable
python tools/perception_accuracy.py report   # rescores saved replies
```

## Experimental conditions

Scored by `tools/ablation_report.py --task`, which groups sessions by the
condition below and compares the answering turn's `iso_date` with the annotated
date, to the precision the annotation asserts. A condition absent from the log
is not the "No retrieval" condition and is never pooled with it.

Two settings, read once at session start:

| Condition | "Plan the search using where similar packages print their dates" | "Also match brand names printed on the package" |
| --- | --- | --- |
| No retrieval | off | ignored |
| Geometry counts | on | off |
| Geometry + names | on | on |

- **No retrieval** sends the shared POLICY prompt with no retrieval guidance and no
  `retrieved_cases`.
- **Both retrieval conditions** use the same prompt and differ only in the payload.

`events.jsonl` records the run:

- `session_start.retrieval`: `enabled`, `active`, `name_matching`, `error`,
  `index_sha256`, `split_sha256`, `cases`, `label_status`, `excluded_products`
- `observation.retrieval`: case ids, targets, faces looked at, name-matched ids and
  words

## The split and the contamination check

The bundled index is built from the **reference side** of `tools/retrieval_split.csv`:
75 reference products and 35 evaluation products.

- **Deterministic:** seed 2027, evaluation fraction 0.3.
- **Stratified by geometry.**
- **Product groups are never divided.** Groups are identical names (e.g. the two
  "Kellogg cereal" records) or a shared brand word.

```
python tools/build_retrieval_index.py split [--evaluation-fraction 0.3 --seed 2027]   # refuses to overwrite without --force
python tools/build_retrieval_index.py build --split tools/retrieval_split.csv
python tools/build_retrieval_index.py check --split tools/retrieval_split.csv          # exit 1 on any overlap
```

`build` refuses a split that:

- is missing or duplicates a product,
- names an unknown product,
- divides a product group, or
- has `legacy_product_id` values that disagree with the current annotations, i.e.
  was written before a renumbering.

The unit test `theBundledIndexHoldsOutItsEvaluationProducts` fails an APK built
from an unsplit index.

**Run `check` before every evaluation run.** It verifies the index against the
split. `run_trials.py` does not read this index today; if it ever does, call
`check` from it.

Since 18 September a second, complementary check is possible *after* a run. The
phone still does not know which product is in the user's hand, but the operator
does and now records it: `session_start.run_label` names the product and
`session_start.retrieval.excluded_products` names what the bundled index held
out. `python tools/ablation_report.py events.jsonl --task` refuses to count any
session whose product was inside the reference index it retrieved from, and
prints it. `check` catches a bad index; this catches a session run on the wrong
product with a good one.

## Verification

- The review page marks products `HUMAN_VERIFIED`, and the builder counts that
  value.
- Verifying a product there checks its date, evidence view and type, but **not
  where the date is**.
- `--verified-only` therefore also requires `location_review_status ==
  HUMAN_VERIFIED`. The review page does not record that field.
- On 2026-09-15 the reviewer stated that all 110 products were verified, including
  where the date is printed. Both statuses were written into
  `annotations.draft.json` and `products.draft.csv` from that statement, not
  clicked in the review page. Each product carries a `verification_note` saying
  so. The previous files are in `annotations/verification_backups/20260915T160232Z/`.
  Per-image statuses were not changed.
- The bundled index was then rebuilt with `--verified-only`: 75 of 75 answers and
  locations are verified. The cases are identical to the draft build, but the
  index hash changed. Sessions logged before and after the rebuild therefore show
  different `index_sha256` values for the same reference set.

## Grounding guard

`answerIsGrounded()` now checks the date that would actually be spoken:

- **If the planner gives an ISO date:** the ISO date from this frame must match it.
- **Otherwise:** `date_string` is spoken, so its digits must match the digits this
  frame read.
- **Printed strings present on both sides:** they must not contradict each other.

Previously a planner that omitted `iso_date` could have any `date_string` spoken.
The stricter rule can block an answer whose perception gave only a printed string
while the planner added an ISO date. That case asks the user to hold still and
try again.

## Tests

```
python -m unittest tools/test_build_retrieval_index.py tools/test_evaluate_appearance_retrieval.py -v
gradlew.bat :app:testDebugUnitTest --tests "com.expagent.agent.DateLocationMemoryTest" --tests "com.expagent.agent.InspectionGroundingTest"
```

None of this has been run on a phone. Whether retrieval shortens searches is an
empirical question for the planned comparison.
