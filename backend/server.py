"""Session and observation endpoints for the expiry-inspection agent.

Run:
    set DASHSCOPE_API_KEY=...
    set EXPIRY_AUTH_TOKEN=...            # the phone sends this as a bearer token
    python -m uvicorn server:app --host 0.0.0.0 --port 8000

--host 0.0.0.0 is what lets the phone reach the laptop over the LAN. See
README.md for the matching Android network-security config; a debug build will
not talk to plain http:// without it.
"""

from __future__ import annotations

import base64
import json
import logging
import os
import secrets
import time
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, status
from fastapi.responses import JSONResponse

import inspection_agent as agent
from schemas import (
    ActionStatus,
    ObservationRequest,
    ObservationResponse,
    SessionStartRequest,
    SessionStartResponse,
    SessionStopResponse,
    ViewQuality,
)
from session_store import SessionStore, Turn

LOG_DIR = Path(os.environ.get("EXPIRY_LOG_DIR", "runs"))
LOG_DIR.mkdir(parents=True, exist_ok=True)
EVENT_LOG = LOG_DIR / "events.jsonl"

# Bounded, and deliberately far below VScan's own 5-minute Ktor engine timeout:
# a stalled turn has to fail while the user is still standing in the aisle.
PERCEPTION_TIMEOUT = float(os.environ.get("EXPIRY_PERCEPTION_TIMEOUT", "25"))
VERIFY_TIMEOUT = float(os.environ.get("EXPIRY_VERIFY_TIMEOUT", "20"))
POLICY_TIMEOUT = float(os.environ.get("EXPIRY_POLICY_TIMEOUT", "20"))

MAX_IMAGE_BYTES = 12 * 1024 * 1024

OPENING_INSTRUCTION = "Hold the package steady in front of the camera, then press Capture."

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("expiry")

app = FastAPI(title="VScan expiry inspection backend")
store = SessionStore(image_dir=LOG_DIR / "images")
client = None  # built lazily so the module imports without credentials


def get_client():
    global client
    if client is None:
        client = agent.build_client()
    return client


def require_auth(authorization: str | None = Header(default=None)) -> None:
    """Shared-secret bearer auth.

    Weak by design and adequate for a LAN research rig, but it must not be
    absent: without it anything on the network can push frames into a session.
    """
    expected = os.environ.get("EXPIRY_AUTH_TOKEN")
    if not expected:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="EXPIRY_AUTH_TOKEN is not set on the server",
        )
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="missing bearer token")
    if not secrets.compare_digest(authorization.removeprefix("Bearer "), expected):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="bad token")


def log_event(kind: str, session_id: str, payload: dict) -> None:
    record = {"ts": time.time(), "kind": kind, "session_id": session_id, **payload}
    with EVENT_LOG.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(record, ensure_ascii=False, default=str) + "\n")


@app.get("/health")
def health() -> dict:
    return {"ok": True, "model": agent.MODEL}


@app.post("/sessions", response_model=SessionStartResponse, dependencies=[Depends(require_auth)])
def start_session(request: SessionStartRequest) -> SessionStartResponse:
    session = store.create(product_hint=request.product_hint, store_images=request.store_images)
    instruction_id = session.next_instruction_id()
    session.outstanding_instruction = OPENING_INSTRUCTION
    log_event(
        "session_start",
        session.session_id,
        {
            "product_hint": request.product_hint,
            "store_images": request.store_images,
            "client_version": request.client_version,
            "model": agent.MODEL,
        },
    )
    return SessionStartResponse(
        session_id=session.session_id,
        instruction=OPENING_INSTRUCTION,
        instruction_id=instruction_id,
    )


