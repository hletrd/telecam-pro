"""Locale-aware stable semantic identities for shell-driven smoke checks."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Selector:
    identity: str
    descriptions: dict[str, tuple[str, ...]]

    def labels_for(self, locale: str) -> tuple[str, ...]:
        language = locale.replace("_", "-").split("-", 1)[0].casefold()
        try:
            return self.descriptions[language]
        except KeyError as error:
            raise ValueError(f"unsupported smoke locale {locale!r} for {self.identity}") from error


OPEN_SETTINGS = Selector(
    "open_settings",
    {"en": ("Open settings",), "ko": ("설정 열기",)},
)
OPEN_FUNCTION_MENU = Selector(
    "open_function_menu",
    {"en": ("Open function menu",), "ko": ("기능 메뉴 열기",)},
)
PHOTO_MODE = Selector(
    "photo_mode",
    {"en": ("Photo mode",), "ko": ("사진 모드",)},
)
VIDEO_MODE = Selector(
    "video_mode",
    {"en": ("Video mode",), "ko": ("동영상 모드",)},
)
TAKE_PHOTO = Selector(
    "take_photo",
    {"en": ("Take photo",), "ko": ("사진 촬영",)},
)
START_RECORDING = Selector(
    "start_recording",
    {"en": ("Start recording",), "ko": ("녹화 시작",)},
)
STOP_RECORDING = Selector(
    "stop_recording",
    {"en": ("Stop recording",), "ko": ("녹화 중지",)},
)

SMOKE_SELECTORS = (
    OPEN_SETTINGS,
    OPEN_FUNCTION_MENU,
    PHOTO_MODE,
    VIDEO_MODE,
    TAKE_PHOTO,
    START_RECORDING,
)
