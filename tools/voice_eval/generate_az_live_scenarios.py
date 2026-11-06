#!/usr/bin/env python3
"""Generate deterministic Azerbaijani multi-turn live-Gemini E2E stories."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "docs/voice-evaluation/az-gemini-live-scenarios-v1.jsonl"
NAMES = (
    "Aylin", "Murad", "Nigar", "Tural", "Aysu", "Orxan", "Lalə", "Rauf", "Nərmin", "Elvin",
    "Sevda", "Fuad", "Günel", "Rəşad", "Mələk", "Emin", "Zəhra", "Cavid", "Fidan", "Anar",
    "Ləman", "Vüsal", "Afaq", "Kənan", "İlahə",
)

TOOLS = {
    1: ["record_money_event"],
    2: ["search_money", "annotate_money_event"],
    3: ["record_money_event", "settle_debt"],
    4: ["correct_money_event"],
    5: ["search_money"],
    6: ["search_conversation_history", "save_memory_episode"],
    7: ["search_memory"],
    8: ["create_task"],
    9: ["create_calendar_event"],
    10: ["search_money", "search_memory", "list_tasks", "list_calendar_events"],
}

RECEIPTS = (
    "Sınaq {marker} üçün: {name} mənə {receipt} manat verdi. Bu məbləği {date} tarixli gəlir kimi qeyd et.",
    "{date} tarixində {name}-dən aldığım {receipt} manat pulu Sınaq {marker} üzrə gəlir kimi yadda saxla.",
    "Sınaq {marker}: {name} mənə {receipt} manat ödədi; bunu {date} tarixində alınmış məbləğ kimi qeyd et.",
    "{name}-in Sınaq {marker} üçün mənə ödədiyi {receipt} manatı {date} tarixli gəlirə əlavə et.",
    "Gəlir qeydi yarat: Sınaq {marker}, ödəyən {name}, məbləğ {receipt} manat, tarix {date}.",
)

ANNOTATIONS = (
    "Sınaq {marker}-dəki {receipt} manat yeni gəlir deyil; {name}-in əvvəlki borcunun qismən qaytarılmasıdır. Mövcud qeydə izah əlavə et, təkrar pul əməliyyatı yaratma.",
    "Bir az əvvəl {name}-dən qeyd etdiyim {receipt} manat Sınaq {marker} üzrə əvvəlki borcun qaytarışıdır. Həmin qeydə bunu yaz, ikinci gəlir yaratma.",
    "{name}-in Sınaq {marker} üçün verdiyi {receipt} manat borcun bir hissəsinin qaytarılması idi; əvvəlki gəliri dəyişmədən yalnız ona izah əlavə et.",
    "Sınaq {marker}: {name}-in ödədiyi {receipt} manat əvvəlki borcdan qismən qaytarışdır, yeni ödəniş deyil. Mövcud əməliyyatı şərhlə qeyd et.",
    "Qeyd etdiyim {receipt} manat {name}-in köhnə borcunun bir hissəsidir. Sınaq {marker} qeydinə səbəbi əlavə et, məbləği və gəlir sayını artırma.",
)

DEBTS = (
    "Bu qaytarmadan əvvəl Sınaq {marker} üçün {name} mənə ümumilikdə {debt} manat borclu idi. Artıq qeyd etdiyim {receipt} manat qaytarışı həmin borcla əlaqələndir.",
    "Sınaq {marker}: {name}-in qaytardığı {receipt} manat nəzərə alınmazdan əvvəl borcu {debt} manat idi. Bu borcu qeyd et və əvvəlki qaytarışı ona bağla.",
    "Əvvəlki qaytarışdan qabaq {name}-in mənə {debt} manat borcu var idi. Sınaq {marker} üzrə mövcud {receipt} manatlıq ödənişi həmin borcun ödənməsi kimi əlaqələndir.",
    "{name} mənə əvvəlcə {debt} manat borclu idi; Sınaq {marker} qaytarışı olan {receipt} manatı bu borcdan çıx. Borcun ilkin məbləği qaytarışdan əvvəlki məbləğdir.",
    "Sınaq {marker} üçün borcun başlanğıc məbləği {debt} manatdır. {name}-in artıq yazılmış {receipt} manat ödənişini həmin borcun qismən qaytarışı kimi bağla.",
)

CORRECTIONS = (
    "Sınaq {marker}-də {name}-in əvvəlki borc qaytarışı {receipt} manat deyil, {corrected} manat olub. Həmin qaytarışın məbləğini düzəlt.",
    "Bir düzəliş: {name}-in Sınaq {marker} üçün qaytardığı məbləğ {corrected} manat idi, {receipt} yox. Mövcud ödənişi düzəlt, yenisini yaratma.",
    "{name}-in əvvəl qeyd etdiyim qaytarış məbləğini Sınaq {marker} üzrə {receipt}-dən {corrected} manata düzəlt.",
    "Sınaq {marker}: əvvəlki qaytarışı səhv məbləğlə yazmışam; {name} əslində {corrected} manat qaytarmışdı. Qeydi düzəlt.",
    "Mövcud {name} qaytarışının Sınaq {marker} məbləğini {corrected} manat et. {receipt} manatlıq əvvəlki məbləğ yanlışdır.",
)


def story(index: int) -> list[dict[str, object]]:
    name = NAMES[(index - 1) % len(NAMES)]
    marker = f"S-{index:03d}"
    receipt = 60 + (index * 7 % 100)
    debt = receipt + 200 + (index % 47)
    corrected = receipt - 10
    remaining = debt - corrected
    date = f"2026-09-{(index - 1) % 28 + 1:02d}"
    task_day = 10 + (index - 1) % 15
    task_due = f"2030-06-{task_day:02d}T10:30"
    event_start = f"2030-06-{task_day + 1:02d}T14:00"
    note_key = f"Sınaq {marker}"
    common = {"name": name, "marker": marker, "receipt": receipt, "debt": debt,
              "corrected": corrected, "remaining": remaining, "date": date}
    utterances = [
        RECEIPTS[(index - 1) % len(RECEIPTS)].format(**common),
        ANNOTATIONS[(index - 1) % len(ANNOTATIONS)].format(**common),
        DEBTS[(index - 1) % len(DEBTS)].format(**common),
        CORRECTIONS[(index - 1) % len(CORRECTIONS)].format(**common),
        f"Sınaq {marker} üzrə {name}-in mənə nə qədər borcu qalıb? Yalnız qeydə alınmış borc və qaytarışlara əsaslan.",
        f"Əvvəlki söhbətlərimizdə {name} ilə borc, qaytarış və düzəliş barədə danışdıqlarımızı {note_key} başlığı ilə mənbələrə bağlı qısa xülasə kimi yadda saxla; ilkin və qalan məbləği qarışdırma.",
        f"Yaddaşımda {name} və {note_key} barədə saxladığım xülasəni tap; düzəlişdən sonra qalan borc məbləğini de və mənbəyə bax.",
        f"{name} ilə {note_key} üzrə qalan borcun ödəniş statusunu yoxlamaq üçün tapşırıq yarat və {task_due[:10]} saat 10:30-da yadıma sal.",
        f"Təqvimə əlavə et: {name} ilə {note_key} üzrə borc qaytarışını müzakirə et, {event_start[:10]} saat 14:00.",
        f"{name} və {note_key} üzrə qeydlərimi yoxla: qalan borc nə qədərdir, hansı tapşırığı nə vaxt etməliyəm və görüş nə vaxtdır? Yaddaş, maliyyə və təqvim mənbələrindən istifadə et.",
    ]
    effects = (
        "receipt_created", "receipt_annotated_without_duplicate", "debt_created_and_receipt_linked",
        "receipt_corrected", "ledger_balance_read", "source_linked_episode_saved",
        "episode_retrieved_from_memory", "task_created_with_reminder", "calendar_event_created",
        "cross_domain_evidence_answered_without_write",
    )
    slots = (
        {"person_name": name, "kind": "receipt", "amount_minor": receipt * 100, "currency": "AZN", "occurred_date": date},
        {"person_name": name, "receipt_minor": receipt * 100, "new_receipt_count": 0},
        {"person_name": name, "debt_minor": debt * 100, "settlement_minor": receipt * 100, "remaining_minor": (debt - receipt) * 100},
        {"person_name": name, "corrected_receipt_minor": corrected * 100, "remaining_minor": remaining * 100},
        {"person_name": name, "remaining_minor": remaining * 100},
        {"person_name": name, "source_link_required": True, "required_amounts_minor": [receipt * 100, debt * 100, corrected * 100, remaining * 100]},
        {"person_name": name, "remaining_minor": remaining * 100, "source_link_required": True},
        {"title_contains": name, "due_local": task_due, "zone_id": "Asia/Baku", "remind": True, "person_label": name},
        {"title_contains": "borc", "start_local": event_start, "zone_id": "Asia/Baku", "precision": "minute"},
        {"person_name": name, "remaining_minor": remaining * 100, "task_due_local": task_due, "calendar_start_local": event_start, "read_only": True},
    )
    return [{
        "schema_version": 1,
        "scenario_id": f"AZ-LIVE-{index:03d}-{turn:02d}",
        "story_id": marker,
        "story_index": index,
        "turn": turn,
        "locale": "az-AZ",
        "synthetic": True,
        "audio_asset": None,
        "utterance": utterance,
        "expected_tools": TOOLS[turn],
        "expected_effect": effects[turn - 1],
        "expected_slots": slots[turn - 1],
    } for turn, utterance in enumerate(utterances, start=1)]


def build() -> list[dict[str, object]]:
    return [row for index in range(1, 101) for row in story(index)]


def validate(rows: list[dict[str, object]]) -> None:
    if len(rows) != 1000:
        raise ValueError(f"expected 1000 scenarios, got {len(rows)}")
    if len({row["scenario_id"] for row in rows}) != 1000 or len({row["utterance"] for row in rows}) != 1000:
        raise ValueError("scenario IDs and utterances must be unique")
    if any(row["locale"] != "az-AZ" or row["synthetic"] is not True or row["audio_asset"] is not None for row in rows):
        raise ValueError("all scenarios must be synthetic Azerbaijani text without audio")
    if any(re.search(r"[\u0400-\u04FF]|[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}|https?://|AIza[0-9A-Za-z_-]{20,}", row["utterance"]) for row in rows):
        raise ValueError("found Cyrillic text or a possible secret/contact/URL")
    stories = Counter(row["story_id"] for row in rows)
    if len(stories) != 100 or any(count != 10 for count in stories.values()):
        raise ValueError("expected 100 related 10-turn stories")
    for story_id in stories:
        turns = [row["turn"] for row in rows if row["story_id"] == story_id]
        if sorted(turns) != list(range(1, 11)):
            raise ValueError(f"invalid turn sequence in {story_id}")
    if Counter(row["expected_effect"] for row in rows) != Counter({
        "receipt_created": 100, "receipt_annotated_without_duplicate": 100,
        "debt_created_and_receipt_linked": 100, "receipt_corrected": 100,
        "ledger_balance_read": 100, "source_linked_episode_saved": 100,
        "episode_retrieved_from_memory": 100, "task_created_with_reminder": 100,
        "calendar_event_created": 100, "cross_domain_evidence_answered_without_write": 100,
    }):
        raise ValueError("unexpected scenario/effect balance")


def render(rows: list[dict[str, object]]) -> str:
    return "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    try:
        rows = build()
        validate(rows)
        generated = render(rows)
        if args.check:
            if not OUTPUT.exists() or OUTPUT.read_text(encoding="utf-8") != generated:
                raise ValueError("checked-in live corpus differs from deterministic generator")
        else:
            OUTPUT.parent.mkdir(parents=True, exist_ok=True)
            OUTPUT.write_text(generated, encoding="utf-8")
        print("az_live_scenarios=valid count=1000 stories=100 turns_per_story=10 locale=az-AZ audio=0 synthetic=1000")
        return 0
    except (OSError, ValueError) as error:
        print(f"az_live_scenarios=invalid reason={error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
