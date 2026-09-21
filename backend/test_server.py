"""Backend tests that run without credentials.

The model calls are stubbed, so what is under test is the session machinery:
memory across turns, frame supersession, the ungrounded-answer guard, and the
failure paths that a phone in a supermarket will actually hit.

    cd backend && python -m pytest -q
"""

from __future__ import annotations

import base64
import io
import os

import pytest
from fastapi.testclient import TestClient
from PIL import Image

os.environ.setdefault("EXPIRY_AUTH_TOKEN", "test-token")

import inspection_agent as agent  # noqa: E402
import server  # noqa: E402
from schemas import ActionStatus, Perception, Policy, Verification, ViewQuality  # noqa: E402

AUTH = {"Authorization": "Bearer test-token"}


def jpeg(colour=(180, 180, 180), size=(64, 64)) -> str:
    buffer = io.BytesIO()
    Image.new("RGB", size, colour).save(buffer, format="JPEG")
    return base64.b64encode(buffer.getvalue()).decode()


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setattr(server, "get_client", lambda: object())
    server.store._sessions.clear()
    return TestClient(server.app)


def stub(monkeypatch, *, perception=None, verification=None, policy=None):
    perception = perception or Perception(
        geometry="cup", surface_in_view="top", date_region_visible=False,
        date_legible=False, looks_like="none", problems=["none"],
    )
    verification = verification or Verification(
        action_status=ActionStatus.COMPLETED, view_changed=True,
        what_changed="lid now facing camera", view_quality=ViewQuality.USABLE,
    )
    policy = policy or Policy(action="CONTINUE", utterance="Turn it upside down.", rationale="lid first")
    monkeypatch.setattr(agent, "perceive", lambda *a, **k: (perception, 10))
    monkeypatch.setattr(agent, "verify", lambda *a, **k: (verification, 10))
    monkeypatch.setattr(agent, "plan", lambda *a, **k: (policy, 10))


def start(client) -> str:
    response = client.post("/sessions", json={}, headers=AUTH)
    assert response.status_code == 200
    return response.json()["session_id"]


def observe(client, session_id, frame_id, **kwargs):
    body = {"frame_id": frame_id, "image_b64": jpeg(), "capture": {}}
    body.update(kwargs)
    return client.post(f"/sessions/{session_id}/observations", json=body, headers=AUTH)


def test_auth_is_required(client):
    assert client.post("/sessions", json={}).status_code == 401
    assert client.post("/sessions", json={}, headers={"Authorization": "Bearer wrong"}).status_code == 401


def test_session_remembers_across_turns(client, monkeypatch):
    """Several views, and the earlier observations survive into later belief."""
    stub(monkeypatch)
    session_id = start(client)

    stub(monkeypatch, perception=Perception(
        geometry="cup", surface_in_view="side", date_region_visible=False,
        date_legible=False, looks_like="none", problems=["none"]))
    assert observe(client, session_id, 1).status_code == 200

    stub(monkeypatch, perception=Perception(
        geometry="cup", surface_in_view="top", date_region_visible=True,
        date_legible=False, looks_like="mixed", problems=["glare"]))
    assert observe(client, session_id, 2).status_code == 200

    state = client.get(f"/sessions/{session_id}", headers=AUTH).json()
    assert set(state["belief"]["surfaces_seen"]) == {"side", "top"}
    assert state["belief"]["geometry"] == "cup"
    assert len(state["history"]) == 2


