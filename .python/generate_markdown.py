# -*- coding: utf-8 -*-
import argparse
import json
import re
from pathlib import Path


LANGUAGE_CODES = [
    "zh-Hans",
    "zh-Hant-HK",
    "zh-Hant-TW",
    "en",
    "fr",
    "es",
    "ja",
    "ko",
    "ru",
    "ar",
]
LANGUAGE_CODE_DEFAULT = "zh-Hans"
ANDROID_CHANGELOG_ALIASES = {
    "zh-Hans": ["zh", "zh-Hans"],
    "zh-Hant-HK": ["zh-rHK", "zh-Hant-HK"],
    "zh-Hant-TW": ["zh-rTW", "zh-Hant-TW"],
}


ROOT = Path(__file__).resolve().parents[1]
README_DIR = ROOT / ".readme"
CHANGELOG_DIR = ROOT / ".changelog"
ANDROID_CHANGELOG_DIR = ROOT / "app" / "src" / "main" / "assets" / "doc"


def load_json(path: Path):
    with path.open("r", encoding="utf-8") as file:
        return json.load(file)


def render_template(text: str, values: dict) -> str:
    def replace(match):
        key = match.group(1).strip()
        if key not in values:
            raise KeyError(f"Missing template value: {key}")
        return str(values[key])

    return re.sub(r"\{\{\s*([A-Za-z0-9_$.-]+)\s*\}\}", replace, text)


def render_dynamic(value, values: dict):
    if isinstance(value, dict):
        return {key: render_dynamic(item, values) for key, item in value.items()}
    if isinstance(value, list):
        return [render_dynamic(item, values) for item in value]
    if isinstance(value, str):
        return render_template(value, values)
    return value


def bullet_list(items):
    return "\n".join(f"- {item}" for item in items)


def markdown_link(label, url):
    return f"[{label}]({url})"


def load_languages():
    common = load_json(README_DIR / "common.json")
    languages = {}
    changelogs = {}
    for code in LANGUAGE_CODES:
        raw_language = load_json(README_DIR / f"lang_{code}.json")
        merged_language = {**common, **raw_language}
        languages[code] = render_dynamic(merged_language, merged_language)

        raw_changelog = load_json(CHANGELOG_DIR / f"lang_{code}.json")
        changelog_values = {key: value for key, value in raw_changelog.items() if key != "$data"}
        changelog_values = render_dynamic(changelog_values, changelog_values)
        changelogs[code] = {
            "values": changelog_values,
            "data": render_dynamic(raw_changelog["$data"], changelog_values),
        }
    return languages, changelogs


def format_changelog_items(changelog, limit=None):
    values = changelog["values"]
    chunks = []
    for index, (version_name, item) in enumerate(changelog["data"].items()):
        if limit is not None and index >= limit:
            break
        lines = [
            f"# {version_name}",
            "",
            f"###### {item['released_date']}",
            "",
        ]
        for category in ["hint", "feature", "fix", "improvement", "dependency"]:
            for item_text in item.get(category, []):
                lines.append(f"* `{values[f'changelog_label_{category}']}` {item_text}")
        chunks.append("\n".join(lines).rstrip())
    return "\n\n".join(chunks).rstrip() + "\n"


def build_language_list(target_code, languages):
    repo_url = languages[target_code]["repo_url"]
    lines = []
    for code in LANGUAGE_CODES:
        content = languages[code]
        label = f"{content['$name']} [{code}]"
        if code == target_code:
            lines.append(f"- {label} # {content['text_current_lowercase']}")
        else:
            lines.append(f"- {markdown_link(label, f'{repo_url}/blob/master/.readme/README-{code}.md')}")
    return "\n".join(lines)


def build_readme_values(code, languages, changelogs):
    content = dict(languages[code])
    repo_url = content["repo_url"]
    content["placeholder_ul_languages_all_supported"] = build_language_list(code, languages)
    content["placeholder_features"] = bullet_list(content["features"])
    content["placeholder_boundaries"] = bullet_list(content["boundaries"])
    content["placeholder_security_limits"] = bullet_list(content["security_limits"])
    content["placeholder_caveats"] = bullet_list(content["caveats"])
    content["placeholder_latest_release_history"] = format_changelog_items(
        changelogs[code],
        limit=3,
    ).rstrip()
    content["placeholder_read_more_in_changelog_md"] = markdown_link(
        f"CHANGELOG-{code}.md",
        f"{repo_url}/blob/master/app/src/main/assets/doc/CHANGELOG-{code}.md",
    )
    return content


def add_output(outputs: dict, path: Path, content: str):
    if path in outputs:
        raise ValueError(f"Duplicate generated output: {path.relative_to(ROOT)}")
    outputs[path] = content


def collect_readmes(outputs, languages, changelogs):
    template = (README_DIR / "template_readme.md").read_text(encoding="utf-8")
    for code in LANGUAGE_CODES:
        output = render_template(template, build_readme_values(code, languages, changelogs))
        add_output(outputs, README_DIR / f"README-{code}.md", output)
        if code == LANGUAGE_CODE_DEFAULT:
            add_output(outputs, ROOT / "README.md", output)


def collect_changelogs(outputs, languages, changelogs):
    template = (CHANGELOG_DIR / "template_changelog.md").read_text(encoding="utf-8")
    for code in LANGUAGE_CODES:
        values = dict(languages[code])
        values["placeholder_release_history"] = format_changelog_items(changelogs[code]).rstrip()
        output = render_template(template, values)
        for name in ANDROID_CHANGELOG_ALIASES.get(code, [code]):
            add_output(outputs, ANDROID_CHANGELOG_DIR / f"CHANGELOG-{name}.md", output)
        if code == LANGUAGE_CODE_DEFAULT:
            add_output(outputs, ANDROID_CHANGELOG_DIR / "CHANGELOG.md", output)
            add_output(outputs, ROOT / "CHANGELOG.md", output)


def collect_outputs(languages, changelogs):
    outputs = {}
    collect_changelogs(outputs, languages, changelogs)
    collect_readmes(outputs, languages, changelogs)
    return outputs


def write_outputs(outputs):
    for path, content in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content.encode("utf-8"))
        print(f"Generated {path.relative_to(ROOT)}")


def check_outputs(outputs):
    mismatches = []
    for path, content in outputs.items():
        expected = content.encode("utf-8")
        if not path.is_file():
            mismatches.append((path, "missing"))
        elif path.read_bytes() != expected:
            mismatches.append((path, "content differs"))

    if mismatches:
        for path, reason in mismatches:
            print(f"OUT OF DATE: {path.relative_to(ROOT)} ({reason})")
        print("Generated documentation is inconsistent. Run: python .python/generate_markdown.py")
        return False

    print(f"Generated documentation is consistent ({len(outputs)} files checked).")
    return True


def parse_args():
    parser = argparse.ArgumentParser(
        description="Generate or verify the repository's localized Markdown artifacts.",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify generated files without modifying the working tree",
    )
    return parser.parse_args()


def main():
    args = parse_args()
    if LANGUAGE_CODE_DEFAULT not in LANGUAGE_CODES:
        raise ValueError(f"Default language code {LANGUAGE_CODE_DEFAULT!r} is not supported")
    languages, changelogs = load_languages()
    outputs = collect_outputs(languages, changelogs)
    if args.check:
        if not check_outputs(outputs):
            raise SystemExit(1)
    else:
        write_outputs(outputs)


if __name__ == "__main__":
    main()
