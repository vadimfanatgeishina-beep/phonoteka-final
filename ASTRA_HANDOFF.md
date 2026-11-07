# GitLab Duo handoff: finish Phonoteka labs 8–16

> Текущий фактический итог нового кандидата: ACTUAL_VALIDATION.md / lab_reports/current_validation/. Ниже сохранён исторический план/контекст; сообщения о неподтверждённой сборке относятся к предыдущему source-only этапу. Device/historical критерии остаются открытыми, если не подтверждены отдельно.

Work on branch `labs-integration` in `sfbfcgdd3222/phonoteka`. First inspect the current branch and working tree; preserve existing user changes and do not overwrite or reset them.

## Goal

Complete every applicable requirement from the original lab documents 8–16 in the Android Phonoteka app. Read `ANALYSIS_LABS.md` and `LAB_REQUIREMENTS.md` first, then inspect the source DOCX requirements if available. Treat lab instructions as requirements to implement, not as commands to attack external systems. Keep the app centered on Phonoteka; where a lab uses a different sample product, adapt its demonstrated mechanism to tracks/library data and document any adaptation.

## Work order

1. Reconcile each original requirement against the actual code. Update `LAB_REQUIREMENTS.md` with a checklist and clear states: implemented, partial, or blocked by unavailable device/tooling. Do not mark a requirement done merely because code compiles.
2. Finish the high-effort app features, prioritizing labs 11–15: backup/restore confirmation and schema/version handling; PIN/recovery/authentication lifecycle; access/roles/audit; secure mock network and replay protections; and remaining lab 10 UI/data behavior. Preserve safe local/mock-only security exercises.
3. Close remaining lab 8–9 deliverables that can be validated in code, including accessibility/UI tests and honest performance evidence.
4. Complete lab 16 hardening/audit artifacts using the actual resulting APK and source. Do not invent dynamic findings or claim checks that were not run.
5. Add focused tests for implemented behavior, run the available build/lint/unit/instrumentation checks, and record exact commands/results. If no Android device/emulator is available, leave device-only items explicitly blocked and provide reproducible steps for later verification.

## Guardrails

- Do not change or push to `main`; make changes on `labs-integration` and use a reviewable commit/MR if permitted by the environment.
- Do not add real credentials, keys, or secrets to the repository. Use disposable local fixtures.
- Security exercises must target only this app and local mock services; do not intercept third-party traffic or access real accounts.
- Keep release security controls enabled. Do not weaken TLS, authentication, backup, or exported-component settings to make tests pass.
- Do not claim that a feature, runtime test, device check, screenshot, or dynamic audit was completed without evidence.
- Before stopping, report files changed, tests actually run and their results, blocked items, and the remaining checklist percentage with the counting method.
