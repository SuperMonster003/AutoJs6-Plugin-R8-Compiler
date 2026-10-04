"""Build four selectable launcher modes from the preserved vector foreground.

The original application/README PNG is retained. Auto has an independent resource ID because PackageManager eagerly resolves
values aliases while parsing activity icons. Every legacy notnight override has
a matching notnight-v26 adaptive XML to preserve drawable type.
"""
from pathlib import Path
import argparse, xml.etree.ElementTree as ET
from PIL import Image
import icon_geometry as geometry

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
SOURCE = ROOT / ".python/icons/launcher-foreground.xml"
A = "{http://schemas.android.com/apk/res/android}"
ET.register_namespace("android", A[1:-1])
# Retained original raster of the same R artwork supplies alpha measurements and
# the catalog PNG. Launcher resources keep their original VectorDrawable paths.
RASTER_SOURCE = ROOT / ".python/icons/r8-original.png"
with Image.open(RASTER_SOURCE) as original:
    SOURCE_ALPHA = original.convert("RGBA").getchannel("A")
SOURCE_ALPHA = SOURCE_ALPHA.crop(SOURCE_ALPHA.getbbox())
UI_GLYPH, ADAPTIVE_GLYPH = geometry.normalized_ratios(SOURCE_ALPHA)

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
    # Source path bounds are (11, 12)-(38, 36), in a 48-unit viewport.
    # Position that artwork explicitly; scaling the previous translated group
    # would move its center as well as changing its size.
    ratio = UI_GLYPH if legacy else ADAPTIVE_GLYPH
    scale = ratio * width / 27
    group = root.find("group")
    for key, value in (("scaleX", scale), ("scaleY", scale),
                       ("translateX", width / 2 - 24.5 * scale),
                       ("translateY", height / 2 - 24 * scale)):
        group.set(A + key, f"{value:.9f}")
    # A conservative vector bounding-circle check also includes an AA margin.
    if ((27 * scale / 2) ** 2 + (24 * scale / 2) ** 2) ** .5 + width / 432 > width * (0.5 if legacy else 33 / 108):
        raise ValueError("Vector artwork exceeds the safe circle")
    if background:
        c = width / 2
        root.insert(0, ET.Element("path", {A + "fillColor": background, A + "pathData":
            f"M{c},0A{c},{c} 0,1 0,{c},{width}A{c},{c} 0,1 0,{c},0"}))
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
        "raw/keep_plugin_center_icon.xml": geometry.KEEP_RESOURCE,
        "mipmap/ic_plugin_center.png": geometry.encode_png(geometry.render(SOURCE_ALPHA, UI_GLYPH, (0x27,) * 3)),
        "mipmap-night/ic_plugin_center.png": geometry.encode_png(geometry.render(SOURCE_ALPHA, UI_GLYPH, (0xD8,) * 3)),
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
    outputs = resources()
    for name, data in outputs.items():
        path = RES / name
        if path.exists() and path.read_bytes() == data: continue
        changed.append(name)
        if not args.check:
            path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
    if args.check and changed: raise SystemExit("Launcher resources differ: " + ", ".join(changed))
    print("Launcher mode resources verified" if args.check else f"Generated {len(outputs)} launcher mode resources")

# AutoJs6 Icon Studio: committed recipe entry point
from pathlib import Path as _IconStudioPath
if __name__ == "__main__" and (_IconStudioPath(__file__).resolve().parents[1] / ".icons/recipe.json").is_file():
    from icon_studio_runtime import main as icon_studio_main
    raise SystemExit(icon_studio_main(_IconStudioPath(__file__).resolve().parents[1]))

if __name__ == "__main__": main()
