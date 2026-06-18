import logging
import threading
import time
from pathlib import Path
from typing import Any

import numpy as np

log = logging.getLogger("speaker-service.voice_gender")


class VoiceGenderDetector:
    def __init__(
        self,
        enabled: bool,
        model_dir: str,
        min_seconds: float,
        max_seconds: float,
        confidence: float,
        margin: float,
        labels: str,
        num_threads: int,
    ):
        self.enabled = enabled
        self.model_dir = Path(model_dir)
        self.min_seconds = min_seconds
        self.max_seconds = max_seconds
        self.confidence = confidence
        self.margin = margin
        self.labels = [item.strip().lower() for item in labels.split(",") if item.strip()]
        self.num_threads = num_threads
        self._model: Any | None = None
        self._session: Any | None = None
        self._input_names: list[str] = []
        self._output_names: list[str] = []
        self._lock = threading.Lock()

    @property
    def loaded(self) -> bool:
        return self._model is not None or self._session is not None

    def load(self) -> None:
        if not self.enabled:
            log.info("[voice-gender] disabled")
            return
        if not self.model_dir.exists():
            log.warning("[voice-gender] model dir not found: %s", self.model_dir)
            return
        started = time.time()
        try:
            import audonnx  # type: ignore

            self._model = audonnx.load(str(self.model_dir))
            elapsed_ms = int((time.time() - started) * 1000)
            log.info("[voice-gender] audonnx model loaded from %s, elapsed=%dms", self.model_dir, elapsed_ms)
            return
        except Exception as exc:
            log.warning("[voice-gender] audonnx load failed: %s; trying onnxruntime fallback", exc)

        model_path = self.model_dir / "model.onnx"
        if not model_path.exists():
            log.warning("[voice-gender] model.onnx not found under %s", self.model_dir)
            return
        try:
            import onnxruntime as ort

            sess_options = ort.SessionOptions()
            sess_options.intra_op_num_threads = max(1, self.num_threads)
            sess_options.inter_op_num_threads = 1
            self._session = ort.InferenceSession(
                str(model_path),
                sess_options=sess_options,
                providers=["CPUExecutionProvider"],
            )
            self._input_names = [item.name for item in self._session.get_inputs()]
            self._output_names = [item.name for item in self._session.get_outputs()]
            elapsed_ms = int((time.time() - started) * 1000)
            log.info("[voice-gender] onnxruntime model loaded from %s, elapsed=%dms", model_path, elapsed_ms)
        except Exception as exc:
            log.warning("[voice-gender] onnxruntime load failed: %s", exc)
            self._session = None

    def predict(self, pcm_data: bytes, sample_rate: int) -> dict[str, Any]:
        started = time.time()
        if not self.loaded:
            return self._unknown(started, model_available=False)
        signal = self._pcm16le_to_float32(pcm_data)
        duration_seconds = len(signal) / float(sample_rate) if sample_rate else 0.0
        if duration_seconds < self.min_seconds:
            result = self._unknown(started, model_available=True)
            result["reason"] = "too_short"
            return result
        max_samples = int(sample_rate * self.max_seconds)
        if max_samples > 0 and len(signal) > max_samples:
            signal = signal[-max_samples:]

        try:
            with self._lock:
                outputs = self._infer(signal, sample_rate)
            scores = self._extract_scores(outputs)
            gender, confidence, male_score, female_score, child_score = self._decide(scores)
            latency_ms = round((time.time() - started) * 1000, 2)
            return {
                "gender": gender,
                "confidence": confidence,
                "male_score": male_score,
                "female_score": female_score,
                "child_score": child_score,
                "latency_ms": latency_ms,
                "model_available": True,
            }
        except Exception as exc:
            log.warning("[voice-gender] inference failed: %s", exc)
            return self._unknown(started, model_available=False)

    def _infer(self, signal: np.ndarray, sample_rate: int) -> Any:
        if self._model is not None:
            return self._model(signal, sample_rate)
        if self._session is None:
            raise RuntimeError("voice gender model is not loaded")
        feeds: dict[str, Any] = {}
        for input_name in self._input_names:
            lower = input_name.lower()
            if "rate" in lower or "sampling" in lower:
                feeds[input_name] = np.array(sample_rate, dtype=np.int64)
            else:
                feeds[input_name] = signal[np.newaxis, :].astype(np.float32)
        values = self._session.run(None, feeds)
        return dict(zip(self._output_names, values))

    def _extract_scores(self, outputs: Any) -> dict[str, float]:
        if isinstance(outputs, dict):
            for key, value in outputs.items():
                if "gender" in key.lower():
                    return self._scores_from_array(value)
            for value in outputs.values():
                scores = self._scores_from_array(value)
                if scores:
                    return scores
            raise RuntimeError("no gender output found")
        return self._scores_from_array(outputs)

    def _scores_from_array(self, value: Any) -> dict[str, float]:
        array = np.asarray(value, dtype=np.float32).squeeze()
        if array.ndim != 1 or array.size < 2:
            return {}
        if float(array.min()) < 0.0 or float(array.max()) > 1.0 or not np.isclose(float(array.sum()), 1.0, atol=0.05):
            array = self._softmax(array)
        labels = self.labels
        if len(labels) != array.size:
            labels = ["child", "female", "male"] if array.size == 3 else ["female", "male"]
        return {labels[index]: float(array[index]) for index in range(min(len(labels), array.size))}

    def _decide(self, scores: dict[str, float]) -> tuple[str, float, float, float, float]:
        male_score = float(scores.get("male", 0.0))
        female_score = float(scores.get("female", 0.0))
        child_score = float(scores.get("child", 0.0))
        if male_score >= female_score:
            gender = "male"
            confidence = male_score
        else:
            gender = "female"
            confidence = female_score
        if confidence < self.confidence or abs(male_score - female_score) < self.margin or child_score > confidence:
            gender = "unknown"
        return gender, round(confidence, 4), round(male_score, 4), round(female_score, 4), round(child_score, 4)

    @staticmethod
    def _pcm16le_to_float32(pcm_data: bytes) -> np.ndarray:
        if not pcm_data:
            return np.array([], dtype=np.float32)
        usable = len(pcm_data) - (len(pcm_data) % 2)
        int_samples = np.frombuffer(pcm_data[:usable], dtype="<i2")
        return int_samples.astype(np.float32) / 32768.0

    @staticmethod
    def _softmax(values: np.ndarray) -> np.ndarray:
        shifted = values - np.max(values)
        exp = np.exp(shifted)
        return exp / np.sum(exp)

    @staticmethod
    def _unknown(started: float, model_available: bool) -> dict[str, Any]:
        return {
            "gender": "unknown",
            "confidence": 0.0,
            "male_score": 0.0,
            "female_score": 0.0,
            "child_score": 0.0,
            "latency_ms": round((time.time() - started) * 1000, 2),
            "model_available": model_available,
        }
