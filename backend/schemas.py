"""Validated request/response definitions for the inspection backend.

The Android client and the agent share exactly these shapes. Anything the model
returns is coerced through here before it reaches a caller, so a malformed model
response fails at the boundary rather than halfway through a session.
"""

from __future__ import annotations

from enum import Enum
from typing import Literal

from pydantic import BaseModel, Field


class ActionStatus(str, Enum):
    """Did the user carry out the outstanding instruction?

    CANNOT_DETERMINE is a first-class outcome, not an error. Two frames that
    differ only by camera shake genuinely do not tell you whether the package
    was rotated, and pretending otherwise is how a session convinces itself it
    has inspected a face it never saw.
    """

    COMPLETED = "completed"
    PARTIAL = "partial"
    NOT_COMPLETED = "not_completed"
    CANNOT_DETERMINE = "cannot_determine"


class ViewQuality(str, Enum):
    """How usable the current frame is, recorded separately from what it shows.

    Kept apart from ActionStatus on purpose: "bottom visible but wrecked by
    glare" must never collapse into "bottom inspected, no date there".
    """

    USABLE = "usable"
    POOR = "poor"
    UNUSABLE = "unusable"


class DateType(str, Enum):
    USE_BY = "USE_BY"
    BEST_BEFORE = "BEST_BEFORE"
    PRODUCTION = "PRODUCTION"
    LOT_CODE = "LOT_CODE"


class CaptureMetadata(BaseModel):
    """What the phone knew when the shutter fired."""

    width: int | None = None
    height: int | None = None
    rotation_degrees: int | None = None
    flash_fired: bool | None = None
    device_ms: int | None = Field(
        default=None, description="Client monotonic clock, for latency accounting only."
    )


class SessionStartRequest(BaseModel):
    product_hint: str | None = Field(
        default=None,
        description="Optional free text from the user, e.g. 'yoghurt pot'. Never trusted as evidence.",
    )
    store_images: bool = Field(
        default=False,
        description="Persist frames to disk. Requires explicit user consent on the device.",
    )
    client_version: str | None = None


class SessionStartResponse(BaseModel):
    session_id: str
    instruction: str
    instruction_id: int


class ObservationRequest(BaseModel):
    """One captured still, plus the context needed to interpret it."""

    frame_id: int = Field(description="Client-assigned, strictly increasing within a session.")
    instruction_id: int | None = Field(
        default=None,
        description="The outstanding instruction this frame is a response to; null for the first frame.",
    )
    image_b64: str = Field(description="JPEG bytes, base64. Not a data: URL.")
    capture: CaptureMetadata = CaptureMetadata()


class ObservationResponse(BaseModel):
    """The only thing the device acts on."""

    frame_id: int
    action_status: ActionStatus
    view_quality: ViewQuality
    instruction: str
    instruction_id: int
    date_text: str | None = None
    iso_date: str | None = None
    date_type: DateType | None = None
    finished: bool = False
    abstained: bool = False
    abstain_reason: Literal["illegible", "no_date_exists", "budget", "unsafe_to_answer"] | None = None


class SessionStopResponse(BaseModel):
    session_id: str
    stopped: bool
    frames_seen: int


# ---------------------------------------------------------------------------
# Model-facing shapes. These mirror the JSON the prompts ask for; they are
# validated separately from the client contract so a model regression cannot
# quietly change what the device receives.
# ---------------------------------------------------------------------------


class Perception(BaseModel):
    geometry: str = "unknown"
    surface_in_view: str = "unclear"
    date_region_visible: bool = False
    date_legible: bool = False
    text_read: str | None = None
    date_string: str | None = None
    iso_date: str | None = None
    date_type: DateType | None = None
    looks_like: str = "none"
    problems: list[str] = Field(default_factory=list)


class Verification(BaseModel):
    """Result of comparing the previous accepted frame with the current one."""

    action_status: ActionStatus
    view_changed: bool
    what_changed: str | None = None
    view_quality: ViewQuality


class Policy(BaseModel):
    action: str
    arg: str | None = None
    utterance: str
    rationale: str | None = None
    date_string: str | None = None
    iso_date: str | None = None
    date_type: DateType | None = None
    abstain_reason: Literal["illegible", "no_date_exists", "budget"] | None = None
