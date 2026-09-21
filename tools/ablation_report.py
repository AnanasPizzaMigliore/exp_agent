#!/usr/bin/env python3
"""Score a device run: the live capture controller, and the shadow arms beside it.

    adb pull /sdcard/Android/data/com.rastislavkish.vscan/files/runs/events.jsonl
    python tools/ablation_report.py events.jsonl --validate
    python tools/ablation_report.py events.jsonl --by-gate
    python tools/ablation_report.py events.jsonl --task
    python tools/ablation_report.py events.jsonl --arms

Four reports, because there are four different questions and they do not have
the same standing:

  --validate  integrity checks on the log itself. Run this first and every time.
              A report drawn from a log that fails these is not worth reading.
  --by-gate   the controlled controller comparison: real sessions grouped by the
              controller that actually drove the camera, scored against the
              scripted block the person was performing.
  --task      the live task comparison: whole sessions on real products grouped
              by retrieval condition, scored against the annotated date. Needs
              --annotations and a run_label naming the product.
  --arms      the shadow arms. Paired by construction, counterfactual after the
              live capture in each turn. Supporting evidence, not the result.

A log may hold both kinds of session; the two reports select what each needs and
leave the rest out, so an interleaved collection round is scored in one pass.

WHAT THE SHADOW NUMBERS ARE. Every arm saw the identical measurements at the
identical instants, so the comparison is paired by construction. But only the
live controller acted: an arm that would have captured earlier would have changed
the photograph, the answer, the next instruction and the user's next movement.
Its decisions are therefore real up to the live capture within each instruction,
and counterfactual after it. The table separates the two and you should quote the
first. Opportunity recall is NOT measurable here at all - it needs annotated
eligible windows and offline replay.

WHAT CHANGED ON 18 SEPTEMBER 2026, and why older logs are not comparable:

  * The divergence reference is now the live controller's own accepted
    reservations, not the E_full shadow arm. Those are different things: on the
    device the shadow arm fires about 6 ms earlier and, in a third of sessions,
    one to three times more often, because it accepts itself immediately where a
    real reservation can be refused or rejected at the camera callback.
  * `live_arm` in ablation_arms was the literal "E_full" in every session ever
    logged. session_start.gate is authoritative and is what this reads.
  * The `idle` verdict no longer requires the live gate to be in a particular
    phase. That conjunct made it fire once in 603 shadow captures.
  * The quality baseline's duplicate test was repaired in the app. Q numbers
    from before that change are not comparable with ones after it.
"""

import argparse
import json
import statistics
import sys
from collections import Counter, defaultdict

#: Blocks the scorer knows how to give a verdict on. Anything else is counted
#: and left unscored, which is the right answer for a session nobody scripted.
BLOCKS = ("A_idle", "B_pause", "C_normal")

#: The only capture reason that is supposed to wait for the person to move.
#: An opening look and an explicit hold-still retry are not, so an unchanged
#: view under those reasons is not evidence of anything.
NEEDS_MOVEMENT = "post_action_change"

#: Where the dataset's annotated answers live. The same default the retrieval
#: index builder uses, so the two are scored against one file.
DEFAULT_ANNOTATIONS = r"D:\agent images\dataset\annotations\annotations.draft.json"


def load(path):
    rows = []
    with open(path, encoding="utf-8") as handle:
        for number, line in enumerate(handle, 1):
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                print(f"{path}:{number}: unreadable line, skipped", file=sys.stderr)
    return rows


def block_of(start):
    """The scripted behaviour, from the field or from a legacy combined label.

    Sessions logged before 18 September carry one `run_label` holding both the
    block and the product, written that way by hand because there was only one
    field ("A_idle_can"). Those are still scoreable, so the prefix is accepted.
    """
    explicit = start.get("block_label")
    if explicit:
        return explicit, "block_label"

    label = start.get("run_label") or ""
    for block in BLOCKS:
        if label == block or label.startswith(block + "_"):
            return block, "run_label prefix"
    return None, None


