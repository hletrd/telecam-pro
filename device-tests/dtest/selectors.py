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

    def all_labels(self) -> tuple[str, ...]:
        return tuple(label for language in sorted(self.descriptions) for label in self.descriptions[language])


def selector(identity: str, english: str | tuple[str, ...], korean: str | tuple[str, ...]) -> Selector:
    """Declare one stable UI identity with exact labels for both supported languages."""
    en = (english,) if isinstance(english, str) else english
    ko = (korean,) if isinstance(korean, str) else korean
    return Selector(identity, {"en": en, "ko": ko})


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
RECORDING = selector("recording", "Recording", "녹화 중")
CLOSE_SETTINGS = selector("close_settings", "Close settings", "설정 닫기")
CLOSE_FUNCTION_MENU = selector("close_function_menu", "Close function menu", "기능 메뉴 닫기")
CLOSE_ADJUSTMENT = selector("close_adjustment", "Close adjustment", "조정 닫기")
SWITCH_CAMERA = selector("switch_camera", "Switch camera", "카메라 전환")
RESET_FOCUS_POINT = selector("reset_focus_point", "Reset focus point", "초점 지점 초기화")
TAKE_PHOTO_WHILE_RECORDING = selector(
    "take_photo_while_recording", "Take photo while recording", "녹화 중 사진 촬영"
)
TELECONVERTER = selector("teleconverter", "Teleconverter", "텔레컨버터")
LOUPE_OVERVIEW = selector("loupe_overview", "Loupe overview", "루페 오버뷰")
SHOW_SHOOTING_INFO = selector("show_shooting_info", "Show shooting info", "촬영 정보 표시")
HIDE_SHOOTING_INFO = selector("hide_shooting_info", "Hide shooting info", "촬영 정보 숨기기")
FLASH = selector("flash", "Flash", "플래시")
SELF_TIMER = selector("self_timer", "Self-timer", "셀프타이머")
ASPECT_RATIO = selector("aspect_ratio", "Aspect ratio", "화면비")
GRID = selector("grid", "Grid", "그리드")
GALLERY = selector(
    "gallery",
    (
        "Find previous captures",
        "Review last RAW capture",
        "Review last video",
        "Review last photo",
        "Review last capture",
    ),
    (
        "이전 촬영 찾기",
        "마지막 RAW 촬영 보기",
        "마지막 동영상 보기",
        "마지막 사진 보기",
        "마지막 촬영 보기",
    ),
)
GAMMA = selector("gamma", "Gamma", "감마")
ISO = selector("iso", "ISO", "ISO")
SHUTTER_SPEED = selector("shutter_speed", "Shutter speed", "셔터 속도")

SETTINGS_TABS = (
    selector("settings_my", "My", "내 메뉴"),
    selector("settings_shoot", "Shoot", "촬영"),
    selector("settings_exposure", "Exposure", "노출"),
    selector("settings_focus", "Focus", "초점"),
    selector("settings_lens", "Lens", "렌즈"),
    selector("settings_video", "Video", "동영상"),
    selector("settings_image", "Image", "이미지"),
    selector("settings_assist", "Assist", "보조 기능"),
    selector("settings_setup", "Setup", "설정"),
)

SETTINGS_PAGE_TITLES = (
    selector("page_my_menu", "My Menu", "내 메뉴"),
    selector("page_shoot", "Shoot", "촬영"),
    selector("page_exposure", "Exposure", "노출"),
    selector("page_focus", "Focus", "초점"),
    selector("page_lens", "Lens", "렌즈"),
    selector("page_video", "Video", "동영상"),
    selector("page_image", "Image", "이미지"),
    selector("page_assist", "Assist", "보조 기능"),
    selector("page_setup", "Setup", "설정"),
)

PHOTO_SETTING_SELECTORS = {
    "Single": selector("drive_single", "Single", "단일 촬영"),
    "Burst": selector("drive_burst", "Burst", "연속 촬영"),
    "AEB": selector("drive_aeb", "AEB", "노출 브래킷"),
    "Timelapse": selector("drive_timelapse", "Timelapse", "타임랩스"),
    "Off": selector("timer_off", "Off", "꺼짐"),
    "3s": selector("timer_3s", "3s", "3초"),
    "10s": selector("timer_10s", "10s", "10초"),
}


def lens_preset(label: str) -> Selector:
    """A preset may be an optical lens or a digital zoom, depending on enumerated hardware."""
    return selector(
        f"lens_preset_{label}",
        (f"{label} lens", f"{label} zoom"),
        (f"{label} 렌즈", f"{label} 줌"),
    )


FOCAL_PRESETS = tuple(lens_preset(label) for label in ("0.6×", "1×", "3×", "10×"))

FN_TILES = {
    "AE": selector("fn_ae", "AE", "AE"),
    "Focus": selector("fn_focus", "Focus", "초점"),
    "Shutter": selector("fn_shutter", "Shutter", "셔터"),
    "ISO": ISO,
    "WB": selector("fn_wb", "WB", "WB"),
    "EV": selector("fn_ev", "EV", "EV"),
    "Zoom": selector("fn_zoom", "Zoom", "줌"),
    "Stabilization": selector("fn_stabilization", "Stabilization", "손떨림 보정"),
    "Drive": selector("fn_drive", "Drive", "드라이브"),
    "Meter": selector("fn_meter", "Meter", "측광"),
    "Peaking": selector("fn_peaking", "Peaking", "피킹"),
    "Zebra": selector("fn_zebra", "Zebra", "제브라"),
    "Gamma": GAMMA,
    "Directionality": selector("fn_directionality", "Directionality", "지향성"),
    "Grid": selector("fn_grid", "Grid", "그리드"),
    "Level": selector("fn_level", "Level", "수평계"),
    "Loupe": selector("fn_loupe", "Loupe", "루페"),
    "Tele": selector("fn_tele", "Tele", "텔레"),
    "Open Gate": selector("fn_open_gate", "Open Gate", "Open Gate"),
    "Frame": selector("fn_frame", "Frame", "프레임"),
}

SMOKE_SELECTORS = (
    OPEN_SETTINGS,
    OPEN_FUNCTION_MENU,
    PHOTO_MODE,
    VIDEO_MODE,
    TAKE_PHOTO,
    START_RECORDING,
)

_FULL_ACTION_SELECTOR_CANDIDATES = (
    *SMOKE_SELECTORS,
    STOP_RECORDING,
    RECORDING,
    CLOSE_SETTINGS,
    CLOSE_FUNCTION_MENU,
    CLOSE_ADJUSTMENT,
    SWITCH_CAMERA,
    RESET_FOCUS_POINT,
    TAKE_PHOTO_WHILE_RECORDING,
    TELECONVERTER,
    LOUPE_OVERVIEW,
    SHOW_SHOOTING_INFO,
    HIDE_SHOOTING_INFO,
    FLASH,
    SELF_TIMER,
    ASPECT_RATIO,
    GRID,
    GALLERY,
    GAMMA,
    ISO,
    SHUTTER_SPEED,
    *SETTINGS_TABS,
    *SETTINGS_PAGE_TITLES,
    *PHOTO_SETTING_SELECTORS.values(),
    *FOCAL_PRESETS,
    *FN_TILES.values(),
)
FULL_ACTION_SELECTORS = tuple(
    {item.identity: item for item in _FULL_ACTION_SELECTOR_CANDIDATES}.values()
)
