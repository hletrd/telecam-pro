"""release_permissions reads bundletool's manifest dump in both attribute spellings."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from release_permissions import (  # noqa: E402
    EXPECTED_RELEASE_PERMISSIONS,
    FORBIDDEN_RELEASE_PERMISSIONS,
    packaged_permission_declarations,
)


def manifest(level: str) -> str:
    return (
        '<manifest><permission android:name="me.hletrd.telecampro.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" '
        f'android:protectionLevel="{level}"/></manifest>'
    )


class PermissionDeclarationTest(unittest.TestCase):
    name = "me.hletrd.telecampro.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

    def test_named_and_numeric_signature_agree(self) -> None:
        # bundletool 1.18 prints the integer form; older releases printed the name.
        for level in ("signature", "0x00000002", "2"):
            self.assertEqual("signature", packaged_permission_declarations(manifest(level))[self.name])

    def test_flagged_levels_never_read_as_plain_signature(self) -> None:
        # 0x12 = signature|privileged: a widened grant must not satisfy the private-guard check.
        self.assertEqual("0x00000012", packaged_permission_declarations(manifest("0x00000012"))[self.name])
        self.assertEqual("normal", packaged_permission_declarations(manifest("0x00000000"))[self.name])


class PlayPolicyPermissionTest(unittest.TestCase):
    def test_media_read_trio_is_forbidden_not_expected(self) -> None:
        # Removed 2026-10-19 under Play's Photo and Video Permissions policy; never re-add.
        trio = {
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        }
        self.assertTrue(trio <= FORBIDDEN_RELEASE_PERMISSIONS)
        self.assertFalse(EXPECTED_RELEASE_PERMISSIONS & FORBIDDEN_RELEASE_PERMISSIONS)
        self.assertEqual(
            {
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO",
                "android.permission.MODIFY_AUDIO_SETTINGS",
            },
            set(EXPECTED_RELEASE_PERMISSIONS),
        )


if __name__ == "__main__":
    unittest.main()
