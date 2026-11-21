# Live Widgets v2 — implementation plan

## Baseline and safety

- Base this work on PR #67 head `f29bf48c952056ad5449f105c27fc0150a4381db`, which ports the MVP onto `release/0.3.2-build.39` / SheV Agent 0.3.2 build 49.
- Do not implement on the older `main` history and do not merge this branch into a release branch without passing CI and explicit review.
- Preserve the existing chat UI, provider routing, settings, voice, weather client and local persistence.
- The current MVP is an in-chat Compose overlay, not an Android system overlay. Keep that permission model unless a separately reviewed requirement changes it.

## Audit findings from PR #67

The MVP already wires `liveWidgets` into `LocalModelPanel`, renders `LiveWidgetsOverlay` above the transcript, persists widget state in `SharedPreferences`, routes explicit Russian commands in `LocalModelViewModel.send()`, and adds policy/router unit tests. Therefore, the earlier concern that no UI wiring exists is not accurate for PR #67. The remaining work is to strengthen behavior and coverage.

Current constraints visible in the MVP:
- `LiveWidgetPolicy.apply()` selects the first widget by type, so multiple instances of one type are not supported.
- HIDE removes an item from persisted state; SHOW creates a new one, so the previous position/scale/city is lost.
- Widget state has no explicit visibility or z-order.
- MOVE parses directional phrases into preset normalized coordinates rather than applying a relative delta to the current position.
- Resize is represented as a scale change, but there is no explicit instance selection when multiple same-type widgets are eventually supported.
- Persistence stores a raw JSON list without an explicit schema version/migration envelope.
- Battery card behavior and overlay geometry need additional tests for charging state, low-battery state, safe insets and rotation.
- Voice commands currently depend on the same text send path; verify the speech-to-text submission path and do not create a second competing router.

## Target design

Keep the implementation Kotlin/Jetpack Compose-native and split responsibilities cleanly. Refactor incrementally; avoid a large rewrite.

Suggested model:
- `LiveWidget`: stable ID, type, position, scale, city/config, visible flag, zIndex.
- `LiveWidgetLayout`: schemaVersion and list of widget instances.
- `LiveWidgetRegistry`: type metadata, localized aliases, default config and factory.
- `LiveWidgetPolicy`: validation and pure state transitions.
- `LiveWidgetCommandHandler`: maps typed intents to policy operations.
- `LiveWidgetRepository`: versioned load/save and migrations.
- `LiveWidgetsOverlay`: rendering, drag gesture, resize affordances, safe bounds and animations.

### Behavior requirements

1. SHOW/HIDE
   - HIDE changes `visible=false`; it must not delete configuration or position.
   - SHOW restores the same hidden instance and its last position/scale.
   - HIDE ALL hides all instances; it does not erase them.
   - Add a separate explicit REMOVE command only if the user asks to delete a widget permanently.

2. Instances and ordering
   - Support multiple instances of a type using stable IDs.
   - Commands that are ambiguous when multiple matching instances exist must ask which one.
   - Bring the dragged instance to front by increasing z-order; normalize z-order safely when values grow.
   - Preserve a configurable maximum instance count and reject invalid layouts.

3. Movement
   - Directional commands such as “перемести часы вправо” apply a bounded delta relative to the current position.
   - Explicit destinations such as “поставь часы сверху справа” may use normalized anchor positions.
   - Clamp the full widget bounds, not only its top-left anchor, to the available chat viewport.

4. Resize
   - Support “сделай крупнее/мельче”, “увеличь/уменьши часы”, and English equivalents.
   - Keep scale within tested min/max values and persist it.
   - Use instance clarification when multiple widgets of the same type exist.

5. Commands
   - Centralize Russian and English aliases in the registry/router.
   - Cover SHOW, HIDE, HIDE ALL, MOVE, RESIZE and explicit REMOVE.
   - Keep ordinary questions about weather/time/battery out of the widget-command route.
   - Use the same command handler for typed chat and speech-to-text submissions.

6. UI/animation
   - Use Compose spring animations for drag/release and visibility.
   - Add a subtle snap-to-edge behavior only after release; never make dragging feel sticky.
   - Respect WindowInsets and the actual chat viewport, including composer, keyboard, status/navigation bars and rotation.
   - Keep controls accessible and large enough for touch.

7. Battery
   - Show percentage and charging/discharging/full state from Android battery broadcasts.
   - Add a low-battery visual warning using a documented threshold.
   - Avoid polling more often than necessary; unregister listeners with the composable lifecycle.

8. Persistence and migration
   - Store a versioned envelope, e.g. `schemaVersion: 2` plus items.
   - Migrate the current unversioned JSON list to v2 without losing existing widget IDs, positions, scale or city.
   - If data is malformed, fail safely and preserve a recoverable backup before resetting.

## Test plan / definition of done

- Unit tests: show/hide/restore preserves instance config; hide all preserves hidden items; add/remove multiple same-type instances; ambiguous target clarification; z-order promotion; relative movement; resize bounds; registry aliases RU/EN; migration from current legacy JSON; corrupt-data recovery.
- Compose/UI tests: drag bounds with insets, composer and IME visible, visibility animation, resize affordances, rotation and small-screen layouts.
- Battery tests: charging, discharging, full and low battery states.
- Run the repository's required Android CI on the exact PR head. Do not claim physical-device verification unless installed and tested on a real device.
- Produce a debug APK artifact only after CI succeeds; do not publish or overwrite a release tag as part of this work.

## Delivery sequence

1. Verify the exact baseline and CI state.
2. Add versioned persistence and backward migration.
3. Add visibility, stable instances and z-order to the model/policy.
4. Extend the command router and connect voice through the existing send path.
5. Improve overlay bounds, spring motion, snap and resize UX.
6. Add battery states and lifecycle-safe updates.
7. Add unit/UI tests, run CI, inspect APK artifact, then request review.
