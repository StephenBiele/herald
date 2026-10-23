#!/usr/bin/env python3
"""Prepare reproducible APK inputs for Herald's on-phone Zoey voice."""
from pathlib import Path
import hashlib
import os
from urllib.request import urlopen
import shutil

DEST = Path(__file__).resolve().parents[2] / "androidApp/src/main/assets/phonon"
SOURCES = {
    "model": "https://huggingface.co/gradium/phonon-31m-en/resolve/92741c5b07d085ff840e26fea8ce43110b87852f/",
    "voice": "https://huggingface.co/gradium/phonon-voices/resolve/0ee17205f254b5abc43b266c8a0f215565c352f7/",
}
ASSETS = [
    ("model", "model.q8.gguf", 64512352, "a7d9aad2cd6dc66473f24a08bc6d2939030ef4d751ef82349361fbef3272dbae"),
    ("model", "config.json", 4634, "b5d0f88353638370d94035b7d73d7d6577c0554d78f8d7ecfd40490aae39ae85"),
    ("model", "tokenizer.json", 277967, "691a00068fa7b989f2f3e5ae5daa2d1a5bb319f2d5360deafcfb19a8bb6e7936"),
    ("voice", "voices/NbpkqMVS3CJeq2j8.safetensors", 256136, "97c7f8c253413574406ab80dd23f49c3ebb2517163b00a9bf86b9c4cf43e7e23"),
]


def valid(path, size, digest):
    if not path.is_file() or path.stat().st_size != size:
        return False
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest() == digest


def prepare():
    for source, relative, size, digest in ASSETS:
        target = DEST / relative
        if not valid(target, size, digest):
            target.parent.mkdir(parents=True, exist_ok=True)
            staging = target.with_name(target.name + ".download")
            try:
                with urlopen(SOURCES[source] + relative, timeout=90) as response, staging.open("wb") as stream:
                    shutil.copyfileobj(response, stream)
                if not valid(staging, size, digest):
                    raise ValueError(f"Asset verification failed: {relative}")
                os.replace(staging, target)
            finally:
                staging.unlink(missing_ok=True)
        print(f"Verified {relative}")
    upstream = "https://raw.githubusercontent.com/gradium-ai/xn-ptts/49b9033e44ab0fbb935973eca3213757644d1bbb/"
    for license_file in ["LICENSE-MIT", "LICENSE-APACHE"]:
        with urlopen(upstream + license_file, timeout=30) as response:
            (DEST / license_file).write_bytes(response.read())
    (DEST / "ATTRIBUTION.txt").write_text(
        "Phonon runtime: gradium-ai/xn-ptts 49b9033e44ab0fbb935973eca3213757644d1bbb (MIT OR Apache-2.0).\n"
        "Model: gradium/phonon-31m-en 92741c5b07d085ff840e26fea8ce43110b87852f (Apache-2.0).\n"
        "Zoey: gradium/phonon-voices 0ee17205f254b5abc43b266c8a0f215565c352f7, NbpkqMVS3CJeq2j8 (Apache-2.0).\n"
        "JNA 5.17.0: Java Native Access contributors (Apache-2.0 option).\n"
    )


if __name__ == "__main__":
    prepare()
