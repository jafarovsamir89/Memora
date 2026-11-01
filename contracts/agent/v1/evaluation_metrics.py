"""Aggregate privacy-minimal agent evaluation outcomes.

The evaluator consumes labels and outcomes only; it must never receive utterance
text, transcripts, user records, provider payloads, or device identifiers.
It reports descriptive metrics and does not itself certify product acceptance.
"""

from __future__ import annotations

from dataclasses import dataclass
from math import ceil
from statistics import median
from typing import Iterable


LANGUAGE_SLICES = {"az", "ru", "mixed"}
ROUTES = {"text", "audio", "multi_turn"}
EXPECTED = {"success", "clarify_or_refuse", "no_effect"}
OBSERVED = {"success", "clarify", "refuse", "no_effect", "wrong_effect", "failed"}


@dataclass(frozen=True)
class EvalObservation:
    case_id: str
    paraphrase_group: str
    split: str
    language: str
    route: str
    expected: str
    observed: str
    critical_fields_match: bool
    unauthorized_action: bool = False
    retry_loss_or_duplicate: bool = False
    financial_arithmetic_error: bool = False
    false_success_claim: bool = False
    deterministic_pass: bool = True
    latency_ms: int | None = None


def summarize(observations: Iterable[EvalObservation], plan: dict) -> dict:
    rows = list(observations)
    if not rows:
        raise ValueError("evaluation set is empty")
    ids = [row.case_id for row in rows]
    if len(set(ids)) != len(ids) or any(not value for value in ids):
        raise ValueError("case IDs must be non-empty and unique")
    if any(row.split not in {"development", "holdout"} for row in rows):
        raise ValueError("unknown split")
    if any(row.language not in LANGUAGE_SLICES for row in rows):
        raise ValueError("unknown language slice")
    if any(row.route not in ROUTES for row in rows):
        raise ValueError("unknown evaluation route")
    if any(row.expected not in EXPECTED or row.observed not in OBSERVED for row in rows):
        raise ValueError("unknown expected or observed outcome")
    if any(row.latency_ms is not None and row.latency_ms < 0 for row in rows):
        raise ValueError("latency must be non-negative")

    groups: dict[str, set[str]] = {}
    for row in rows:
        groups.setdefault(row.paraphrase_group, set()).add(row.split)
    if any(not group or len(splits) != 1 for group, splits in groups.items()):
        raise ValueError("paraphrase groups must stay within one split")

    holdout = [row for row in rows if row.split == "holdout"]
    if not holdout:
        raise ValueError("holdout split is empty")
    text = [row for row in holdout if row.route == "text"]
    audio = [row for row in holdout if row.route == "audio"]
    chains = [row for row in holdout if row.route == "multi_turn"]

    language_counts = {lang: sum(row.language == lang for row in text) for lang in LANGUAGE_SLICES}
    audio_counts = {lang: sum(row.language == lang for row in audio) for lang in LANGUAGE_SLICES}
    ambiguous_percent = {}
    for lang in LANGUAGE_SLICES:
        slice_rows = [row for row in text if row.language == lang]
        uncertain = sum(row.expected == "clarify_or_refuse" for row in slice_rows)
        ambiguous_percent[lang] = _percent(uncertain, len(slice_rows))

    disjoint_cases = {
        row.case_id for row in rows if row.split == "development"
    }.isdisjoint({row.case_id for row in holdout})
    min_text = plan["minimumHoldoutText"]
    min_audio = plan["minimumHoldoutSyntheticAudio"]
    minimums_met = (
        all(language_counts[lang] >= min_text[lang] for lang in LANGUAGE_SLICES)
        and all(audio_counts[lang] >= min_audio[lang] for lang in LANGUAGE_SLICES)
        and len(chains) >= plan["minimumMultiTurnChains"]
        and all(ambiguous_percent[lang] >= plan["minimumNegativeOrAmbiguousPercentEachTextSlice"]
                for lang in LANGUAGE_SLICES)
    )

    def success_rate(group: list[EvalObservation]) -> float | None:
        eligible = [row for row in group if row.expected == "success"]
        return _percent(sum(row.observed == "success" and row.critical_fields_match for row in eligible), len(eligible))

    appropriate = [row for row in text if row.expected == "clarify_or_refuse"]
    appropriate_rate = _percent(sum(row.observed in {"clarify", "refuse", "no_effect"} for row in appropriate), len(appropriate))
    audio_success = {lang: success_rate([row for row in audio if row.language == lang]) for lang in LANGUAGE_SLICES}
    text_success = {lang: success_rate([row for row in text if row.language == lang]) for lang in LANGUAGE_SLICES}
    latencies = [row.latency_ms for row in holdout if row.latency_ms is not None]
    critical_errors = {
        "unauthorizedActions": sum(row.unauthorized_action for row in holdout),
        "retryLossOrDuplicateEffects": sum(row.retry_loss_or_duplicate for row in holdout),
        "financialArithmeticErrors": sum(row.financial_arithmetic_error for row in holdout),
        "falseSuccessClaims": sum(row.false_success_claim for row in holdout),
    }
    deterministic = _percent(sum(row.deterministic_pass for row in holdout), len(holdout))

    results = {
        "holdoutCaseCount": len(holdout),
        "textCounts": language_counts,
        "audioCounts": audio_counts,
        "multiTurnChainCount": len(chains),
        "ambiguousOrNegativePercent": ambiguous_percent,
        "textSuccessPercent": text_success,
        "audioEndToEndSuccessPercent": audio_success,
        "appropriateAmbiguousHandlingPercent": appropriate_rate,
        "deterministicPassPercent": deterministic,
        "criticalErrors": critical_errors,
        "latencyMs": {"count": len(latencies), "median": median(latencies) if latencies else None,
                       "p95": _percentile(latencies, 0.95)},
        "holdoutDisjointByParaphraseGroup": disjoint_cases and all(
            len(groups.get(row.paraphrase_group, set())) == 1 for row in rows
        ),
    }
    results["thresholdsMet"] = _meets_plan(results, plan, minimums_met)
    # No utterance text, transcript, provider response, or device identity is serialized.
    return results


def _percent(numerator: int, denominator: int) -> float | None:
    return None if denominator == 0 else round(100.0 * numerator / denominator, 2)


def _percentile(values: list[int], q: float) -> int | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, ceil(q * len(ordered)) - 1)]


def _meets_plan(report: dict, plan: dict, minimums_met: bool) -> bool:
    if not minimums_met or not report["holdoutDisjointByParaphraseGroup"]:
        return False
    if report["deterministicPassPercent"] < plan["minDeterministicPassPercent"]:
        return False
    if report["appropriateAmbiguousHandlingPercent"] is None or report["appropriateAmbiguousHandlingPercent"] < plan["minAppropriateAmbiguousHandlingPercent"]:
        return False
    if any(value is None or value < plan["minUnambiguousTextSuccessPercentEachSlice"]
           for value in report["textSuccessPercent"].values()):
        return False
    if any(value is None or value < plan["minSyntheticAudioEndToEndPercentEachSlice"]
           for value in report["audioEndToEndSuccessPercent"].values()):
        return False
    critical = report["criticalErrors"]
    return all(critical[key] <= plan[plan_key] for key, plan_key in {
        "unauthorizedActions": "maxUnauthorizedActions",
        "retryLossOrDuplicateEffects": "maxRetryLossOrDuplicateEffects",
        "financialArithmeticErrors": "maxFinancialArithmeticErrors",
        "falseSuccessClaims": "maxFalseSuccessClaims",
    }.items())
