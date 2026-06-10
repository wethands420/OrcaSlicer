# Desktop Fork Browser Integration

## Purpose

This document describes the embedded model-browser feature in the Windows OrcaSlicer fork.

## Current Scope

The fork currently provides:

- embedded browser entry inside the OrcaSlicer UI
- browsing of model websites in the desktop app
- download interception
- import routing into existing Orca import flows
- project-vs-geometry handling
- ZIP inspection for archive downloads
- configurable download-directory behavior

## Preferred Integration Strategy

The preferred desktop architecture remains generic download interception:

1. user browses normally
2. embedded browser detects a download
3. application-controlled download path saves the file
4. file type is inspected
5. import intent is chosen when needed
6. existing Orca import logic is reused

This remains more robust than deep site automation.

## Supported Import Intent

Desktop workflow should continue to distinguish:

- geometry import
- project open/import

This is especially important for `3MF`.

If a file may contain project state, the user should choose how to handle it rather than silently applying assumptions.

## ZIP Handling

ZIP archives should continue to be treated as inspectable containers:

- inspect contents
- detect supported file types
- let the user choose what to import
- avoid silent bulk import

## Fragile Areas

These remain technically or legally fragile:

- provider login flows
- OAuth-based login
- JavaScript-heavy page behavior
- anti-bot protections
- provider redesigns that change download triggers

If site-specific behavior breaks, acceptable fallbacks include:

- generic download interception where possible
- opening the URL in the external browser when required

## Upstream-Friendly vs. Fork-Specific

### More Upstream-Friendly

- generic embedded browser shell
- generic download interception
- project-vs-geometry confirmation
- ZIP inspection by file contents
- reuse of existing Orca import logic

### More Fork-Specific

- curated model-site shortcuts
- site-specific browser workarounds
- product-specific discovery UX

## Rule for Future Work

1. Keep the generic desktop download path working first.
2. Reuse Orca import logic instead of duplicating file handlers.
3. Treat site-specific compatibility fixes as optional layers.
