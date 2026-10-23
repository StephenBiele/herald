import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import prepare_phonon_pack as pack


class PackIntegrityTest(unittest.TestCase):
    def test_corrupt_download_never_becomes_an_apk_asset(self):
        expected = hashlib.sha256(b"model").hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(pack, "DEST", root), patch.object(
                pack, "ASSETS", [("model", "model.q8.gguf", 5, expected)]
            ), patch.object(pack, "urlopen", return_value=io.BytesIO(b"wrong")):
                with self.assertRaises(ValueError):
                    pack.prepare()
            self.assertEqual([], list(root.iterdir()))

    def test_existing_verified_model_is_reused_and_attribution_is_included(self):
        expected = hashlib.sha256(b"model").hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "model.q8.gguf").write_bytes(b"model")
            with patch.object(pack, "DEST", root), patch.object(
                pack, "ASSETS", [("model", "model.q8.gguf", 5, expected)]
            ), patch.object(pack, "urlopen", side_effect=lambda *args, **kwargs: io.BytesIO(b"license")) as fetch:
                pack.prepare()
            self.assertEqual(2, fetch.call_count)  # Licenses only, no model download.
            self.assertEqual(b"model", (root / "model.q8.gguf").read_bytes())
            self.assertIn("NbpkqMVS3CJeq2j8", (root / "ATTRIBUTION.txt").read_text())


if __name__ == "__main__":
    unittest.main()
