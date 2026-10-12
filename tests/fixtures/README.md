# Deterministic sync fixtures

The automated tests create all revision envelopes at runtime with fixed UUIDs and UTC timestamps. They use temporary Linux and Android repository directories and never read or write the user's Downloads directory, KDE Connect configuration, or phone.

The fixtures prove synchronization semantics only. They do not prove Android Storage Access Framework behavior, intent routing, KDE Connect transfer completion, or physical-device convergence.