class Session:
    """One session's rows, sorted into the handful of things a report asks for."""

    def __init__(self, session_id, rows):
        self.id = session_id
        self.rows = rows
        self.start = next((r for r in rows if r.get("kind") == "session_start"), {})
        self.arms_row = next((r for r in rows if r.get("kind") == "ablation_arms"), None)

        self.block, self.block_source = block_of(self.start)
        self.run_label = self.start.get("run_label")

        # session_start is written by the code that selects the controller;
        # ablation_arms was a hardcoded literal until 18 September. Prefer the
        # first, fall back to the second only when the run predates the field.
        self.gate = self.start.get("gate")
        self.logged_arm = (self.arms_row or {}).get("live_arm")
        self.live_arm = self.gate or self.logged_arm or "E_full"

        self.arms = [a for a in ((self.arms_row or {}).get("arms") or "").split(",") if a]

        self.observations = self.of("observation")
        self.shadow = self.of("ablation_capture")
        self.freshness = self.of("ablation_freshness")
        self.stale = self.of("stale_response")
        self.rejected = self.of("capture_rejected")
        self.onsets = self.of("event_onset")
        self.finish = next((r for r in rows if r.get("kind") == "session_finish"), None)

        accepted = {r.get("capture_token") for r in self.of("capture_accepted")}
        # A reservation that was never accepted did not become a photograph, so
        # it is not a capture. Reserving is the instant the controller decided;
        # accepting is ~800 ms later, when CameraX hands the image back.
        self.live_captures = [r for r in self.of("capture_reserved")
                              if r.get("capture_token") in accepted]
        self.automatic = [r for r in self.live_captures if r.get("automatic")]

    def of(self, kind):
        return [r for r in self.rows if r.get("kind") == kind]

    @property
    def started(self):
        return self.start.get("ts") or (self.rows[0].get("ts") if self.rows else 0)

    @property
    def seconds(self):
        if not self.rows:
            return 0.0
        return max(r.get("ts", 0) for r in self.rows) - self.started

    @property
    def answered(self):
        return bool(self.finish and self.finish.get("date"))

    @property
    def retrieval(self):
        return self.start.get("retrieval") or {}

    @property
    def condition(self):
        """The retrieval condition this session ran under, named as in RETRIEVAL.md.

        None when the log predates the field, which is not the same as "No
        retrieval" and must not be pooled with it.
        """
        block = self.retrieval
        if not block:
            return None
        if not block.get("enabled"):
            return "No retrieval"
        return "Geometry + names" if block.get("name_matching") else "Geometry counts"

    @property
    def answer(self):
        """The turn that ended the session with a date, or None.

        Read from the observation rather than session_finish because only the
        observation carries the ISO date; session_finish holds the string that
        was spoken, which is whatever the package printed.
        """
        for row in self.observations:
            if row.get("finished") and (row.get("policy") or {}).get("action") == "ANSWER":
                return row
        return None

    def reason_for(self, instruction_id):
        """The reason the arms were armed under for one instruction."""
        for row in self.live_captures:
            if row.get("instruction_id") == instruction_id:
                return row.get("reason")
        return None


def sessions_of(rows):
    grouped = defaultdict(list)
    for row in rows:
        grouped[row.get("session_id")].append(row)
    found = [Session(sid, group) for sid, group in grouped.items() if sid]
    return sorted(found, key=lambda s: s.started)


# --------------------------------------------------------------------------
# validate
# --------------------------------------------------------------------------

