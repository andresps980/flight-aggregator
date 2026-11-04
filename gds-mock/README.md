# gds-mock

Placeholder module for the three mock Global Distribution System providers described in
[docs/exercise.md](../docs/exercise.md):

| Provider | Format (planned) |
|---|---|
| Mock-Alpha | raw JSON lists |
| Mock-Beta | deeply nested XML |
| Mock-Gamma | comma-separated string streams |

They will simulate artificial latencies (100 ms – 5000 ms) and occasional HTTP 500 errors. Nothing is implemented
yet; the module only exists so the multi-module build, formatting, coverage and architecture rules are in place.
