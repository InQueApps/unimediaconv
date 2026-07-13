# Documentation (canonical location)

Planning, product specs, QA matrices, and the Cursor skill for this app live **outside the repo**:

**Canonical locations:**

- `C:\Users\inque\OneDrive\Desktop\Work\skills\unimediaconv\` (full set)
- `C:\Users\inque\OneDrive\Desktop\Work\code\skills\unimediaconv\` (learnings + refactor plan + index)

| Document | Purpose |
|----------|---------|
| `SKILL.md` | Cursor agent context — read first |
| `REFERENCE.md` | Deep technical reference |
| `SESSION_LEARNINGS.md` | Recent bugs, features, test pitfalls (Jul 2026) |
| `REFACTOR_PLAN.md` | Lean-up refactoring phases R0–R5 |
| `PREVIEW_PLAN.md` | Live preview feature plan |
| `FEATURES.md` | Product specifications |
| `FEATURES_MATRIX.md` | Technical conversion matrix (auto-generated) |
| `GAPS.md` | Open gaps (auto-generated) |
| `PLAN.md` | Implementation roadmap |
| `TEST_PLAN.md` | Full QA plan |
| `GEOMETRY_FIX_PLAN.md` | Geometry fix tracking |
| `GEOMETRY_TEST_PLAN.md` | Round-trip / geometry tests |
| `CONVERSION_ANALYSIS.md` | Conversion analysis notes |
| `YOUTUBE_ANALYSIS.md` | Link / YouTube import notes |

## Regenerate auto-docs

From repo root:

```powershell
.\gradlew :app:testDebugUnitTest --tests ConversionFeaturesDocGeneratorTest
.\gradlew :app:testDebugUnitTest --tests GapsDocGeneratorTest
```

Output writes to `Work/skills/unimediaconv/` (override with env `UNIMEDIACONV_SKILLS_DIR`).

## Run all tests

```powershell
.\scripts\run_all_tests.ps1
```
