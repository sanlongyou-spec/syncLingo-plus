#!/usr/bin/env python3
"""Convert the secretariat dictionary workbook into terminology API payloads."""

from __future__ import annotations

import argparse
import csv
import json
import re
import urllib.request
import zipfile
from collections.abc import Iterable
from pathlib import Path
from xml.etree import ElementTree as ET


MAIN_NS = {"main": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}


def normalize_text(value: str | None) -> str:
    return re.sub(r"\s+", " ", (value or "").replace("\u3000", " ")).strip(" ;；,，")


def column_index(cell_ref: str | None) -> int:
    match = re.match(r"([A-Z]+)", cell_ref or "")
    if not match:
        return 0
    value = 0
    for char in match.group(1):
        value = value * 26 + ord(char) - 64
    return value - 1


def read_workbook(path: Path) -> list[tuple[str, list[list[str]]]]:
    with zipfile.ZipFile(path) as archive:
        shared_strings: list[str] = []
        if "xl/sharedStrings.xml" in archive.namelist():
            root = ET.fromstring(archive.read("xl/sharedStrings.xml"))
            shared_strings = [
                "".join((node.text or "") for node in item.findall(".//main:t", MAIN_NS))
                for item in root.findall("main:si", MAIN_NS)
            ]

        workbook = ET.fromstring(archive.read("xl/workbook.xml"))
        relations = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
        relation_by_id = {item.attrib["Id"]: item.attrib["Target"] for item in relations}

        sheets: list[tuple[str, list[list[str]]]] = []
        for sheet in workbook.findall("main:sheets/main:sheet", MAIN_NS):
            name = sheet.attrib["name"]
            relation_id = sheet.attrib[
                "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id"
            ]
            target = relation_by_id[relation_id]
            sheet_path = target if target.startswith("xl/") else f"xl/{target.lstrip('/')}"
            root = ET.fromstring(archive.read(sheet_path))
            rows: list[list[str]] = []
            for row in root.findall(".//main:sheetData/main:row", MAIN_NS):
                values: list[str] = []
                for cell in row.findall("main:c", MAIN_NS):
                    index = column_index(cell.attrib.get("r"))
                    while len(values) <= index:
                        values.append("")
                    cell_type = cell.attrib.get("t")
                    value_node = cell.find("main:v", MAIN_NS)
                    inline_node = cell.find("main:is/main:t", MAIN_NS)
                    value = ""
                    if cell_type == "s" and value_node is not None:
                        shared_index = int(value_node.text or "0")
                        value = shared_strings[shared_index] if shared_index < len(shared_strings) else ""
                    elif cell_type == "inlineStr" and inline_node is not None:
                        value = inline_node.text or ""
                    elif value_node is not None:
                        value = value_node.text or ""
                    values[index] = normalize_text(value)
                rows.append(values)
            sheets.append((name, rows))
        return sheets


def get(row: list[str], index: int) -> str:
    return row[index] if index < len(row) else ""


def add_term(
    terms: list[dict[str, object]],
    sheet: str,
    row_number: int,
    term_id: str = "",
    term_en: str = "",
    term_zh: str = "",
    pinyin: str = "",
    category: str = "",
    note: str = "",
    user_id: int = 1,
) -> None:
    term_id = normalize_text(term_id)
    term_en = normalize_text(term_en)
    term_zh = normalize_text(term_zh)
    if not (term_id or term_en or term_zh):
        return
    header_text = f"{term_id}{term_en}{term_zh}".lower()
    if row_number <= 2 and any(marker in header_text for marker in ("indonesia", "english", "mandarin", "no")):
        return
    terms.append(
        {
            "termId": term_id or None,
            "userId": user_id,
            "termEn": term_en or None,
            "termZh": term_zh or None,
            "pinyin": normalize_text(pinyin) or None,
            "category": normalize_text(category) or None,
            "note": normalize_text(note) or None,
            "sourceSheet": sheet,
            "sourceRow": row_number,
            "reviewStatus": "APPROVED" if term_zh and (term_id or term_en) else "NEED_REVIEW",
            "enabled": bool(term_zh and (term_id or term_en)),
        }
    )


