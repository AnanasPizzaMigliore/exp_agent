"""Per-session memory.

VScan's own TabAdapter.consultConfig() builds a fresh Conversation on every call,
so the app has no cross-turn memory to borrow. Everything an inspection needs to
remember lives here instead.

In-process and single-node by design: one researcher, one phone, one laptop. If
this ever needs to survive a restart, swap the dict for SQLite and keep the API.
"""

from __future__ import annotations

import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path

from schemas import ActionStatus, Perception, ViewQuality

# A session older than this is assumed abandoned; the phone died, or the user
# walked away mid-aisle. Reaped lazily so there is no background thread.
SESSION_TTL_SECONDS = 60 * 30


@dataclass
class Turn:
    """One capture and everything decided because of it."""

    frame_id: int
    instruction_id: int | None
    perception: Perception
    action_status: ActionStatus
    view_quality: ViewQuality
    what_changed: str | None
    instruction: str
    rationale: str | None
    latency_ms: int
    tokens: int


@dataclass
class RegionRecord:
    """A face of the package we have looked at, and how well we saw it.

    inspected_well is deliberately not a bool on its own: a face seen only
    through glare is not a face we can rule out.
    """

    surface: str
    best_quality: ViewQuality
    date_region_visible: bool
    date_legible: bool
    times_seen: int = 1


@dataclass
class Session:
    session_id: str
    created_at: float
    store_images: bool
    product_hint: str | None = None

    # Outstanding instruction the user is currently acting on.
    instruction_id: int = 0
    outstanding_instruction: str | None = None

    # Previous accepted frame, kept for action verification.
    previous_image_b64: str | None = None
    last_frame_id: int = -1

    geometry: str = "unknown"
    regions: dict[str, RegionRecord] = field(default_factory=dict)

    # Candidate reading and the uncertainty we have not resolved.
    candidate_date: str | None = None
    candidate_iso: str | None = None
    candidate_type: str | None = None
    unresolved: list[str] = field(default_factory=list)

    history: list[Turn] = field(default_factory=list)
    tokens_spent: int = 0
    stopped: bool = False
    finished: bool = False

    # Guards against overlapping work for one session.
    lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    def next_instruction_id(self) -> int:
        self.instruction_id += 1
        return self.instruction_id

    def record_region(self, perception: Perception, quality: ViewQuality) -> None:
        """Fold one observation into what we know about the package's faces."""
        surface = perception.surface_in_view or "unclear"
        existing = self.regions.get(surface)
        if existing is None:
            self.regions[surface] = RegionRecord(
                surface=surface,
                best_quality=quality,
                date_region_visible=perception.date_region_visible,
                date_legible=perception.date_legible,
            )
            return
        existing.times_seen += 1
        # Only ever upgrade the recorded quality: one clean look at a face is
        # what licenses ruling it out, and a later glare-ruined frame of the
        # same face must not erase that.
        if _quality_rank(quality) > _quality_rank(existing.best_quality):
            existing.best_quality = quality
        existing.date_region_visible = existing.date_region_visible or perception.date_region_visible
        existing.date_legible = existing.date_legible or perception.date_legible

    def well_inspected_surfaces(self) -> list[str]:
        return [
            name
            for name, record in self.regions.items()
            if record.best_quality is ViewQuality.USABLE
        ]

    def belief(self) -> dict:
        """Compact state handed to the planner. No images: the planner is text-only."""
        return {
            "geometry": self.geometry,
            "product_hint": self.product_hint,
            "surfaces_inspected_well": self.well_inspected_surfaces(),
            "surfaces_seen": {
                name: {
                    "quality": record.best_quality.value,
                    "date_region_visible": record.date_region_visible,
                    "date_legible": record.date_legible,
                    "times_seen": record.times_seen,
                }
                for name, record in self.regions.items()
            },
            "candidate_date": self.candidate_date,
            "candidate_iso": self.candidate_iso,
            "candidate_type": self.candidate_type,
            "unresolved": self.unresolved,
            "turns": len(self.history),
        }

    def visible_history(self, limit: int = 6) -> list[dict]:
        return [
            {
                "frame_id": turn.frame_id,
                "surface_in_view": turn.perception.surface_in_view,
                "date_region_visible": turn.perception.date_region_visible,
                "date_legible": turn.perception.date_legible,
                # Carried so answer_is_grounded() can accept a date_type that an
                # earlier, cleaner frame established. Without it that corroboration
                # branch can never match and the guard silently over-rejects.
                "date_type": turn.perception.date_type.value if turn.perception.date_type else None,
                "looks_like": turn.perception.looks_like,
                "problems": turn.perception.problems,
                "instruction": turn.instruction,
                "action_status": turn.action_status.value,
                "view_quality": turn.view_quality.value,
                "what_changed": turn.what_changed,
            }
            for turn in self.history[-limit:]
        ]


def _quality_rank(quality: ViewQuality) -> int:
    return {ViewQuality.UNUSABLE: 0, ViewQuality.POOR: 1, ViewQuality.USABLE: 2}[quality]


class SessionStore:
    def __init__(self, image_dir: Path | None = None):
        self._sessions: dict[str, Session] = {}
        self._lock = threading.Lock()
        self._image_dir = image_dir

    def create(self, *, product_hint: str | None, store_images: bool) -> Session:
        self._reap()
        session = Session(
            session_id=uuid.uuid4().hex,
            created_at=time.time(),
            store_images=store_images,
            product_hint=product_hint,
        )
        with self._lock:
            self._sessions[session.session_id] = session
        return session

    def get(self, session_id: str) -> Session | None:
        with self._lock:
            return self._sessions.get(session_id)

    def stop(self, session_id: str) -> Session | None:
        session = self.get(session_id)
        if session is not None:
            session.stopped = True
        return session

    def save_image(self, session: Session, frame_id: int, image_bytes: bytes) -> Path | None:
        """Persist a frame only when the user consented at session start."""
        if not session.store_images or self._image_dir is None:
            return None
        directory = self._image_dir / session.session_id
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / f"frame_{frame_id:04d}.jpg"
        path.write_bytes(image_bytes)
        return path

    def _reap(self) -> None:
        cutoff = time.time() - SESSION_TTL_SECONDS
        with self._lock:
            stale = [key for key, value in self._sessions.items() if value.created_at < cutoff]
            for key in stale:
                del self._sessions[key]
