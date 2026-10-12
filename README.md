# ReasonNotes

Shared Forge/Qwen engineering project.

## Workspace ownership

- Forge: /home/reason/Codex-Projects/ReasonNotes
- Qwen: /home/reason/Temporary/QwenProjects/ReasonNotes
- Neutral exchange: /home/reason/Temporary/AgentHandoff/ReasonNotes.git

Use the handoff Git remote for agent-to-agent transfer.
Do not copy working directories between Forge and Qwen.

## Milestone 1 prototype

The current branch contains a dependency-free Python synchronization core, Linux KDE Connect adapter, and minimal native Android probe. Automated evidence is documented in `docs/milestone1/AUTOMATED_EVIDENCE.md`; the exact pending hardware sequence is in `docs/milestone1/PHONE_TEST_PROCEDURE.md`.

Android runtime behavior and bidirectional convergence are not yet verified. Do not treat the successful host build or prior manual file transfer as synchronization proof.