def build_terms(sheets: list[tuple[str, list[list[str]]]], user_id: int) -> list[dict[str, object]]:
    terms: list[dict[str, object]] = []

    main_sheet, main_rows = sheets[0]
    for row_number, row in enumerate(main_rows[1:], start=2):
        add_term(terms, main_sheet, row_number, get(row, 0), get(row, 1), get(row, 3), get(row, 4), get(row, 5), get(row, 6), user_id)

    fertilizer_sheet, fertilizer_rows = sheets[2]
    for row_number, row in enumerate(fertilizer_rows, start=1):
        add_term(terms, fertilizer_sheet, row_number, "", get(row, 2), get(row, 3), get(row, 4), "农业", "", user_id)
        add_term(terms, fertilizer_sheet, row_number, "", get(row, 7), get(row, 8), get(row, 9), "农业", "", user_id)

    machinery_sheet, machinery_rows = sheets[3]
    for row_number, row in enumerate(machinery_rows, start=1):
        add_term(terms, machinery_sheet, row_number, get(row, 2), "", get(row, 3), get(row, 4), "机械", "英文/印尼文混合列", user_id)
        add_term(terms, machinery_sheet, row_number, get(row, 7), "", get(row, 8), get(row, 9), "机械", "英文/印尼文混合列", user_id)

    hydrocarbon_sheet, hydrocarbon_rows = sheets[4]
    for row_number, row in enumerate(hydrocarbon_rows, start=1):
        add_term(terms, hydrocarbon_sheet, row_number, "", get(row, 0), get(row, 1), "", "化学", "词缀/词根", user_id)
        add_term(terms, hydrocarbon_sheet, row_number, "", get(row, 3), get(row, 4), "", "化学", "词缀/词根", user_id)
        add_term(terms, hydrocarbon_sheet, row_number, "", get(row, 6), get(row, 7), "", "化学", "词缀/词根", user_id)

    agro_sheet, agro_rows = sheets[5]
    for row_number, row in enumerate(agro_rows, start=1):
        add_term(terms, agro_sheet, row_number, get(row, 1), get(row, 2), get(row, 3), get(row, 4), "工农业", "", user_id)

    return terms


def chunks(items: list[dict[str, object]], size: int) -> Iterable[list[dict[str, object]]]:
    for start in range(0, len(items), size):
        yield items[start:start + size]


def post_terms(api_base_url: str, terms: list[dict[str, object]], batch_size: int, user_id: int) -> None:
    for batch in chunks(terms, batch_size):
        request = urllib.request.Request(
            f"{api_base_url.rstrip('/')}/api/terminology/batch?userId={user_id}",
            data=json.dumps(batch, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(request) as response:
            if response.status >= 300:
                raise RuntimeError(f"Batch import failed with HTTP {response.status}")


def aliases(value: object) -> list[str]:
    text = normalize_text(str(value or ""))
    if not text:
        return []
    return [normalize_text(part) for part in re.split(r"[;；]", text) if normalize_text(part)]


def write_glossary_files(output_dir: Path, terms: list[dict[str, object]]) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    directions = {
        "zh-to-id.tsv": ("termZh", "termId"),
        "id-to-zh.tsv": ("termId", "termZh"),
        "zh-to-en.tsv": ("termZh", "termEn"),
        "en-to-zh.tsv": ("termEn", "termZh"),
        "id-to-en.tsv": ("termId", "termEn"),
        "en-to-id.tsv": ("termEn", "termId"),
    }
    approved = [item for item in terms if item["reviewStatus"] == "APPROVED" and item["enabled"]]
    for file_name, (source_key, target_key) in directions.items():
        rows: set[tuple[str, str]] = set()
        for item in approved:
            for source_term in aliases(item.get(source_key)):
                for target_term in aliases(item.get(target_key)):
                    if source_term and target_term:
                        rows.add((source_term, target_term))
        with (output_dir / file_name).open("w", encoding="utf-8", newline="") as handle:
            writer = csv.writer(handle, delimiter="\t")
            writer.writerows(sorted(rows))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("xlsx_path", type=Path)
    parser.add_argument("--output", type=Path, default=Path("outputs/secretariat-terminology.json"))
    parser.add_argument("--glossary-output-dir", type=Path, default=Path("outputs/glossaries"))
    parser.add_argument("--api-base-url")
    parser.add_argument("--batch-size", type=int, default=200)
    parser.add_argument("--user-id", type=int, default=1)
    args = parser.parse_args()

    sheets = read_workbook(args.xlsx_path)
    terms = build_terms(sheets, args.user_id)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(terms, ensure_ascii=False, indent=2), encoding="utf-8")
    write_glossary_files(args.glossary_output_dir, terms)

    approved = sum(1 for item in terms if item["reviewStatus"] == "APPROVED")
    need_review = len(terms) - approved
    print(f"Generated {len(terms)} terms: approved={approved}, need_review={need_review}")
    print(f"Wrote {args.output}")
    print(f"Wrote glossary files to {args.glossary_output_dir}")

    if args.api_base_url:
        post_terms(args.api_base_url, terms, args.batch_size, args.user_id)
        print(f"Imported {len(terms)} terms into {args.api_base_url}")


if __name__ == "__main__":
    main()
