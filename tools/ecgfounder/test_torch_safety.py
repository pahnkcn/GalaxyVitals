from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from torch_safety import require_safe_torch_version  # noqa: E402


class TorchSafetyTest(unittest.TestCase):
    def test_accepts_safe_stable_and_local_versions(self) -> None:
        require_safe_torch_version("2.10.0")
        require_safe_torch_version("2.10.0+cpu")
        require_safe_torch_version("2.11.1+cu128")

    def test_rejects_affected_version(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "unsafe"):
            require_safe_torch_version("2.9.1")

    def test_rejects_missing_or_unverifiable_versions(self) -> None:
        for version in (None, "", "2.10", "2.10.0rc1", "2.10.0+"):
            with self.subTest(version=version):
                with self.assertRaisesRegex(RuntimeError, "cannot (determine|verify)"):
                    require_safe_torch_version(version)


if __name__ == "__main__":
    unittest.main()
