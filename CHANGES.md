# Changelog — 20260503

## Summary of Changes

| Type | Count |
|------|-------|
| Modified | 1 |
| Removed | 84 |
| Added | 0 |

---

## Modified Files

- `README.md` — replaced generic README with full Android project documentation

---

## Removed Files


### `screenshots/` — Store/README images (not required for Android build)

- `screenshots/1.png`
- `screenshots/1.thumb.png`
- `screenshots/2.png`
- `screenshots/2.thumb.png`
- `screenshots/3.png`
- `screenshots/3.thumb.png`
- `screenshots/4.png`
- `screenshots/4.thumb.png`
- `screenshots/5.png`
- `screenshots/5.thumb.png`
- `screenshots/6.png`
- `screenshots/6.thumb.png`
- `screenshots/tasker/tasker_01.png`
- `screenshots/tasker/tasker_02.png`
- `screenshots/tasker/tasker_03.png`
- `screenshots/tasker/tasker_04.png`
- `screenshots/tasker/tasker_05.png`
- `screenshots/tasker/tasker_06.png`
- `screenshots/tasker/tasker_07.png`
- `screenshots/tasker/tasker_08.png`
- `screenshots/tasker/tasker_09.png`
- `screenshots/tasker/tasker_10.png`
- `screenshots/tasker/tasker_11.png`
- `screenshots/tasker/tasker_12.png`
- `screenshots/tasker/tasker_13.png`
- `screenshots/tasker/tasker_14.png`
- `screenshots/tasker/thumbs/tasker_01.png`
- `screenshots/tasker/thumbs/tasker_02.png`
- `screenshots/tasker/thumbs/tasker_03.png`
- `screenshots/tasker/thumbs/tasker_04.png`
- `screenshots/tasker/thumbs/tasker_05.png`
- `screenshots/tasker/thumbs/tasker_06.png`
- `screenshots/tasker/thumbs/tasker_07.png`
- `screenshots/tasker/thumbs/tasker_08.png`
- `screenshots/tasker/thumbs/tasker_09.png`
- `screenshots/tasker/thumbs/tasker_10.png`
- `screenshots/tasker/thumbs/tasker_11.png`
- `screenshots/tasker/thumbs/tasker_12.png`
- `screenshots/tasker/thumbs/tasker_13.png`
- `screenshots/tasker/thumbs/tasker_14.png`

### `.github/` — GitHub CI workflows and issue templates

- `.github/ISSUE_TEMPLATE/bug_report.md`
- `.github/ISSUE_TEMPLATE/config.yml`
- `.github/dependabot.yml`
- `.github/workflows/main.yml`

### `docs/` — Developer documentation (build, guidelines, test guides)

- `docs/BUILD.md`
- `docs/GUIDELINES.md`
- `docs/TEST.md`

### `uhabits-ios/` — iOS module (not referenced in `settings.gradle.kts`)

- `uhabits-ios/Application/AppDelegate.swift`
- `uhabits-ios/Application/Assets.xcassets/AppIcon.appiconset/Contents.json`
- `uhabits-ios/Application/Assets.xcassets/AppIcon.appiconset/loop-120.png`
- `uhabits-ios/Application/Assets.xcassets/AppIcon.appiconset/loop-180.png`
- `uhabits-ios/Application/Assets.xcassets/Contents.json`
- `uhabits-ios/Application/Assets.xcassets/ic_more.imageset/Contents.json`
- `uhabits-ios/Application/Assets.xcassets/ic_more.imageset/baseline_more_horiz_black_24pt_1x.png`
- `uhabits-ios/Application/Assets.xcassets/ic_more.imageset/baseline_more_horiz_black_24pt_2x.png`
- `uhabits-ios/Application/Assets.xcassets/ic_more.imageset/baseline_more_horiz_black_24pt_3x.png`
- `uhabits-ios/Application/BridgingHeader.h`
- `uhabits-ios/Application/Frontend/AboutScreenController.swift`
- `uhabits-ios/Application/Frontend/DetailScreenController.swift`
- `uhabits-ios/Application/Frontend/EditHabitController.swift`
- `uhabits-ios/Application/Frontend/MainScreenController.swift`
- `uhabits-ios/Application/Info.plist`
- `uhabits-ios/Application/Launch.storyboard`
- `uhabits-ios/Application/Platform/ComponentView.swift`
- `uhabits-ios/Tests/Info.plist`
- `uhabits-ios/uhabits.xcodeproj/project.pbxproj`
- `uhabits-ios/uhabits.xcodeproj/project.xcworkspace/contents.xcworkspacedata`
- `uhabits-ios/uhabits.xcodeproj/project.xcworkspace/xcshareddata/IDEWorkspaceChecks.plist`
- `uhabits-ios/uhabits.xcodeproj/project.xcworkspace/xcshareddata/WorkspaceSettings.xcsettings`

### `uhabits-web/` — Web module (not part of Android Gradle build)

- `uhabits-web/.babelrc`
- `uhabits-web/Makefile`
- `uhabits-web/package-lock.json`
- `uhabits-web/package.json`
- `uhabits-web/src/main/index.js`
- `uhabits-web/src/test/index.html`
- `uhabits-web/src/test/index.js`

### `tools/` — Shell/Python helper scripts (string conversion utilities)

- `tools/androidStringsToKt.sh`
- `tools/convertAllStrings.sh`
- `tools/parseInstrument.py`

### Root-level files — informational/meta files not needed for build

- `CHANGELOG.md`
- `NOTICE.md`
- `build.sh`
- `translators-classic.csv`
- `translators-crowdin.csv`
