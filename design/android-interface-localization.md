# Android interface localization

The Roots app uses ordinary Android string resources (not PLaMo at runtime).
The device/app locale selects translated resources, and missing strings fall
back to English. Android 13+ exposes the 43 locale variants in system app
language settings. The content translation target remains an independent
preference; Follow Android language resolves the current app configuration.

Current coverage: 54 common navigation, Tree/Mesh and translation-setting
labels across 43 locale variants. These are draft translations, not native
speaker certified. Advanced controls, dynamic status reports, long permission
and sharing disclosures, and other technical text still use existing English.
Do not describe this as complete 43-language localization.

Resources are grouped as `roots_ui_navigation.xml` and
`roots_ui_actions.xml` under the library's `src/main/res/values*` folders.
Use `getString(R.string.roots_ui_...)` for programmatically constructed UI
and resource IDs for dialog buttons. Never translate source content, model
prompts, identifiers, URLs, stored knowledge or protocol keys as UI labels.
Brand and model names remain unchanged. Chinese uses separate script folders;
Android Hebrew and Indonesian use legacy resource qualifiers and Filipino
uses the Android locale `fil` (PLaMo target code `tl`).

To add a label, add its English value first and a translated value under each
locale when available. Run `python scripts/check-android-locales.py` to check
resource parity; Android resources and UI layout still require a real Android
build and native-language review. Do not change consent semantics when
translating disclosures; validate those with the feature owners.
