"""Build four selectable launcher modes from the preserved vector foreground.

The original application/README PNG is retained. Auto has an independent resource ID because PackageManager eagerly resolves
values aliases while parsing activity icons. Every legacy notnight override has
a matching notnight-v26 adaptive XML to preserve drawable type.
"""
from pathlib import Path
import argparse, xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
SOURCE = ROOT / ".python/icons/launcher-foreground.xml"
A = "{http://schemas.android.com/apk/res/android}"
ET.register_namespace("android", A[1:-1])
SCALE = None

def xml(root):
    ET.indent(root, space="    ")
    return b'<?xml version="1.0" encoding="utf-8"?>\n' + ET.tostring(root, encoding="utf-8") + b"\n"

def glyph(color, legacy=False, background=None):
    root = ET.parse(SOURCE).getroot()
    width, height = (float(root.get(A + name)) for name in ("viewportWidth", "viewportHeight"))
    assert width == height
    for path in root.iter("path"):
        if A + "fillColor" in path.attrib: path.set(A + "fillColor", color)
        if A + "strokeColor" in path.attrib: path.set(A + "strokeColor", color)
    if SCALE is not None:
        group = root.find("group")
        group.set(A + "scaleX", str(SCALE)); group.set(A + "scaleY", str(SCALE))
    if legacy:
        children = list(root)
        for child in children: root.remove(child)
        if background:
            c = width / 2
            ET.SubElement(root, "path", {A + "fillColor": background, A + "pathData":
                f"M{c},0A{c},{c} 0,1 0,{c},{width}A{c},{c} 0,1 0,{c},0"})
        group = ET.SubElement(root, "group", {A + "pivotX": str(width/2), A + "pivotY": str(height/2), A + "scaleX": "1.5", A + "scaleY": "1.5"})
        group.extend(children)
    return xml(root)

def adaptive(foreground, background):
    root = ET.Element("adaptive-icon")
    for tag, ref in (("background", "@color/" + background), ("foreground", "@drawable/" + foreground), ("monochrome", "@drawable/launcher_glyph_monochrome")):
        ET.SubElement(root, tag, {A + "drawable": ref})
    return xml(root)

def resources():
    values = ET.Element("resources")
    for name, color in (("launcher_icon_background_dark", "#212121"), ("launcher_icon_background_light", "#FAFAFA")):
        ET.SubElement(values, "color", {"name": name}).text = color
    return {
        "drawable/launcher_glyph_dark.xml": glyph("#D8D8D8"),
        "drawable/launcher_glyph_light.xml": glyph("#272727"),
        "drawable/launcher_glyph_monochrome.xml": glyph("#000000"),
        "mipmap/ic_launcher_system.xml": glyph("#D8D8D8", True, "#212121"),
        "mipmap/ic_launcher_system_light.xml": glyph("#272727", True, "#FAFAFA"),
        "mipmap/ic_launcher_transparent.xml": glyph("#272727", True),
        "mipmap-night/ic_launcher_transparent.xml": glyph("#D8D8D8", True),
        "mipmap-anydpi-v26/ic_launcher_system.xml": adaptive("launcher_glyph_dark", "launcher_icon_background_dark"),
        "mipmap-anydpi-v26/ic_launcher_system_light.xml": adaptive("launcher_glyph_light", "launcher_icon_background_light"),
        "values/launcher_icons.xml": xml(values),
        "mipmap/ic_launcher_system_auto.xml": glyph("#D8D8D8", True, "#212121"),
        "mipmap-notnight/ic_launcher_system_auto.xml": glyph("#272727", True, "#FAFAFA"),
        "mipmap-anydpi-v26/ic_launcher_system_auto.xml": adaptive("launcher_glyph_dark", "launcher_icon_background_dark"),
        "mipmap-notnight-anydpi-v26/ic_launcher_system_auto.xml": adaptive("launcher_glyph_light", "launcher_icon_background_light"),
    }

def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--check", action="store_true")
    args = parser.parse_args(); changed = []
    obsolete = (RES / "values-notnight/launcher_icons.xml").resolve()
    assert obsolete.is_relative_to(RES.resolve())
    if obsolete.exists():
        changed.append("obsolete values-notnight/launcher_icons.xml")
        if not args.check: obsolete.unlink()
    for name, data in resources().items():
        path = RES / name
        if path.exists() and path.read_bytes() == data: continue
        changed.append(name)
        if not args.check:
            path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
    if args.check and changed: raise SystemExit("Launcher resources differ: " + ", ".join(changed))
    print("Launcher mode resources verified" if args.check else "Generated 14 launcher mode resources")

if __name__ == "__main__": main()
