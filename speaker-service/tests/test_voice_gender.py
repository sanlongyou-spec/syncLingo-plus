import base64
import importlib
import sys
import types

from fastapi.testclient import TestClient


try:
    import sherpa_onnx  # noqa: F401
except ImportError:
    sys.modules["sherpa_onnx"] = types.SimpleNamespace()


main = importlib.import_module("main")

from voice_gender import VoiceGenderDetector


def _detector(confidence: float = 0.75, margin: float = 0.15) -> VoiceGenderDetector:
    return VoiceGenderDetector(
        enabled=False,
        model_dir="models/__none__",
        min_seconds=6,
        max_seconds=8,
        confidence=confidence,
        margin=margin,
        labels="child,female,male",
        num_threads=1,
    )


def test_decide_accepts_clear_gender():
    gender, _conf, _male, _female, _child, reason = _detector()._decide(
        {"male": 0.91, "female": 0.06, "child": 0.03}
    )
    assert gender == "male"
    assert reason == "accepted"


def test_decide_reports_low_confidence():
    gender, *_, reason = _detector()._decide({"male": 0.56, "female": 0.44, "child": 0.0})
    assert gender == "unknown"
    assert reason == "low_confidence"


def test_decide_reports_low_margin():
    gender, *_, reason = _detector()._decide({"male": 0.80, "female": 0.78, "child": 0.0})
    assert gender == "unknown"
    assert reason == "low_margin"


def test_decide_reports_child_dominant():
    gender, *_, reason = _detector()._decide({"male": 0.80, "female": 0.10, "child": 0.90})
    assert gender == "unknown"
    assert reason == "child_dominant"


class FakeDetector:
    loaded = True

    def predict(self, pcm_data: bytes, sample_rate: int):
        return {
            "gender": "female",
            "confidence": 0.92,
            "male_score": 0.04,
            "female_score": 0.92,
            "child_score": 0.04,
            "latency_ms": 3.5,
            "model_available": True,
            "reason": "accepted",
        }


def test_voice_gender_endpoint_uses_detector(monkeypatch):
    monkeypatch.setattr(main, "voice_gender_detector", FakeDetector())
    client = TestClient(main.app)
    payload = {
        "audio_base64": base64.b64encode(b"\x00\x00" * 16000 * 6).decode("ascii"),
        "sample_rate": 16000,
        "encoding": "pcm_s16le",
        "speaker_id": "speaker-a",
    }

    response = client.post("/voice-gender", json=payload)

    assert response.status_code == 200
    body = response.json()
    assert body["gender"] == "female"
    assert body["confidence"] == 0.92
    assert body["model_available"] is True
    assert body["reason"] == "accepted"


def test_voice_gender_endpoint_degrades_on_invalid_audio(monkeypatch):
    monkeypatch.setattr(main, "voice_gender_detector", FakeDetector())
    client = TestClient(main.app)

    response = client.post(
        "/voice-gender",
        json={"audio_base64": "not-base64", "sample_rate": 16000, "encoding": "pcm_s16le"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["gender"] == "unknown"
    assert body["model_available"] is False
    assert body["reason"] == "bad_base64"


def test_health_reports_voice_gender_model_loaded(monkeypatch):
    monkeypatch.setattr(main, "voice_gender_detector", FakeDetector())
    client = TestClient(main.app)

    response = client.get("/health")

    assert response.status_code == 200
    assert response.json()["voice_gender_model_loaded"] is True
