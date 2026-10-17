# ReasonNotes

Cross-device notes project built around a native Linux desktop experience, an Android client, and controlled synchronization between them.

The repository includes note storage/editing, Android UI work, selective sync, delivery/receipt tracking, conflict-safe receive behavior, performance work, branding/icons, and the desktop Liquid Glass material.

## Branch structure

Implementation is intentionally preserved across several namespaces:

- `forge/*` — larger Forge milestones;
- `qwen/*` — Qwen validation/acceptance work;
- `sol/*` — focused desktop/Android fixes and polish checkpoints.

`main` is the collaboration baseline; substantial feature work remains on the engineering branches rather than being flattened into synthetic history.

Active engineering project, not a packaged public release.
