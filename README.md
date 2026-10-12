# ReasonNotes

Shared Forge/Qwen engineering project.

## Workspace ownership

- Forge: /home/reason/Codex-Projects/ReasonNotes
- Qwen: /home/reason/Temporary/QwenProjects/ReasonNotes
- Neutral exchange: /home/reason/Temporary/AgentHandoff/ReasonNotes.git

Use the handoff Git remote for agent-to-agent transfer.
Do not copy working directories between Forge and Qwen.

## Milestone 1 prototype

The current branch contains a dependency-free Python synchronization core, Linux KDE Connect adapter, and minimal native Android probe. Automated evidence is documented in `docs/milestone1/AUTOMATED_EVIDENCE.md`; the physical procedure and completed results are in `docs/milestone1/PHONE_TEST_PROCEDURE.md` and `docs/milestone1/PHONE_TEST_RESULTS.md`.

Physical testing verified direct two-way KDE Connect exchange, automatic directory discovery on user sync, exact convergence, explicit conflict choices, immutable history retention, and bounded retry behavior. KDE Connect is recommended for Milestone 2, with a small ReasonNotes import-receipt envelope required before delivery can be presented as confirmed.
