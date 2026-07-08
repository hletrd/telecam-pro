# UI/UX Policy

TeleCam Pro uses **Sony Alpha / Sony Xperia Pro camera operation** as the reference, not generic
phone-camera helper UI.

## Product Rules

- Keep the viewfinder quiet. Controls should be compact, status-like, and easy to ignore while
  framing.
- Prefer pro-camera patterns: Fn, My Menu, MR banks, PASM-style exposure, exposure meter, OSD status
  rows, peaking, zebra, histogram, waveform, and review zoom.
- Do not add tutorial banners, safety nags, warning chips, marketing cards, coach marks, or
  "helpful" overlays unless the user explicitly asks for them.
- If a condition matters, expose it as a normal camera state or menu row. Do not invent conspicuous
  alert UI for it.
- Any new UI must be judged against whether it would look plausible on a Sony pro camera screen.

## Practical Examples

- Good: `STEADY` in the OSD, Fn toggle for stabilization, menu rows for video format locks.
- Good: exposure meter moves away from an opened control ruler instead of covering it.
- Bad: `300mm WARN` banners, warning pills, large explanatory overlays, or helper copy over the
  viewfinder.