def test_glare_does_not_count_as_inspected(client, monkeypatch):
    """'Visible but ruined by glare' must not become 'inspected, no date there'."""
    session_id = start(client)
    # First frame: a glare-spoiled look at the side. No previous image exists,
    # so quality comes from the perception's own problem list.
    stub(monkeypatch, perception=Perception(
        geometry="jar", surface_in_view="side", date_region_visible=False,
        date_legible=False, looks_like="none", problems=["glare"]))
    observe(client, session_id, 1)
    # Second frame: the base is now showing, but the frame is unusable.
    stub(monkeypatch, perception=Perception(
        geometry="jar", surface_in_view="base", date_region_visible=True,
        date_legible=False, looks_like="mixed", problems=["glare"]),
        verification=Verification(
            action_status=ActionStatus.COMPLETED, view_changed=True,
            what_changed="base now showing", view_quality=ViewQuality.UNUSABLE))
    observe(client, session_id, 2)

    belief = client.get(f"/sessions/{session_id}", headers=AUTH).json()["belief"]
    assert belief["surfaces_inspected_well"] == []
    # The base was seen but not ruled out -- the distinction the session turns on.
    assert belief["surfaces_seen"]["base"]["date_region_visible"] is True
    assert belief["surfaces_seen"]["base"]["quality"] == "unusable"


def test_quality_only_upgrades(client, monkeypatch):
    """One clean look licenses ruling a face out; a later bad frame must not erase it."""
    session_id = start(client)
    stub(monkeypatch, verification=Verification(
        action_status=ActionStatus.COMPLETED, view_changed=True,
        what_changed="top", view_quality=ViewQuality.USABLE))
    observe(client, session_id, 1)
    observe(client, session_id, 2)
    stub(monkeypatch, verification=Verification(
        action_status=ActionStatus.NOT_COMPLETED, view_changed=False,
        what_changed=None, view_quality=ViewQuality.UNUSABLE))
    observe(client, session_id, 3)

    belief = client.get(f"/sessions/{session_id}", headers=AUTH).json()["belief"]
    assert "top" in belief["surfaces_inspected_well"]


def test_partial_rotation_is_reported(client, monkeypatch):
    stub(monkeypatch, verification=Verification(
        action_status=ActionStatus.PARTIAL, view_changed=True,
        what_changed="turned about halfway", view_quality=ViewQuality.USABLE))
    session_id = start(client)
    observe(client, session_id, 1)
    body = observe(client, session_id, 2).json()
    assert body["action_status"] == "partial"


def test_unchanged_image_is_not_completed(client, monkeypatch):
    stub(monkeypatch, verification=Verification(
        action_status=ActionStatus.NOT_COMPLETED, view_changed=False,
        what_changed=None, view_quality=ViewQuality.USABLE))
    session_id = start(client)
    observe(client, session_id, 1)
    assert observe(client, session_id, 2).json()["action_status"] == "not_completed"


def test_ungrounded_answer_is_blocked(client, monkeypatch):
    """A date the current frame cannot support must not be spoken."""
    stub(
        monkeypatch,
        perception=Perception(
            geometry="cup", surface_in_view="top", date_region_visible=False,
            date_legible=False, looks_like="none", problems=["glare"]),
        policy=Policy(action="ANSWER", utterance="It expires on the fourth of May.",
                      date_string="04 MAY 2027", iso_date="2027-05-04", date_type="USE_BY"),
    )
    session_id = start(client)
    body = observe(client, session_id, 1).json()
    assert body["finished"] is False
    assert body["date_text"] is None
    assert "not certain" in body["instruction"].lower()


def test_grounded_answer_finishes(client, monkeypatch):
    stub(
        monkeypatch,
        perception=Perception(
            geometry="cup", surface_in_view="top", date_region_visible=True,
            date_legible=True, text_read="USE BY 04 MAY 2027", date_string="04 MAY 2027",
            iso_date="2027-05-04", date_type="USE_BY", looks_like="date", problems=["none"]),
        policy=Policy(action="ANSWER", utterance="Use by the fourth of May, twenty twenty-seven.",
                      date_string="04 MAY 2027", iso_date="2027-05-04", date_type="USE_BY"),
    )
    session_id = start(client)
    body = observe(client, session_id, 1).json()
    assert body["finished"] is True
    assert body["iso_date"] == "2027-05-04"


