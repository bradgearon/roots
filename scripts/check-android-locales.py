"""Validate the bundled Roots UI string catalog (standard library only)."""
from pathlib import Path
import xml.etree.ElementTree as ET
import re

ROOT = Path(__file__).resolve().parents[1] / "android/contextual-overlay/src/main"
RES = ROOT / "res"
FILES = ("roots_ui_navigation.xml", "roots_ui_actions.xml")
ANDROID = "{http://schemas.android.com/apk/res/android}"


def qualifier(locale):
    return {"en": "values", "zh-Hans": "values-b+zh+Hans",
            "zh-Hant": "values-b+zh+Hant", "he": "values-iw",
            "id": "values-in", "fil": "values-b+fil",
            "ceb": "values-b+ceb"}.get(locale, "values-" + locale)


def catalog(folder):
    result = {}
    for filename in FILES:
        path = RES / folder / filename
        for item in ET.parse(path).getroot():
            key = item.attrib["name"]
            assert item.tag == "string" and key.startswith("roots_ui_"), path
            assert key not in result and (item.text or "").strip(), (path, key)
            result[key] = item.text
    return result


locales = [x.attrib[ANDROID + "name"] for x in
           ET.parse(RES / "xml/roots_ui_locales.xml").getroot()]
assert len(locales) == len(set(locales)) == 43
english = catalog("values")
assert len(english) >= 54
for locale in locales:
    translated = catalog(qualifier(locale))
    assert translated.keys() == english.keys(), (locale, english.keys() ^ translated.keys())
used = set()
for path in (ROOT / "java").rglob("*.java"):
    used.update(re.findall(r"R\.string\.(roots_ui_\w+)", path.read_text(encoding="utf-8")))
assert used <= english.keys(), used - english.keys()
print(f"{len(locales)} locales, {len(english)} strings each; {len(used)} referenced by Java")
