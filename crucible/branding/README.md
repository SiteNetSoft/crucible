# CrucibleVM brand assets

| File | Use |
|---|---|
| `logo-mark.svg` | Primary mark. Square, transparent, reads down to 16 px. Use for favicons, avatars, and anywhere the name already appears next to it. |
| `logo-mark-mono.svg` | Single-colour mark. Inherits `currentColor`, so it takes the surrounding text colour. Use on busy backgrounds, in print, and where the palette would clash. |
| `logo.svg` | Horizontal lockup, mark plus name. Use in page headers and READMEs. |
| `logo-readme.svg` | The lockup with the name converted to paths, so it renders the same everywhere, and an ALPHA tag in the molten colours. 352 x 64, shown at one and a half times that at the top of the repository README. Drop the tag by regenerating from `logo.svg` once the project leaves alpha. |

## The mark

A crucible with the profile rising out of the melt. The three bars are the thing CrucibleVM
actually produces — branch execution counts, ascending the way a skewed branch does in the sample
profile. They are deliberately not an arrow: the project measures, it does not promise.

The vessel is one flat silhouette so the mark stays readable when it is 16 px in a browser tab.
Only the molten metal carries the gradient; everything else is flat.

## Palette

| Token | Hex | Role |
|---|---|---|
| Iron | `#44557A` | Vessel, rim, foot. A mid slate so a single file holds up on both light and dark backgrounds — check any change against both. |
| Molten, hot | `#FFD24A` | Top of the gradient, the hottest metal. |
| Molten, mid | `#FF8A1F` | Middle stop. |
| Molten, deep | `#E0402A` | Bottom of the gradient, the coolest metal. |

## Wordmark

The lockup sets the name in a system sans stack, so it renders without a web font but will differ
slightly between machines. Before using the lockup somewhere fixed — a social preview image, print,
a site header — convert the text to paths:

    inkscape crucible/branding/logo.svg --export-text-to-path -o logo-outlined.svg

## Clear space and minimum size

Keep clear space around the mark equal to the height of its rim. Minimum size is 16 px for the
mark and 120 px wide for the lockup; below that use the mark alone.
