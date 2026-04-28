# Hooks for the Ai Pin's base software

A system-injected set of hooks for the Humane OS and user facing apps on the Ai Pin. It redirects Humane API calls to a local server and cleans up things like telemetry and log spam.

The repo has three main parts:

- `injector` - Selectively inject hooks into specified processes
- `hook` — The actual hooks, split out per app/functionality
- `server` — Reimplementation of cosmOS gRPC backend
