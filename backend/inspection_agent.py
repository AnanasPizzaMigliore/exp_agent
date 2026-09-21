"""Perception, action verification and planning.

Three model calls, deliberately kept apart, for the same reason agent_demo.py
keeps two apart: when a session goes wrong you need to know whether it mis-saw
the package, mis-judged whether the user moved it, or mis-decided what to ask
for next. Fused into one call that question is unanswerable.

    PERCEPTION(frame)                  -> what is visible right now
    VERIFY(previous, current, asked)   -> did the requested movement happen
    POLICY(belief, obs, verify)        -> what to say next        (no image: cheap)

The perception and policy prompts are carried over from agent_demo.py, which was
tuned against real photographs. Keep them in sync when you change either.
"""

from __future__ import annotations

import base64
import io
import json
import os
import time

from dotenv import load_dotenv
from openai import OpenAI
from PIL import Image

from schemas import (
    ActionStatus,
    DateType,
    Perception,
    Policy,
    Verification,
    ViewQuality,
)

# Loaded here rather than in server.py because this module reads the environment
# at import time and server.py imports it. Does not override a variable that is
# already set, so an explicit `set VAR=...` still wins over the file - which is
# what lets the tests pin EXPIRY_AUTH_TOKEN without touching .env.
load_dotenv()