def test_earlier_frame_can_corroborate_the_date_type(client, monkeypatch):
    """A type read cleanly on turn one still counts on turn two.

    The second frame reads the digits but not the "USE BY" caption above them,
    which is the ordinary case when the user has moved closer. Rejecting that
    would send the user round the package again for something already seen.
    """
    stub(monkeypatch, perception=Perception(
        geometry="cup", surface_in_view="top", date_region_visible=True, date_legible=True,
        text_read="USE BY 04 MAY 2027", date_string="04 MAY 2027", iso_date="2027-05-04",
        date_type="USE_BY", looks_like="date", problems=["none"]))
    session_id = start(client)
    observe(client, session_id, 1)

    stub(
        monkeypatch,
        perception=Perception(
            geometry="cup", surface_in_view="top", date_region_visible=True, date_legible=True,
            text_read="04 MAY 2027", date_string="04 MAY 2027", iso_date="2027-05-04",
            date_type=None, looks_like="date", problems=["none"]),
        policy=Policy(action="ANSWER", utterance="Use by the fourth of May.",
                      date_string="04 MAY 2027", iso_date="2027-05-04", date_type="USE_BY"),
    )
    body = observe(client, session_id, 2).json()
    assert body["finished"] is True
    assert body["date_type"] == "USE_BY"


def test_answer_contradicting_the_frame_is_blocked(client, monkeypatch):
    """Same frame, different date: the policy's reading loses."""
    stub(
        monkeypatch,
        perception=Perception(
            geometry="cup", surface_in_view="top", date_region_visible=True, date_legible=True,
            text_read="USE BY 04 MAY 2027", date_string="04 MAY 2027", iso_date="2027-05-04",
            date_type="USE_BY", looks_like="date", problems=["none"]),
        policy=Policy(action="ANSWER", utterance="Use by the fourth of March.",
                      date_string="04 MAR 2027", iso_date="2027-03-04", date_type="USE_BY"),
    )
    session_id = start(client)
    body = observe(client, session_id, 1).json()
    assert body["finished"] is False
    assert body["date_text"] is None


def test_missing_date_abstains(client, monkeypatch):
    stub(monkeypatch, policy=Policy(
        action="ABSTAIN", utterance="I cannot find a date on this package.",
        abstain_reason="no_date_exists"))
    session_id = start(client)
    body = observe(client, session_id, 1).json()
    assert body["finished"] is True and body["abstained"] is True
    assert body["abstain_reason"] == "no_date_exists"


def test_superseded_frame_is_rejected(client, monkeypatch):
    stub(monkeypatch)
    session_id = start(client)
    observe(client, session_id, 5)
    assert observe(client, session_id, 4).status_code == 409
    assert observe(client, session_id, 5).status_code == 409


def test_stopped_session_rejects_observations(client, monkeypatch):
    stub(monkeypatch)
    session_id = start(client)
    assert client.post(f"/sessions/{session_id}/stop", headers=AUTH).status_code == 200
    assert observe(client, session_id, 1).status_code == 409


def test_malformed_model_output_is_rejected(client, monkeypatch):
    session_id = start(client)

    def boom(*a, **k):
        raise agent.ModelOutputError("not JSON")

    monkeypatch.setattr(agent, "perceive", boom)
    assert observe(client, session_id, 1).status_code == 502


def test_upstream_failure_is_reported(client, monkeypatch):
    session_id = start(client)

    def boom(*a, **k):
        raise RuntimeError("connection reset")

    monkeypatch.setattr(agent, "perceive", boom)
    assert observe(client, session_id, 1).status_code == 504


def test_bad_image_is_rejected(client, monkeypatch):
    stub(monkeypatch)
    session_id = start(client)
    response = client.post(
        f"/sessions/{session_id}/observations",
        json={"frame_id": 1, "image_b64": "not base64!!", "capture": {}},
        headers=AUTH,
    )
    assert response.status_code == 422


def test_unknown_session(client):
    assert observe(client, "nope", 1).status_code == 404


def test_downscale_leaves_small_images_untouched():
    """Expiry codes are small; the ceiling must never shrink an already-small frame."""
    small = jpeg(size=(64, 64))
    assert agent.downscale(small, 1568) == small


def test_downscale_bounds_large_images():
    large = jpeg(size=(4000, 3000))
    out = base64.b64decode(agent.downscale(large, 1568))
    with Image.open(io.BytesIO(out)) as image:
        assert max(image.size) == 1568
