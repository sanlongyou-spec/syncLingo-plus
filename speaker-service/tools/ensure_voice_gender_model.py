import os
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path


DEFAULT_MODEL_DIR = "models/wav2vec2-large-robust-6-ft-age-gender"
DEFAULT_MODEL_URL = (
    "https://zenodo.org/records/7761387/files/"
    "w2v2-L-robust-6-age-gender.25c844af-1.1.1.zip?download=1"
)


def main() -> int:
    enabled = os.getenv("VOICE_GENDER_ENABLED", "true").lower() == "true"
    if not enabled:
        print("[voice-gender] disabled, skip model check")
        return 0

    root = Path(__file__).resolve().parents[1]
    model_dir = Path(os.getenv("VOICE_GENDER_MODEL_DIR", DEFAULT_MODEL_DIR))
    if not model_dir.is_absolute():
        model_dir = root / model_dir

    model_path = model_dir / "model.onnx"
    if model_path.exists():
        print(f"[voice-gender] model found: {model_dir}")
        return 0

    model_url = os.getenv("VOICE_GENDER_MODEL_URL", DEFAULT_MODEL_URL)
    archive_path = model_dir / "voice-gender-model.zip"
    extract_dir = model_dir / ".extract"
    print(f"[voice-gender] model.onnx not found, downloading ONNX model to {model_dir}")
    model_dir.mkdir(parents=True, exist_ok=True)
    if extract_dir.exists():
        shutil.rmtree(extract_dir)

    urllib.request.urlretrieve(model_url, archive_path)
    with zipfile.ZipFile(archive_path) as archive:
        archive.extractall(extract_dir)

    extracted_model = next(extract_dir.rglob("model.onnx"), None)
    if extracted_model is None:
        print("[voice-gender] downloaded archive is incomplete, missing model.onnx", file=sys.stderr)
        return 1

    source_dir = extracted_model.parent
    for item in source_dir.iterdir():
        target = model_dir / item.name
        if item.is_dir():
            shutil.copytree(item, target, dirs_exist_ok=True)
        else:
            shutil.copy2(item, target)

    archive_path.unlink(missing_ok=True)
    shutil.rmtree(extract_dir, ignore_errors=True)

    if not model_path.exists():
        print(f"[voice-gender] downloaded model is incomplete, missing {model_path}", file=sys.stderr)
        return 1

    print(f"[voice-gender] model ready: {model_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