def validate(found):
    """Integrity checks. Returns the number of problems worth acting on."""
    problems = 0
    notes = 0

    print("=== log integrity ===\n")

    mismatched = [s for s in found
                  if s.gate and s.logged_arm and s.gate != s.logged_arm]
    if mismatched:
        notes += len(mismatched)
        print(f"  {len(mismatched)} session(s) where ablation_arms.live_arm "
              f"disagrees with session_start.gate.")
        for s in mismatched:
            print(f"    {s.id[:8]}  gate={s.gate!r}  live_arm={s.logged_arm!r}")
        print("  Known defect, fixed 18 September: live_arm was a hardcoded literal.")
        print("  session_start.gate is correct; these sessions remain scoreable.\n")

    unlabelled = [s for s in found if s.arms and not s.block]
    if unlabelled:
        notes += len(unlabelled)
        print(f"  {len(unlabelled)} session(s) with arms but no scriptable block label.")
        print("  Counted, not scored - nobody scripted what the person did.")
        for s in unlabelled[:6]:
            print(f"    {s.id[:8]}  run_label={s.run_label!r}")
        if len(unlabelled) > 6:
            print(f"    ... and {len(unlabelled)-6} more")
        print()

    # The load-bearing check. Where E_full drove the session, its shadow twin
    # should reproduce it; if it does not, no other arm's number means anything.
    counts = []
    deltas = []
    for s in found:
        if s.live_arm != "E_full" or not s.arms:
            continue
        shadow = [r for r in s.shadow
                  if r["arm"] == "E_full" and not r.get("after_live_capture")]
        live = {r["instruction_id"]: r["ts"] for r in s.automatic}
        counts.append((s, len(live), len(shadow)))
        for row in shadow:
            reference = live.get(row.get("instruction_id"))
            if reference is not None:
                deltas.append((row["ts"] - reference) * 1000)

    if counts:
        disagreeing = [c for c in counts if c[1] != c[2]]
        print(f"  E_full shadow vs live E_full, over {len(counts)} session(s):")
        if deltas:
            print(f"    timing   n={len(deltas)}  median {statistics.median(deltas):+.0f} ms  "
                  f"range {min(deltas):+.0f}..{max(deltas):+.0f} ms")
        print(f"    counts   {len(disagreeing)} of {len(counts)} session(s) disagree")
        for s, live_n, shadow_n in disagreeing:
            print(f"      {s.id[:8]}  live={live_n}  shadow={shadow_n}")
        if disagreeing:
            notes += 1
            print("    Expected: the shadow arm accepts itself immediately, where a real")
            print("    reservation can be refused or rejected at the camera callback.")
            print("    It is why the divergence reference is the live rows, not this arm.")
        print()

    missing_reason = sum(1 for s in found for r in s.shadow if "capture_reason" not in r)
    if missing_reason:
        notes += 1
        print(f"  {missing_reason} shadow capture row(s) predate capture_reason.")
        print("  Their reason is recovered by joining on instruction_id, which is")
        print("  exact when the instruction produced a live capture and absent otherwise.\n")

    stored = [s for s in found if s.start.get("store_images")]
    print(f"  {len(stored)} of {len(found)} session(s) kept photographs.\n")

    if not notes:
        print("  nothing to report.\n")

    return problems


# --------------------------------------------------------------------------
# by-gate: the controlled controller comparison
# --------------------------------------------------------------------------

def score_session(session):
    """Verdicts for one session, against the block the person was performing.

    These are operational proxies read off the log, not a human's judgement of
    the video. Each is named after exactly what it counts:

      spurious   an automatic capture under a reason that was supposed to wait
                 for movement, taken while the view still matched the reference
                 the instruction was given about. In A_idle, where the person is
                 scripted to do nothing, every such capture is one.
      stale      a reply the controller threw away because the view had moved
                 on. In B_pause this is the signature of capturing into the
                 middle of an action rather than at the end of it.
      repeats    more than one accepted automatic capture for one instruction.
      answered   the session ended with a date.
    """
    spurious = 0
    for row in session.automatic:
        if row.get("reason") != NEEDS_MOVEMENT:
            continue
        # The controller's own view relation at the capture instant.
        at_capture = [r for r in session.of("stability_at_capture")]
        # stability_at_capture is keyed by frame, not token; the nearest one at
        # or after the reservation is this capture's.
        nearest = None
        for r in at_capture:
            if r["ts"] >= row["ts"] and (nearest is None or r["ts"] < nearest["ts"]):
                nearest = r
        if nearest and nearest.get("instruction_relation") == "same":
            spurious += 1

    per_instruction = Counter(r.get("instruction_id") for r in session.automatic)
    repeats = sum(n - 1 for n in per_instruction.values() if n > 1)

    return {
        "captures": len(session.automatic),
        "manual": len(session.live_captures) - len(session.automatic),
        "spurious": spurious,
        "stale": len(session.stale),
        "rejected": len(session.rejected),
        "repeats": repeats,
        "turns": len(session.observations),
        "answered": session.answered,
        "seconds": session.seconds,
    }