# Qwen credentials stay here, on the backend. The phone never sees them.
MODEL = os.environ.get("EXPIRY_MODEL", "qwen3.8-max")
BASE_URL = os.environ.get("EXPIRY_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
API_KEY_ENV = os.environ.get("EXPIRY_API_KEY_ENV", "DASHSCOPE_API_KEY")

MAX_EDGE = 1568
MAX_TOKENS = 4000

# Small printed date codes are the whole point, so downscaling is a real risk.
# Frames arrive already sized by the phone; this is a ceiling, not a target.
VERIFY_MAX_EDGE = 768  # verification only needs gross layout change, not glyphs


class ModelOutputError(RuntimeError):
    pass


PERCEPTION_SYS = """\
You are the eyes of an assistant helping a blind shopper. Describe only what is
actually visible in this one photograph. Do not guess, do not infer from what a
package of this kind usually looks like, and do not speculate about faces you
cannot see.

A date code is dot-matrix or laser-printed, often on a lid, a base, or a carton
flap, and is easily confused with a lot code (commonly prefixed L or LOTE) or with
a clock time printed beside it by the coding machine. A lot code, packaging date,
or print time is not an expiry date. If a date and time occur together, exclude the
time from date_string.

Reply as JSON only:
{"geometry": "carton|jar|can|bottle|bag|cup|tray|multipack|unknown",
 "surface_in_view": "side|top|base|label|unclear",
 "date_region_visible": true/false,
 "date_legible": true/false,
 "text_read": "verbatim, or null",
 "date_string": "expiry date only, verbatim, or null",
 "iso_date": "YYYY-MM-DD, YYYY-MM, or null",
 "date_type": "USE_BY|BEST_BEFORE|PRODUCTION|LOT_CODE|null",
 "looks_like": "date|packaging_date|lot_code|print_time|mixed|none",
 "problems": ["glare"|"blur"|"too_far"|"occluded_by_hand"|"cut_off"|"none"]}"""

VERIFY_SYS = """\
Two photographs of the same package, taken moments apart. Between them a blind
user was asked to do one thing. Decide whether they did it.

You are NOT reading the date here and you are NOT judging the package. Only
report what changed between the two images and how usable the second one is.

Be strict about "completed". Camera shake, a slight drift in framing, or a
change in lighting alone is NOT the requested movement. If the two images differ
only in ways that could come from an unsteady hand, the honest answer is
not_completed. If something clearly moved but not as far or not in the way that
was asked, that is partial. If you genuinely cannot tell the two apart well
enough to judge - too blurred, too dark, framing too different - say
cannot_determine rather than guessing.

Judge view_quality of the SECOND image on its own terms, independently of whether
the movement happened: a perfectly executed turn can still produce a useless
frame, and a frame ruined by glare must not be recorded as a face that was
successfully inspected.

Reply as JSON only:
{"action_status": "completed|partial|not_completed|cannot_determine",
 "view_changed": true/false,
 "what_changed": "one short clause, or null",
 "view_quality": "usable|poor|unusable"}"""

POLICY_SYS = """\
You direct a blind shopper's hands as they look for an expiry date. You cannot
see; you receive only structured observations. Say ONE thing.

The user has ONE free hand: the other holds a cane or a trolley. Manipulations
are not free, so spend them sparingly and never spend one on a face you have
already inspected well.

  hold still, look again        cheapest
  move or tilt the phone        cheap
  turn the package in the hand  costly
  tip it to show lid or base    costliest, needs a regrip

Date placement by package type, which should drive your search order:
  jar, can, cup, tub  -> lid or base FIRST. Turning them about the vertical axis
                         never reveals the code, so do not waste turns rotating.
  carton, box         -> top flap, then side panels.
  bag, pouch          -> the top seam.

Phrase every instruction relative to the object or to gravity - "turn it upside
down", "point the lid at your phone". Never say "left" or "right" on its own: the
user cannot tell whether you mean theirs, the package's, or the camera's. One
short sentence, no preamble, no pleasantries.

If the last instruction was only partly carried out, ask for the REST of that
movement rather than starting a new one, and say so in a way that makes clear it
is a continuation. If it was not carried out at all, consider that your phrasing
may have been the problem and try saying it differently.

Choose action ANSWER only when a date has actually been READ in the current
observation. Choose ABSTAIN when it is illegible, when the package plainly
carries no date, or when you have run out of sensible things to try. Do not
guess a date, and never report a lot code, a packaging date or a print time as
an expiry date.

Reply as JSON only:
{"action": "CONTINUE|ANSWER|ABSTAIN",
 "arg": null,
 "utterance": "what you say aloud, one short sentence",
 "rationale": "why, one clause",
 "date_string": "only when action is ANSWER, else null",
 "iso_date": "YYYY-MM-DD, YYYY-MM, or null",
 "date_type": "USE_BY|BEST_BEFORE|null",
 "abstain_reason": "illegible|no_date_exists|budget|null"}"""


def build_client() -> OpenAI:
    key = os.environ.get(API_KEY_ENV)
    if not key:
        raise RuntimeError(f"set {API_KEY_ENV} before starting the server")
    return OpenAI(api_key=key, base_url=BASE_URL)


def _strip_code_fence(text: str) -> str:
    if not text.startswith("```"):
        return text
    lines = text.splitlines()
    if lines and lines[0].startswith("```"):
        lines = lines[1:]
    if lines and lines[-1].strip() == "```":
        lines = lines[:-1]
    return "\n".join(lines).strip()


def _usage_tokens(usage) -> int:
    return int(getattr(usage, "total_tokens", 0) or 0)


def _call(client: OpenAI, system: str, content, *, timeout: float) -> tuple[dict, int]:
    """One model call. Returns parsed JSON and tokens spent.

    A reasoning model can burn its whole budget before emitting anything, which
    surfaces as finish_reason="length" with empty content rather than an error.
    That is treated as a failure here, not as an empty observation.
    """
    response = client.chat.completions.create(
        model=MODEL,
        max_tokens=MAX_TOKENS,
        timeout=timeout,
        messages=[
            {"role": "system", "content": system},
            {"role": "user", "content": content},
        ],
    )
    tokens = _usage_tokens(response.usage)
    choice = response.choices[0]
    text = (choice.message.content or "").strip()
    if choice.finish_reason == "length" and not text:
        raise ModelOutputError("model truncated by max_tokens before producing output")
    try:
        return json.loads(_strip_code_fence(text)), tokens
    except json.JSONDecodeError as exc:
        raise ModelOutputError(f"model did not return JSON: {text[:300]!r}") from exc


def downscale(image_b64: str, max_edge: int = MAX_EDGE) -> str:
    """Bound the long edge without touching an already-small frame.

    Expiry codes are small and low contrast, so this only ever shrinks images
    that exceed the ceiling; it never upscales and never crops.
    """
    raw = base64.b64decode(image_b64)
    with Image.open(io.BytesIO(raw)) as image:
        image = image.convert("RGB")
        if max(image.size) <= max_edge:
            return image_b64
        scale = max_edge / max(image.size)
        image = image.resize(
            (round(image.width * scale), round(image.height * scale)), Image.LANCZOS
        )
        buffer = io.BytesIO()
        image.save(buffer, format="JPEG", quality=90)
    return base64.b64encode(buffer.getvalue()).decode()


def _image_part(image_b64: str) -> dict:
    return {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{image_b64}"}}


def perceive(client: OpenAI, image_b64: str, *, timeout: float) -> tuple[Perception, int]:
    raw, tokens = _call(
        client,
        PERCEPTION_SYS,
        [_image_part(downscale(image_b64)), {"type": "text", "text": "Describe this photograph."}],
        timeout=timeout,
    )
    raw.pop("_raw", None)
    if raw.get("date_type") not in {"USE_BY", "BEST_BEFORE", "PRODUCTION", "LOT_CODE", None}:
        raw["date_type"] = None
    if isinstance(raw.get("problems"), str):
        raw["problems"] = [raw["problems"]]
    try:
        return Perception(**raw), tokens
    except Exception as exc:
        raise ModelOutputError(f"invalid perception output: {raw}") from exc


def verify(
    client: OpenAI,
    previous_b64: str,
    current_b64: str,
    instruction: str,
    *,
    timeout: float,
) -> tuple[Verification, int]:
    raw, tokens = _call(
        client,
        VERIFY_SYS,
        [
            {"type": "text", "text": f"The user was asked: {instruction!r}"},
            {"type": "text", "text": "First photograph (before):"},
            _image_part(downscale(previous_b64, VERIFY_MAX_EDGE)),
            {"type": "text", "text": "Second photograph (after):"},
            _image_part(downscale(current_b64, VERIFY_MAX_EDGE)),
        ],
        timeout=timeout,
    )
    try:
        return Verification(**raw), tokens
    except Exception as exc:
        raise ModelOutputError(f"invalid verification output: {raw}") from exc


def plan(
    client: OpenAI,
    belief: dict,
    perception: Perception,
    verification: Verification | None,
    history: list[dict],
    *,
    timeout: float,
) -> tuple[Policy, int]:
    payload = {
        "belief": belief,
        "latest_observation": perception.model_dump(mode="json"),
        "last_action_outcome": verification.model_dump(mode="json") if verification else None,
        "history": history,
    }
    raw, tokens = _call(
        client, POLICY_SYS, json.dumps(payload, ensure_ascii=False), timeout=timeout
    )
    if raw.get("arg") == "null":
        raw["arg"] = None
    if raw.get("date_type") not in {"USE_BY", "BEST_BEFORE", None}:
        raw["date_type"] = None
    try:
        policy = Policy(**raw)
    except Exception as exc:
        raise ModelOutputError(f"invalid policy output: {raw}") from exc
    if policy.action not in {"CONTINUE", "ANSWER", "ABSTAIN"}:
        raise ModelOutputError(f"unknown action {policy.action!r}")
    if not policy.utterance or not policy.utterance.strip():
        raise ModelOutputError("policy returned an empty utterance")
    return policy, tokens


def answer_is_grounded(policy: Policy, perception: Perception, history: list[dict]) -> bool:
    """Refuse an ANSWER the current frame does not actually support.

    Carried over from agent_demo.py's guard. The failure it prevents is the
    expensive one: a confidently spoken date that was inferred from a plausible
    package rather than read off this one.
    """
    if not perception.date_legible:
        return False
    if perception.looks_like not in {"date", "mixed"}:
        return False
    if policy.iso_date and perception.iso_date and policy.iso_date != perception.iso_date:
        return False
    if policy.date_type is None:
        return False
    if perception.date_type is not None and policy.date_type == perception.date_type:
        return True
    # A type established on an earlier, cleaner frame is acceptable corroboration.
    return any(item.get("date_type") == policy.date_type.value for item in history)


class Timed:
    """Wall-clock for one turn, so latency lands in the experiment log."""

    def __enter__(self):
        self._start = time.monotonic()
        return self

    def __exit__(self, *exc):
        self.ms = int((time.monotonic() - self._start) * 1000)
        return False
