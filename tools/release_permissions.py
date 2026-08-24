"""Closed effective-permission authority for the upload artifact and privacy checks."""

from __future__ import annotations

import re


EXPECTED_RELEASE_PERMISSIONS = frozenset(
    {
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
    }
)


def packaged_permissions(manifest: str) -> frozenset[str]:
    """Extract every effective uses-permission name from bundletool's manifest dump."""
    return frozenset(
        re.findall(
            r'<uses-permission(?:-sdk-\d+)?\b[^>]*\bandroid:name="([^"]+)"',
            manifest,
        )
    )
