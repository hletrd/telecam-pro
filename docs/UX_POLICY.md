# UX policy

Use Sony Alpha and Xperia Pro camera screens as the reference. Not generic phone-camera UI.

## Rules

- Keep the viewfinder quiet.
- Use Fn, My Menu, MR banks, PASM-style exposure, compact OSD, peaking, zebra, histogram, waveform,
  and review zoom.
- Do not add tutorial banners, warning chips, coach marks, marketing cards, or helper overlays unless
  the user asks.
- Important states belong in the OSD, Fn, or a menu row.
- Start in a preview-first compact state. `DISP` reveals detailed status and the full Fn strip;
  compact mode keeps only active or output-changing state plus the Fn entry point.
- Keep MR save/recall in Shooting/My Menu. At rest, show only the active MR slot in the OSD.
- Keep every interactive touch target at least 48 dp; compact glyphs may remain visually smaller
  inside that hit region.
- If it would look odd on a Sony camera screen, do not ship it.

## Examples

- Good: `STEADY` in the OSD, Fn toggle for stabilization, menu rows for video format locks.
- Good: move the exposure meter away from an opened control ruler.
- Bad: `300mm WARN`, warning pills, large explanatory overlays, or helper copy over the viewfinder.