def by_gate(found):
    scoreable = [s for s in found if s.block]
    if not scoreable:
        print("No session carries a scriptable block label, so nothing can be scored.")
        print("Set one on the settings screen, or in run_config.json:")
        print('    {"label": "P25", "block_label": "C_normal"}')
        return 1

    print("=== live controller, by condition and block ===\n")
    print("Real sessions, scored on the controller that actually drove the camera.\n")

    grouped = defaultdict(list)
    for s in scoreable:
        grouped[(s.block, s.live_arm)].append(s)

    header = (f"  {'block':<10} {'gate':<20} {'n':>3} {'caps':>5} {'spur':>5} "
              f"{'stale':>6} {'rept':>5} {'rej':>4} {'turns':>6} {'done':>5} {'secs':>6}")
    for block in BLOCKS + ("(other)",):
        keys = [k for k in grouped if k[0] == block] if block != "(other)" \
            else [k for k in grouped if k[0] not in BLOCKS]
        if not keys:
            continue
        print(header)
        for key in sorted(keys, key=lambda k: k[1]):
            group = grouped[key]
            scores = [score_session(s) for s in group]
            n = len(scores)
            total = lambda field: sum(x[field] for x in scores)
            print(f"  {key[0]:<10} {key[1]:<20} {n:>3} "
                  f"{total('captures'):>5} {total('spurious'):>5} {total('stale'):>6} "
                  f"{total('repeats'):>5} {total('rejected'):>4} {total('turns'):>6} "
                  f"{sum(1 for x in scores if x['answered']):>5} "
                  f"{statistics.median([x['seconds'] for x in scores]):>6.0f}")
        print()

    print("  caps  accepted automatic captures      spur  taken with the view unmoved")
    print("  stale replies discarded as out of date rept  second capture for one instruction")
    print("  rej   captures refused at the callback turns model turns completed")
    print("  done  sessions that ended with a date  secs  median session length")
    print()
    print("  In A_idle the person is scripted to do nothing: caps above the opening")
    print("  look, and every spur, is the controller acting on its own.")
    print("  In B_pause they stop mid-action: stale is the signature of capturing")
    print("  into the pause. In C_normal the comparison is turns, done and secs.")
    print()

    unscored = [s for s in found if not s.block]
    if unscored:
        print(f"  ({len(unscored)} session(s) without a block label were left out.)")
    return 0


# --------------------------------------------------------------------------
# task: the live comparison against the annotated date
# --------------------------------------------------------------------------

def load_annotations(path):
    """product_id -> the annotated answer, or {} if the file is not there."""
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
    except (OSError, json.JSONDecodeError) as problem:
        print("annotations unreadable (%s); dates cannot be scored" % problem,
              file=sys.stderr)
        return {}
    return {p["product_id"]: p for p in data.get("products", [])}


def date_parts(value):
    """(year, month, day) from an ISO or partial-ISO date, missing parts None.

    Handles the three shapes the annotations use - "2027-07-21", "2027-04" and
    "--09-12" - and whatever the planner puts in the same field.
    """
    if not value or not isinstance(value, str):
        return None
    text = value.strip()
    if text.startswith("--"):
        rest = text[2:].split("-")
        month = rest[0] if len(rest) > 0 else ""
        day = rest[1] if len(rest) > 1 else ""
        return (None,
                int(month) if month.isdigit() else None,
                int(day) if day.isdigit() else None)
    parts = []
    for bit in text.split("-")[:3]:
        parts.append(int(bit) if bit.isdigit() else None)
    while len(parts) < 3:
        parts.append(None)
    return tuple(parts)


def dates_agree(annotated, spoken):
    """Whether the session's date matches, to the precision the annotation asserts.

    The annotation is deliberately the weaker of the two: a package printed
    "04-2027" has no day to get right, so a session answering that month is
    correct and is not marked down for the day it did or did not invent. Only
    the fields the annotation actually states are compared. None means the
    comparison could not be made at all.
    """
    left = date_parts(annotated)
    right = date_parts(spoken)
    if left is None or right is None:
        return None
    compared = 0
    for a, b in zip(left, right):
        if a is None:
            continue
        compared += 1
        if a != b:
            return False
    return True if compared else None


