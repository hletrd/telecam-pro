"""Closed effective-permission authority for the upload artifact and privacy checks."""

from __future__ import annotations

import re


EXPECTED_RELEASE_PERMISSIONS = frozenset(
    {
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
    }
)

# Permissions that must NEVER reach the packaged manifest. The visual-media READ trio shipped from
# 2026-08-01 for a reinstall gallery restore; Google Play flagged it under the Photo and Video
# Permissions policy (broad photo/video access is reserved for apps whose core purpose needs it, and
# a camera app reviewing its own captures does not qualify), so the owner removed it on 2026-10-19.
# The exact-set check above would already reject them as "unexpected"; this named list makes the
# failure say WHY, and check_docs.py pins the source manifest's tools:node="remove" guards to it.
FORBIDDEN_RELEASE_PERMISSIONS = frozenset(
    {
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
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


# bundletool >= 1.18 prints android:protectionLevel as its integer value ("0x00000002") rather than
# the attribute name ("signature"). Only a bare base level is translated; any added flag bits keep the
# raw value, so a "signature|privileged"-style declaration can never pass as plain "signature".
_PROTECTION_LEVEL_NAMES = {0: "normal", 1: "dangerous", 2: "signature", 3: "signatureOrSystem"}


def normalized_protection_level(value: str) -> str:
    try:
        number = int(value, 0)
    except ValueError:
        return value
    return _PROTECTION_LEVEL_NAMES.get(number, value)


def packaged_permission_declarations(manifest: str) -> dict[str, str]:
    """Return permission declarations and protection levels from bundletool XML output."""
    declarations: dict[str, str] = {}
    for tag in re.findall(r"<permission\b[^>]*>", manifest):
        name = re.search(r'\bandroid:name="([^"]+)"', tag)
        protection = re.search(r'\bandroid:protectionLevel="([^"]+)"', tag)
        if name is not None:
            declarations[name.group(1)] = (
                normalized_protection_level(protection.group(1)) if protection is not None else ""
            )
    return declarations
