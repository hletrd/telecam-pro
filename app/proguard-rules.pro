# Keep camera2/EGL/media reflection-touched classes if any are added later.
# v1 release is non-minified; rules kept minimal intentionally.

# STAGED (inert while isMinifyEnabled=false) for the documented future R8 flip:
# SettingsStore round-trips every enum BY NAME (enumValueOf / Enum.name). R8 renames enum constants
# unless kept, which would silently corrupt every persisted setting on the first minified build —
# users would relaunch into defaults with no error. Keep the state enums' names when minifying.
#-keepclassmembers enum com.hletrd.findx9tele.camera.** {
#    public static **[] values();
#    public static ** valueOf(java.lang.String);
#    <fields>;
#}