def leaked_sessions(sessions):
    """Sessions whose product was inside the reference index they retrieved from.

    RETRIEVAL.md says nothing in the app can run this check, because the phone
    does not know which product is in the user's hand. It does now: run_label
    names it, and session_start records which products the bundled index held
    out. A session that fails this could have been handed its own product's
    case and cannot be counted.
    """
    leaked = []
    for s in sessions:
        if not s.retrieval.get("active"):
            continue
        excluded = s.retrieval.get("excluded_products") or []
        if excluded and s.run_label not in excluded:
            leaked.append(s)
    return leaked


def task_report(found, annotations):
    usable = [s for s in found
              if s.condition and s.run_label and s.run_label in annotations]
    skipped = [s for s in found
               if s.condition and not (s.run_label and s.run_label in annotations)]

    if not usable:
        print("No session names a product that the annotations know.")
        print("Set the product for the run, in run_config.json:")
        print('    {"label": "P25"}')
        if skipped:
            print("(%d session(s) had a retrieval condition but no usable product "
                  "label.)" % len(skipped))
        return 1

    print("=== live task comparison, by retrieval condition ===\n")
    print("Whole sessions on real products, scored against the annotated date.\n")

    leaked = leaked_sessions(usable)
    if leaked:
        print("  !! %d session(s) used a product that is IN the reference index."
              % len(leaked))
        print("     Retrieval could return the product's own case, so these are")
        print("     contaminated and are left out of the table below:")
        for s in leaked:
            print("       %s  %s" % (s.id[:8], s.run_label))
        print()

    grouped = defaultdict(list)
    for s in usable:
        if s not in leaked:
            grouped[s.condition].append(s)

    print("  %-20s %3s %5s %6s %6s %5s %6s %6s %8s"
          % ("condition", "n", "done", "right", "wrong", "type", "turns", "secs", "tokens"))

    for condition in ("No retrieval", "Geometry counts", "Geometry + names"):
        group = grouped.get(condition) or []
        if not group:
            continue

        right = wrong = unscored = 0
        type_right = type_judged = 0
        for s in group:
            answer = s.answer
            if answer is None:
                continue
            truth = annotations[s.run_label]
            policy = answer.get("policy") or {}
            verdict = dates_agree(truth.get("normalized_date"), policy.get("iso_date"))
            if verdict is True:
                right += 1
            elif verdict is False:
                wrong += 1
            else:
                unscored += 1
            if truth.get("date_type") and truth["date_type"] != "UNKNOWN":
                type_judged += 1
                if policy.get("date_type") == truth["date_type"]:
                    type_right += 1

        done = sum(1 for s in group if s.answer is not None)
        turns = statistics.median([len(s.observations) for s in group])
        secs = statistics.median([s.seconds for s in group])
        tokens = statistics.median([(s.finish or {}).get("tokens") or 0 for s in group])
        types = "%d/%d" % (type_right, type_judged) if type_judged else "-"

        print("  %-20s %3d %5d %6d %6d %5s %6.0f %6.0f %8.0f"
              % (condition, len(group), done, right, wrong, types, turns, secs, tokens))

    print()
    print("  done   sessions that reached an ANSWER   right/wrong  against the annotation")
    print("  type   BEST_BEFORE vs USE_BY agreement, where the annotation states one")
    print("  turns  median model turns   secs median session length   tokens median spend")
    print()
    print("  A date is judged only to the precision the annotation asserts: a package")
    print("  printed \"04-2027\" has no day to get right.")
    print("  Labels are drafts. Both statuses on these products read HUMAN_VERIFIED,")
    print("  which RETRIEVAL.md records as written from the reviewer's statement")
    print("  rather than clicked per product in the review page.")
    if skipped:
        print("\n  (%d session(s) left out for want of a known product label.)" % len(skipped))
    return 0


# --------------------------------------------------------------------------
# arms: the shadow comparison
# --------------------------------------------------------------------------

