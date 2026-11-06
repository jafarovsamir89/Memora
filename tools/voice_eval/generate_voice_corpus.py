#!/usr/bin/env python3
"""Generate the deterministic, text-only Memora voice evaluation corpus."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "docs/voice-evaluation/voice-scenarios-v1.jsonl"
LANGUAGES = ("az", "ru", "en", "mixed")
CATEGORIES = (
    "chat_no_write",
    "note_save",
    "receipt",
    "expense",
    "debt_direction_ambiguous",
    "task_create",
    "calendar_create",
    "memory_search",
    "negated_transaction",
    "ambiguous_transaction",
)
NAMES = (
    "Aylin-01", "Murad-02", "Nigar-03", "Tural-04", "Aysu-05",
    "Orxan-06", "Lalə-07", "Rauf-08", "Nərmin-09", "Elvin-10",
    "Sevda-11", "Fuad-12", "Günel-13", "Rəşad-14", "Mələk-15",
    "Emin-16", "Zəhra-17", "Cavid-18", "Fidan-19", "Anar-20",
    "Ləman-21", "Vüsal-22", "Afaq-23", "Kənan-24", "İlahə-25",
)
TOPICS = {
    "az": ("qısa fasilə", "günün planı", "iş masasının təşkili", "su içmək", "səhər rutini",
           "faylları nizamlamaq", "vaxtı bölmək", "sadə nahar", "gəzinti", "oxu fasiləsi",
           "yeni söz öyrənmək", "sakit iş mühiti", "həftəlik plan", "qısa qeydlər", "diqqəti toplamaq",
           "işdən sonra istirahət", "səyahət siyahısı", "evdə bitkilər", "yüngül idman", "məktub yazmaq",
           "kiçik məqsədlər", "səhər hazırlığı", "kitab rəfi", "təqvimdən istifadə", "işləri bölmək"),
    "ru": ("короткий перерыв", "план дня", "порядок на столе", "питьевой режим", "утренняя привычка",
           "порядок в файлах", "распределение времени", "простой обед", "прогулка", "перерыв на чтение",
           "изучение слов", "спокойная рабочая среда", "план недели", "короткие заметки", "концентрация",
           "отдых после работы", "список для поездки", "домашние растения", "лёгкая зарядка", "написание письма",
           "маленькие цели", "утренние сборы", "книжная полка", "ведение календаря", "разделение задач"),
    "en": ("a short break", "planning the day", "organizing a desk", "drinking water", "a morning routine",
           "sorting files", "time blocking", "a simple lunch", "taking a walk", "a reading break",
           "learning a word", "a calm workspace", "weekly planning", "short notes", "regaining focus",
           "rest after work", "a travel checklist", "indoor plants", "light exercise", "writing a letter",
           "small goals", "getting ready in the morning", "a bookshelf", "using a calendar", "breaking down tasks"),
}
NOTES = {
    "az": ("mavi qovluq rəfində saxlanmalıdır", "nümunə sifariş kodu Q-01-dir", "test cədvəli cümə günü yoxlanacaq",
           "ehtiyat siyahısına lampa əlavə et", "sintetik layihə qeydi A-01-dir"),
    "ru": ("синяя папка хранится на полке", "код тестового заказа Q-01", "проверить таблицу в пятницу",
           "добавить лампу в список запаса", "синтетическая заметка проекта A-01"),
    "en": ("keep the blue folder on the shelf", "the test order code is Q-01", "review the sample table on Friday",
           "add a lamp to the supply list", "the synthetic project note is A-01"),
}
TASKS = {
    "az": ("sintetik nümunəni yoxla", "mavi qovluğu sırala", "test siyahısını tamamla", "ehtiyat kodunu yoxla",
           "layihə qeydini nəzərdən keçir"),
    "ru": ("проверить синтетический образец", "разложить синюю папку", "закончить тестовый список",
           "проверить запасной код", "просмотреть заметку проекта"),
    "en": ("check the synthetic sample", "sort the blue folder", "finish the test checklist",
           "verify the spare code", "review the project note"),
}
EVENTS = {
    "az": ("sintetik təchizat görüşü", "nümunə sifariş zəngi", "test cədvəli baxışı", "qovluq yoxlama görüşü",
           "layihə planı müzakirəsi"),
    "ru": ("встреча по синтетической поставке", "звонок по тестовому заказу", "проверка образца таблицы",
           "встреча по папке", "обсуждение плана проекта"),
    "en": ("synthetic supply meeting", "test order call", "sample table review", "folder review meeting",
           "project plan discussion"),
}


def amount_for(index: int) -> str:
    return f"{index}.{index:02d}"


def date_for(index: int) -> str:
    return f"2026-12-{index:02d}"


def time_for(index: int) -> str:
    return f"{8 + (index % 12):02d}:{(index % 4) * 15:02d}"


def scenario_text(language: str, category: str, index: int) -> tuple[str, dict[str, object]]:
    base = language if language != "mixed" else "az"
    name, amount, date, time = NAMES[index - 1], amount_for(index), date_for(index), time_for(index)
    topic = TOPICS[base][index - 1]
    note = NOTES[base][(index - 1) % len(NOTES[base])] + f" [{index:02d}]"
    task = TASKS[base][(index - 1) % len(TASKS[base])] + f" [{index:02d}]"
    event = EVENTS[base][(index - 1) % len(EVENTS[base])] + f" [{index:02d}]"

    if category == "chat_no_write":
        text = {
            "az": f"Salam, {topic} barədə bir qısa, ümumi məsləhət ver; heç nəyi yadda saxlama.",
            "ru": f"Привет. Дай один короткий общий совет про тему «{topic}»; ничего не сохраняй.",
            "en": f"Hi. Give me one short general tip about {topic}; do not save anything.",
            "mixed": f"Salam, {topic} barədə bir qısa məsləhət ver. Keep it general; heç nəyi yadda saxlama.",
        }[language]
        expected, tools, slots, must_not_write = "answer_only", [], {}, True
    elif category == "note_save":
        text = {
            "az": f"Ayrıca qeyd kimi saxla: {note}.",
            "ru": f"Сохрани отдельной заметкой: {note}.",
            "en": f"Save this as a standalone note: {note}.",
            "mixed": f"Ayrıca qeyd kimi saxla: {note}. Save it exactly as one note.",
        }[language]
        expected, tools, slots, must_not_write = "save_note", ["save_note"], {"body": note, "kind": "note"}, False
    elif category in ("receipt", "expense"):
        kind = "receipt" if category == "receipt" else "expense"
        text = {
            "az": (f"Sintetik nümunə: {name} mənə {amount} AZN ödədi, {date} tarixində gəlir kimi qeyd et."
                   if kind == "receipt" else f"Sintetik nümunə: {date} tarixində {name} üçün {amount} AZN xərclədim, xərc kimi qeyd et."),
            "ru": (f"Синтетический пример: {name} заплатил мне {amount} AZN {date}; запиши как поступление."
                   if kind == "receipt" else f"Синтетический пример: я потратил {amount} AZN для {name} {date}; запиши как расход."),
            "en": (f"Synthetic test: {name} paid me {amount} AZN on {date}; record it as a receipt."
                   if kind == "receipt" else f"Synthetic test: I spent {amount} AZN for {name} on {date}; record it as an expense."),
            "mixed": (f"Sintetik nümunə: {name} mənə {amount} AZN ödədi {date}; record as a receipt."
                      if kind == "receipt" else f"Sintetik nümunə: {date} {name} üçün {amount} AZN xərclədim; record as an expense."),
        }[language]
        expected, tools, slots, must_not_write = f"record_{kind}", ["record_money_event"], {
            "kind": kind, "person_name": name, "amount": amount, "currency": "AZN", "occurred_date": date,
        }, False
    elif category == "debt_direction_ambiguous":
        text = {
            "az": f"Sintetik qeyd: {name} ilə aramızda {amount} AZN borc var, amma kimin kimə borclu olduğu bilinmir. Əvvəl soruş, heç nə qeyd etmə.",
            "ru": f"Синтетическая запись: между мной и {name} долг {amount} AZN, но неясно, кто кому должен. Сначала уточни, ничего не записывай.",
            "en": f"Synthetic note: {name} and I have a {amount} AZN debt, but who owes whom is unclear. Ask first; record nothing.",
            "mixed": f"Sintetik qeyd: {name} ilə {amount} AZN borc var, who owes whom bilinmir. Əvvəl soruş; heç nə qeyd etmə.",
        }[language]
        expected, tools, slots, must_not_write = "clarify_no_write", [], {"amount": amount, "person_name": name}, True
    elif category == "task_create":
        text = {
            "az": f"Yadıma sal: {task}, {date} tarixində saat {time}.",
            "ru": f"Напомни мне: {task}, {date} в {time}.",
            "en": f"Remind me to {task} on {date} at {time}.",
            "mixed": f"Yadıma sal: {task}, {date} saat {time}. Please create one task with a reminder.",
        }[language]
        due = f"{date}T{time}"
        expected, tools, slots, must_not_write = "create_task", ["create_task"], {
            "title": task, "due_local": due, "zone_id": "Asia/Baku", "remind": True,
        }, False
    elif category == "calendar_create":
        text = {
            "az": f"Təqvimə yaz: {event}, {date} saat {time}.",
            "ru": f"Добавь в календарь: {event}, {date} в {time}.",
            "en": f"Add this to my calendar: {event} on {date} at {time}.",
            "mixed": f"Təqvimə yaz: {event}, {date} saat {time}. Add one calendar event.",
        }[language]
        expected, tools, slots, must_not_write = "create_calendar_event", ["create_calendar_event"], {
            "title": event, "start_local": f"{date}T{time}", "zone_id": "Asia/Baku", "precision": "minute",
        }, False
    elif category == "memory_search":
        text = {
            "az": f"Yaddaşda {topic} barədə əvvəldən nə demişəm? Mənbə yoxdursa, bilmədiyini de.",
            "ru": f"Что я раньше говорил про «{topic}»? Если источника нет, скажи, что не знаешь.",
            "en": f"What did I previously say about {topic}? If there is no stored source, say you do not know.",
            "mixed": f"Yaddaşda {topic} barədə nə demişəm? If there is no source, don't invent an answer.",
        }[language]
        expected, tools, slots, must_not_write = "search_memory", ["search_memory"], {"query": topic}, True
    elif category == "negated_transaction":
        text = {
            "az": f"Mən {name}-ə {amount} AZN ödəmədim. Bu xərci qeydə alma; heç nə yazma.",
            "ru": f"Я не платил {name} {amount} AZN. Не записывай этот расход и ничего не меняй.",
            "en": f"I did not pay {name} {amount} AZN. Do not record this expense or change anything.",
            "mixed": f"Mən {name}-ə {amount} AZN ödəmədim. Do not record it; heç nəyi dəyişmə.",
        }[language]
        expected, tools, slots, must_not_write = "no_write", [], {"amount": amount, "person_name": name}, True
    else:
        text = {
            "az": f"{name} ilə bağlı {amount} AZN var, amma mənə gəldi, yoxsa mən xərclədim, yadımda deyil. Əvvəl dəqiqləşdir, yazma.",
            "ru": f"Есть {amount} AZN по {name}, но я не помню, получил я их или потратил. Сначала уточни, не записывай.",
            "en": f"There is {amount} AZN involving {name}, but I cannot tell whether I received or spent it. Ask first; do not record.",
            "mixed": f"{name} ilə {amount} AZN var, received or spent yadımda deyil. Əvvəl soruş; nothing yazma.",
        }[language]
        expected, tools, slots, must_not_write = "clarify_no_write", [], {"amount": amount, "person_name": name}, True

    return text, {
        "expected_action": expected,
        "expected_tools": tools,
        "expected_slots": slots,
        "must_not_write": must_not_write,
    }


def build_corpus() -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    locale = {"az": "az-AZ", "ru": "ru-RU", "en": "en-US", "mixed": "az-AZ"}
    for language in LANGUAGES:
        for category in CATEGORIES:
            for index in range(1, 26):
                utterance, expectation = scenario_text(language, category, index)
                scenario_id = f"VOICE-{language.upper()}-{category.upper()}-{index:03d}"
                rows.append({
                    "schema_version": 1,
                    "scenario_id": scenario_id,
                    "language_group": language,
                    "locale": locale[language],
                    "category": category,
                    "variant": index,
                    # One of each five lexical slot variants is reserved in every language/category cell.
                    "split": "holdout" if index in {1, 7, 13, 19, 25} else "development",
                    "synthetic": True,
                    "audio_asset": None,
                    "utterance": utterance,
                    "reference_transcript": utterance,
                    **expectation,
                })
    return rows


def validate(rows: list[dict[str, object]]) -> None:
    if len(rows) != 1000:
        raise ValueError(f"expected 1000 scenarios, got {len(rows)}")
    ids = [row["scenario_id"] for row in rows]
    texts = [row["utterance"] for row in rows]
    if len(set(ids)) != 1000 or len(set(texts)) != 1000:
        raise ValueError("scenario IDs and utterances must be unique")
    language_counts = Counter(row["language_group"] for row in rows)
    category_counts = Counter(row["category"] for row in rows)
    split_counts = Counter(row["split"] for row in rows)
    if language_counts != Counter({language: 250 for language in LANGUAGES}):
        raise ValueError(f"unbalanced language groups: {language_counts}")
    if category_counts != Counter({category: 100 for category in CATEGORIES}):
        raise ValueError(f"unbalanced categories: {category_counts}")
    if split_counts != Counter({"development": 800, "holdout": 200}):
        raise ValueError(f"unexpected split: {split_counts}")
    if any(row["reference_transcript"] != row["utterance"] for row in rows):
        raise ValueError("text-only reference transcript must match its scripted utterance")
    if any(row["synthetic"] is not True or row["audio_asset"] is not None for row in rows):
        raise ValueError("corpus must stay synthetic and contain no audio files")
    if any(not row["utterance"] or len(row["utterance"]) > 500 for row in rows):
        raise ValueError("utterance is empty or exceeds the bounded length")
    if any(re.search(r"\b[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}\b|https?://|AIza[0-9A-Za-z_-]{20,}", str(row["utterance"])) for row in rows):
        raise ValueError("possible email, URL, or API key in synthetic text")
    for language in LANGUAGES:
        for category in CATEGORIES:
            cell = [r for r in rows if r["language_group"] == language and r["category"] == category]
            if len(cell) != 25 or sum(r["split"] == "holdout" for r in cell) != 5:
                raise ValueError(f"invalid cell/split: {language}/{category}")
            for row in cell:
                writing = row["expected_action"] in {"save_note", "record_receipt", "record_expense", "create_task", "create_calendar_event"}
                if row["must_not_write"] == writing:
                    raise ValueError(f"write policy mismatch in {row['scenario_id']}")


def render(rows: list[dict[str, object]]) -> str:
    return "".join(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n" for row in rows)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="validate the checked-in corpus without rewriting it")
    args = parser.parse_args()
    try:
        rows = build_corpus()
        validate(rows)
        generated = render(rows)
        if args.check:
            if not OUTPUT.exists() or OUTPUT.read_text(encoding="utf-8") != generated:
                raise ValueError("checked-in corpus differs from deterministic generator")
        else:
            OUTPUT.parent.mkdir(parents=True, exist_ok=True)
            OUTPUT.write_text(generated, encoding="utf-8")
        print("voice_corpus=passed scenarios=1000 languages=4 categories=10 development=800 holdout=200 audio_files=0")
        return 0
    except (OSError, ValueError) as error:
        print(f"voice_corpus=failed reason={error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
