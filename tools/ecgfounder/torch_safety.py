"""Fail-closed checks for loading PyTorch checkpoint files."""

from __future__ import annotations

import re

MIN_SAFE_TORCH_VERSION = (2, 10, 0)
_STABLE_VERSION = re.compile(
    r"(\d+)\.(\d+)\.(\d+)(?:\+[0-9A-Za-z][0-9A-Za-z._-]*)?"
)


def require_safe_torch_version(version: object) -> None:
    """Reject versions affected by unsafe ``weights_only`` deserialization."""
    if not isinstance(version, str):
        raise RuntimeError(
            "cannot determine the PyTorch version; "
            "install a stable PyTorch release >= 2.10.0"
        )

    match = _STABLE_VERSION.fullmatch(version)
    if match is None:
        raise RuntimeError(
            f"cannot verify PyTorch version {version!r}; "
            "install a stable PyTorch release >= 2.10.0"
        )

    parsed = tuple(int(part) for part in match.groups())
    if parsed < MIN_SAFE_TORCH_VERSION:
        raise RuntimeError(
            f"PyTorch {version} is unsafe for checkpoint loading; "
            "install PyTorch >= 2.10.0"
        )
