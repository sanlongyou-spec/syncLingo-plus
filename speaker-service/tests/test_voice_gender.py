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


def test_health_reports_voice_gender_model_loaded(monkeypatch):
    monkeypatch.setattr(main, "voice_gender_detector", FakeDetector())
    client = TestClient(main.app)

    response = client.get("/health")

    assert response.status_code == 200
    assert response.json()["voice_gender_model_loaded"] is True