@app.post(
    "/sessions/{session_id}/observations",
    response_model=ObservationResponse,
    dependencies=[Depends(require_auth)],
)
def observe(session_id: str, request: ObservationRequest) -> ObservationResponse:
    session = store.get(session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="unknown session")
    if session.stopped:
        # The user pressed stop while this frame was in flight. Nothing about
        # this response should reach them.
        raise HTTPException(status_code=409, detail="session stopped")
    if session.finished:
        raise HTTPException(status_code=409, detail="session already finished")

    if not session.lock.acquire(blocking=False):
        # At most one in-flight observation per session. A second capture that
        # races the first is dropped rather than queued: by the time it were
        # served its instruction would be stale anyway.
        raise HTTPException(status_code=429, detail="observation already in flight")
    try:
        if request.frame_id <= session.last_frame_id:
            raise HTTPException(
                status_code=409,
                detail=f"superseded frame {request.frame_id} (last {session.last_frame_id})",
            )

        try:
            image_bytes = base64.b64decode(request.image_b64, validate=True)
        except Exception:
            raise HTTPException(status_code=422, detail="image_b64 is not valid base64")
        if not image_bytes:
            raise HTTPException(status_code=422, detail="empty image")
        if len(image_bytes) > MAX_IMAGE_BYTES:
            raise HTTPException(status_code=413, detail="image too large")

        api = get_client()
        tokens = 0
        with agent.Timed() as timed:
            try:
                perception, used = agent.perceive(
                    api, request.image_b64, timeout=PERCEPTION_TIMEOUT
                )
                tokens += used

                verification = None
                if session.previous_image_b64 and session.outstanding_instruction:
                    verification, used = agent.verify(
                        api,
                        session.previous_image_b64,
                        request.image_b64,
                        session.outstanding_instruction,
                        timeout=VERIFY_TIMEOUT,
                    )
                    tokens += used

                policy, used = agent.plan(
                    api,
                    session.belief(),
                    perception,
                    verification,
                    session.visible_history(),
                    timeout=POLICY_TIMEOUT,
                )
                tokens += used
            except agent.ModelOutputError as exc:
                log_event(
                    "model_error", session_id, {"frame_id": request.frame_id, "error": str(exc)}
                )
                raise HTTPException(status_code=502, detail=f"model output rejected: {exc}")
            except Exception as exc:
                log_event(
                    "upstream_error", session_id, {"frame_id": request.frame_id, "error": str(exc)}
                )
                raise HTTPException(status_code=504, detail="model call failed")

        # The user may have stopped the session while the model was thinking.
        if session.stopped:
            raise HTTPException(status_code=409, detail="session stopped")

        action_status = verification.action_status if verification else ActionStatus.CANNOT_DETERMINE
        view_quality = verification.view_quality if verification else _quality_from(perception)

        session.record_region(perception, view_quality)
        if perception.geometry and perception.geometry != "unknown":
            session.geometry = perception.geometry

        finished = False
        abstained = False
        abstain_reason = None
        date_text = None
        iso_date = None
        date_type = None

        if policy.action == "ANSWER":
            if agent.answer_is_grounded(policy, perception, session.visible_history(limit=50)):
                finished = True
                date_text = policy.date_string
                iso_date = policy.iso_date
                date_type = policy.date_type
                session.candidate_date = date_text
                session.candidate_iso = iso_date
                session.candidate_type = date_type.value if date_type else None
            else:
                # Ungrounded answer: refuse it and keep the session alive rather
                # than speaking a date this frame does not support.
                log_event(
                    "guard_blocked_answer",
                    session_id,
                    {
                        "frame_id": request.frame_id,
                        "attempted": policy.model_dump(mode="json"),
                        "perception": perception.model_dump(mode="json"),
                    },
                )
                policy.utterance = (
                    "I am not certain enough of that reading. Hold the code steady "
                    "and a little closer, then press Capture."
                )
        elif policy.action == "ABSTAIN":
            finished = True
            abstained = True
            abstain_reason = policy.abstain_reason or "illegible"

        instruction_id = session.next_instruction_id()
        session.outstanding_instruction = policy.utterance
        session.previous_image_b64 = request.image_b64
        session.last_frame_id = request.frame_id
        session.tokens_spent += tokens
        session.finished = finished
        session.history.append(
            Turn(
                frame_id=request.frame_id,
                instruction_id=request.instruction_id,
                perception=perception,
                action_status=action_status,
                view_quality=view_quality,
                what_changed=verification.what_changed if verification else None,
                instruction=policy.utterance,
                rationale=policy.rationale,
                latency_ms=timed.ms,
                tokens=tokens,
            )
        )

        saved = store.save_image(session, request.frame_id, image_bytes)
        log_event(
            "observation",
            session_id,
            {
                "frame_id": request.frame_id,
                "answered_instruction_id": request.instruction_id,
                "capture": request.capture.model_dump(mode="json"),
                "perception": perception.model_dump(mode="json"),
                "verification": verification.model_dump(mode="json") if verification else None,
                "policy": policy.model_dump(mode="json"),
                "action_status": action_status.value,
                "view_quality": view_quality.value,
                "finished": finished,
                "latency_ms": timed.ms,
                "tokens": tokens,
                "model": agent.MODEL,
                "image_path": str(saved) if saved else None,
            },
        )

        return ObservationResponse(
            frame_id=request.frame_id,
            action_status=action_status,
            view_quality=view_quality,
            instruction=policy.utterance,
            instruction_id=instruction_id,
            date_text=date_text,
            iso_date=iso_date,
            date_type=date_type,
            finished=finished,
            abstained=abstained,
            abstain_reason=abstain_reason,
        )
    finally:
        session.lock.release()


@app.post(
    "/sessions/{session_id}/stop",
    response_model=SessionStopResponse,
    dependencies=[Depends(require_auth)],
)
def stop_session(session_id: str) -> SessionStopResponse:
    session = store.stop(session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="unknown session")
    log_event(
        "session_stop",
        session_id,
        {"frames_seen": len(session.history), "tokens": session.tokens_spent},
    )
    return SessionStopResponse(
        session_id=session_id, stopped=True, frames_seen=len(session.history)
    )


@app.get("/sessions/{session_id}", dependencies=[Depends(require_auth)])
def inspect_session(session_id: str) -> JSONResponse:
    """Debugging aid: the whole belief state, for eyeballing a run."""
    session = store.get(session_id)
    if session is None:
        raise HTTPException(status_code=404, detail="unknown session")
    return JSONResponse(
        {
            "session_id": session_id,
            "belief": session.belief(),
            "history": session.visible_history(limit=100),
            "stopped": session.stopped,
            "finished": session.finished,
            "tokens": session.tokens_spent,
        }
    )


def _quality_from(perception) -> ViewQuality:
    """Fallback quality for the first frame, where there is nothing to compare."""
    problems = {problem for problem in perception.problems if problem and problem != "none"}
    if {"blur", "too_far"} & problems and not perception.date_legible:
        return ViewQuality.POOR
    if "occluded_by_hand" in problems:
        return ViewQuality.POOR
    return ViewQuality.USABLE if not problems else ViewQuality.POOR
