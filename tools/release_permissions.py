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

PACKAGE_PRIVATE_PERMISSION_SUFFIX = ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"


def expected_packaged_permissions(application_id: str) -> frozenset[str]:
    """Exact effective set: disclosed runtime access plus AndroidX's signature-only guard."""
    return EXPECTED_RELEASE_PERMISSIONS | {application_id + PACKAGE_PRIVATE_PERMISSION_SUFFIX}


def packaged_permissions(manifest: str) -> frozenset[str]:
    """Extract every effective uses-permission name from bundletool's manifest dump."""
    return frozenset(
        re.findall(
            r'<uses-permission(?:-sdk-\d+)?\b[^>]*\bandroid:name="([^"]+)"',
            manifest,
        )
    )


def packaged_permission_declarations(manifest: str) -> dict[str, str]:
    """Return permission declarations and protection levels from bundletool XML output."""
    declarations: dict[str, str] = {}
    for tag in re.findall(r"<permission\b[^>]*>", manifest):
        name = re.search(r'\bandroid:name="([^"]+)"', tag)
        protection = re.search(r'\bandroid:protectionLevel="([^"]+)"', tag)
        if name is not None:
            declarations[name.group(1)] = protection.group(1) if protection is not None else ""
    return declarations
