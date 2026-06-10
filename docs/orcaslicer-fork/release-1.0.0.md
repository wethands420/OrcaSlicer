# Release 1.0.0

## Summary

This release packages the Windows OrcaSlicer fork with integrated 3D model website browsing and download/import handling.

## Included behavior

- embedded browser entry inside the OrcaSlicer UI
- browsing of supported model websites in-app
- intercepted downloads routed through OrcaSlicer
- distinction between geometry import and project open/import
- ZIP inspection before importing archive contents
- configurable local download directory

## Intended user flow

1. open the model browser inside OrcaSlicer
2. browse a supported model website
3. start a download from the embedded browser
4. let OrcaSlicer inspect the downloaded file
5. choose project import vs. geometry import when applicable
6. continue with the normal OrcaSlicer workflow

## Known limitations

- some providers may change page behavior without notice
- login flows can break when sites change OAuth, CSP, or anti-bot behavior
- JavaScript-heavy sites may require site-specific fixes
- if embedded browsing fails for a site, external browser fallback remains a valid workaround

## Packaging notes

- build artifacts and dependency build directories are intentionally excluded from Git
- release binaries should be attached as GitHub Release assets, not committed into the repository