def arms_report(session):
    if not session.arms:
        return

    print(f"session {session.id[:8]}  block={session.block or '-'}  "
          f"live={session.live_arm}  {len(session.observations)} observations, "
          f"{len(session.automatic)} live automatic captures, "
          f"{len(session.shadow)} shadow captures")

    if not session.shadow:
        print("  no shadow captures recorded\n")
        return

    # The divergence point per instruction is when the LIVE controller reserved,
    # not when any shadow arm fired.
    live_at = {}
    for row in session.automatic:
        live_at.setdefault(row.get("instruction_id"), row["ts"])

    per_arm = defaultdict(list)
    for row in session.shadow:
        per_arm[row["arm"]].append(row)

    accepted_stale = Counter()
    judged = Counter()
    for row in session.freshness:
        for arm in session.arms:
            verdict = row.get(arm)
            if verdict is None:
                continue
            judged[arm] += 1
            # The live controller is the only reference available on-device for
            # whether the view had really moved on. Disagreement with it is what
            # to look at; it is not ground truth, only the best judge present.
            if verdict and row.get("live_fresh") is False:
                accepted_stale[arm] += 1

    print(f"  {'arm':<22} {'captures':>8} {'valid':>6} {'counterf':>9} "
          f"{'idle':>5} {'vs live ms':>11} {'kept stale':>11}")

    for arm in session.arms:
        got = per_arm.get(arm, [])
        valid = [r for r in got if not r.get("after_live_capture")]
        counterfactual = len(got) - len(valid)

        # A capture taken although the view still matched the reference its
        # instruction was given about - and under a reason that was supposed to
        # wait for movement. An opening look legitimately fires on an unchanged
        # view and must not be counted here.
        idle = 0
        for row in valid:
            if row.get("instruction_relation") != "same":
                continue
            reason = row.get("capture_reason") or session.reason_for(row.get("instruction_id"))
            if reason == NEEDS_MOVEMENT:
                idle += 1

        deltas = []
        for row in valid:
            reference = live_at.get(row.get("instruction_id"))
            if reference is not None:
                deltas.append((row["ts"] - reference) * 1000)
        delta = f"{statistics.median(deltas):+.0f}" if deltas else "-"

        stale = f"{accepted_stale[arm]}/{judged[arm]}" if judged[arm] else "-"

        print(f"  {arm:<22} {len(got):>8} {len(valid):>6} {counterfactual:>9} "
              f"{idle:>5} {delta:>11} {stale:>11}")

    print()


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("events", help="events.jsonl pulled from the device")
    parser.add_argument("--session", help="one session id")
    parser.add_argument("--validate", action="store_true",
                        help="integrity checks on the log")
    parser.add_argument("--by-gate", action="store_true",
                        help="the live controller comparison, by condition and block")
    parser.add_argument("--task", action="store_true",
                        help="the live task comparison, by retrieval condition")
    parser.add_argument("--arms", action="store_true",
                        help="the shadow arm tables")
    parser.add_argument("--annotations", default=DEFAULT_ANNOTATIONS,
                        help="annotations.draft.json, for the annotated dates")
    args = parser.parse_args()

    rows = load(args.events)
    found = sessions_of(rows)
    if args.session:
        found = [s for s in found if s.id.startswith(args.session)]
        if not found:
            print(f"no session matching {args.session}", file=sys.stderr)
            return 1

    # No mode given: everything, validation first.
    everything = not (args.validate or args.by_gate or args.task or args.arms)

    status = 0
    if args.validate or everything:
        status |= validate(found)
    if args.by_gate or everything:
        status |= by_gate(found)
    if args.task or everything:
        # Only a failure when the report was asked for by name. A whole-log run
        # should not report failure because it also held controller sessions.
        outcome = task_report(found, load_annotations(args.annotations))
        if args.task:
            status |= outcome
    if args.arms or everything:
        print("=== shadow arms ===\n")
        withany = [s for s in found if s.arms]
        if not withany:
            print("No ablation rows in this log.\n"
                  "Turn on 'Record what other capture policies would have done' in\n"
                  "settings, run a session, and pull events.jsonl again.\n")
        for session in withany:
            arms_report(session)
        print("valid      = decided before the live capture, on input the arm really saw")
        print("counterf   = after it, when the session would already have diverged")
        print("idle       = fired with no change from the instruction view, when the")
        print("             reason required one")
        print("vs live    = median offset from the live controller's own reservation")
        print("kept stale = judged a reply usable that the live controller rejected")

    return status


if __name__ == "__main__":
    sys.exit(main())
