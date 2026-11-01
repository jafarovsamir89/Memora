"""P0 contract fixtures only: this does not implement an agent or grant permissions."""
import copy
import hashlib
import datetime
from importlib.resources import files
import json
import unittest
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError, reset_tzpath

from jsonschema import Draft202012Validator, FormatChecker
from canonical import canonical_bytes, command_digest

ROOT = Path(__file__).resolve().parents[2] / "contracts" / "agent" / "v1"
# Keep DST fixtures deterministic across operating systems and tzdb updates.
reset_tzpath((str(files("tzdata").joinpath("zoneinfo")),))
ZoneInfo.clear_cache()


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def invalid_number(_value):
    raise ValueError("non-integer number is forbidden in this contract slice")


def read_json(text):
    return json.loads(text, object_pairs_hook=strict_object, parse_float=invalid_number, parse_constant=invalid_number)


def read_fixture(name):
    return read_json((ROOT / "fixtures" / name).read_text(encoding="utf-8"))


def text_valid(text, maximum):
    try:
        return bool(text.strip()) and len(text.encode("utf-16-le", errors="strict")) // 2 <= maximum
    except UnicodeEncodeError:
        return False


def task_title_valid(text):
    try:
        text.encode("utf-16-le", errors="strict")
        return bool(text.strip()) and len(text) <= 200
    except UnicodeEncodeError:
        return False


def check_due(due):
    if due is None or due["precision"] == "date":
        return
    try:
        local = datetime.datetime.strptime(due["localDateTime"], "%Y-%m-%dT%H:%M")
        zone = ZoneInfo(due["zoneId"])
        offsets = set()
        for fold in (0, 1):
            candidate = local.replace(tzinfo=zone, fold=fold)
            if candidate.astimezone(datetime.timezone.utc).astimezone(zone).replace(tzinfo=None) == local:
                offsets.add(candidate.utcoffset())
        if len(offsets) != 1:
            raise ValueError("local time is ambiguous or nonexistent")
    except (ZoneInfoNotFoundError, ValueError) as error:
        raise ValueError("invalid local date, zone, or DST boundary") from error


def check_event_window(start, end):
    if start["precision"] != end["precision"]:
        raise ValueError("event start and end precision must match")
    if start["precision"] == "date":
        if start["date"] >= end["date"]:
            raise ValueError("event end date must be after start date")
        return
    if start["zoneId"] != end["zoneId"]:
        raise ValueError("event start and end must use one explicit time zone")
    check_due(start)
    check_due(end)
    if start["localDateTime"] >= end["localDateTime"]:
        raise ValueError("event end must be after start")


def validate_semantics(kind, obj):
    if kind in ("model-tool-call", "command-envelope"):
        args = obj["arguments"]
        if kind=="command-envelope":
            target_ids=[item["id"] for item in obj["expectedVersions"]]
            if len(target_ids)!=len(set(target_ids)):
                raise ValueError("duplicate expected target version")
        if kind == "command-envelope" and obj["payloadDigest"] != command_digest(obj):
            raise ValueError("command digest does not match content")
        if obj["toolName"] == "create_note":
            if not text_valid(args["title"], 200) or not text_valid(args["body"], 20000):
                raise ValueError("invalid Unicode, whitespace-only text, or UTF-16 length")
            if kind == "command-envelope":
                if args["noteRevisionId"] == obj["sourceRevisionId"]:
                    raise ValueError("new revision cannot reuse the request source revision")
                ids = [item["id"] for item in obj["expectedVersions"]]
                if len(ids) != len(set(ids)):
                    raise ValueError("duplicate expected version target")
                links = {item["id"] for item in args["links"]}
                if set(ids) != links:
                    raise ValueError("expected versions must cover linked targets")
        if obj["toolName"] == "update_note":
            for field, limit in (("title", 200), ("body", 20000)):
                if field in args["patch"] and not text_valid(args["patch"][field], limit):
                    raise ValueError("invalid Unicode, whitespace-only text, or UTF-16 length")
            if kind == "command-envelope":
                expected = obj["expectedVersions"]
                if len(expected) != 1 or expected[0]["id"] != args["noteId"]:
                    raise ValueError("update must bind the exact target version")
                if args["newRevisionId"] == obj["sourceRevisionId"]:
                    raise ValueError("new revision cannot reuse the request source revision")
        if obj["toolName"] == "search_memory":
            if not text_valid(args["query"], 2000):
                raise ValueError("invalid query")
            period = args.get("period", {})
            if "from" in period and "untilExclusive" in period and period["from"] >= period["untilExclusive"]:
                raise ValueError("invalid half-open period")
        if obj["toolName"] == "resolve_person":
            if not text_valid(args["name"], 200) or ("context" in args and not text_valid(args["context"], 500)):
                raise ValueError("invalid person query")
        if obj["toolName"] == "query_finance":
            period = args.get("period")
            if period and period["from"] >= period["untilExclusive"]:
                raise ValueError("finance period must be non-empty and half-open")
        if obj["toolName"] == "read_records":
            keys = [(item["type"], item["id"]) for item in args["records"]]
            if len(keys) != len(set(keys)):
                raise ValueError("duplicate requested record")
            allowed = set()
            if any(kind == "note" for kind, _ in keys): allowed.update(("title", "body", "source", "revision"))
            if any(kind == "person" for kind, _ in keys): allowed.update(("name", "source"))
            if not set(args["fields"]).issubset(allowed):
                raise ValueError("fields do not apply to requested record types")
        if obj["toolName"] in ("create_person", "update_person", "create_project", "update_project"):
            data = args.get("patch", args)
            for field in ("name", "title"):
                if field in data and not text_valid(data[field], 200):
                    raise ValueError("invalid name or title")
            if "aliases" in data and any(not text_valid(alias, 200) for alias in data["aliases"]):
                raise ValueError("invalid alias")
            if kind == "command-envelope":
                expected = obj["expectedVersions"]
                target = args["personId"] if "person" in obj["toolName"] else args["projectId"]
                if obj["toolName"].startswith("create_") and expected:
                    raise ValueError("new object cannot have expected target version")
                if obj["toolName"].startswith("update_") and (len(expected) != 1 or expected[0]["id"] != target):
                    raise ValueError("update must bind the exact target version")
        if obj["toolName"] == "link_records":
            endpoints = {args["from"]["id"], args["to"]["id"]}
            if len(endpoints) != 2:
                raise ValueError("self-link or conflicting endpoint identity")
            if kind == "command-envelope":
                expected = [item["id"] for item in obj["expectedVersions"]]
                if len(expected) != 2 or set(expected) != endpoints or args["linkId"] in endpoints:
                    raise ValueError("link must bind both endpoint versions and a new ID")
        if obj["toolName"] == "unlink_records" and kind == "command-envelope":
            expected = obj["expectedVersions"]
            if len(expected) != 1 or expected[0]["id"] != args["linkId"]:
                raise ValueError("unlink must bind exact link version")
        if obj["toolName"] == "preview_delete":
            ids = [item["id"] for item in args["targets"]]
            if len(ids) != len(set(ids)):
                raise ValueError("duplicate deletion target")
        if obj["toolName"] == "commit_delete" and kind == "command-envelope":
            ids = [item["id"] for item in obj["expectedVersions"]]
            if len(ids) != len(set(ids)):
                raise ValueError("duplicate deletion version")
        if obj["toolName"] in ("create_task", "update_task"):
            data = args.get("patch", args)
            if "title" in data and not task_title_valid(data["title"]):
                raise ValueError("invalid task title")
            if "due" in data:
                check_due(data["due"])
            if data.get("remind") is True and (data.get("due") is None or data["due"]["precision"] != "minute"):
                raise ValueError("explicit time and zone required for reminder")
            if data.get("status") == "DONE" and data.get("remind") is True:
                raise ValueError("completed task cannot request an active reminder")
            if obj["toolName"] == "update_task" and "due" in data and (data["due"] is None or data["due"]["precision"] == "date") and data.get("remind") is not False:
                raise ValueError("explicit reminder cancellation required when removing its time")
            if kind == "command-envelope":
                expected = [item["id"] for item in obj["expectedVersions"]]
                target = {args["taskId"]} if obj["toolName"] == "update_task" else {x["id"] for x in args["links"]}
                if len(expected) != len(target) or set(expected) != target:
                    raise ValueError("task command versions do not match existing targets")
                if obj["toolName"] == "create_task" and args["taskId"] in target:
                    raise ValueError("task ID collides with linked target")
        if obj["toolName"] == "create_calendar_event":
            check_event_window(args["start"], args["end"])
        if obj["toolName"] == "update_calendar_event" and "start" in args["patch"] and "end" in args["patch"]:
            check_event_window(args["patch"]["start"], args["patch"]["end"])
        if obj["toolName"] == "prepare_changes":
            operations = args["operations"]
            ids = [item["operationId"] for item in operations]
            if len(ids) != len(set(ids)):
                raise ValueError("duplicate prepared operation id")
            by_id = {item["operationId"]: set(item["dependsOn"]) for item in operations}
            if any(dep not in by_id or dep == op for op, deps in by_id.items() for dep in deps):
                raise ValueError("unknown or self dependency in plan")
            visiting, visited = set(), set()
            def visit(node):
                if node in visiting: raise ValueError("cycle in prepared plan")
                if node in visited: return
                visiting.add(node)
                for dep in by_id[node]: visit(dep)
                visiting.remove(node); visited.add(node)
            for node in ids: visit(node)
            for group in args.get("atomicGroups", []):
                if not set(group).issubset(set(ids)):
                    raise ValueError("atomic group references unknown operation")
        if obj["toolName"] == "create_table":
            cols=args["columns"]
            ids=[col.get("columnId") for col in cols if "columnId" in col]
            if kind=="command-envelope" and (len(ids)!=len(cols) or len(set(ids))!=len(ids)):
                raise ValueError("application must assign distinct IDs to all new columns")
            if args["mode"]=="linked_view" and "viewSpec" not in args:
                raise ValueError("linked view requires a typed view specification")
            if args["mode"]=="editable" and "viewSpec" in args:
                raise ValueError("editable table cannot carry a linked view specification")
            for col in cols:
                if col["type"]=="enum" and not col.get("enumValues"):
                    raise ValueError("enum column requires explicit values")
                if col["type"]=="reference" and not col.get("referenceType"):
                    raise ValueError("reference column requires an allowed entity type")
        if obj["toolName"] in ("add_table_rows", "update_table_rows", "change_table_schema") and kind=="command-envelope":
            table_id=args["tableId"];schema_version=args["expectedSchemaVersion"]
            expected={item["id"]:item["version"] for item in obj["expectedVersions"]}
            wanted={table_id:schema_version}
            if obj["toolName"]=="update_table_rows":
                for row in args["rows"]:
                    if row["rowId"] in wanted:
                        raise ValueError("row ID collides with table ID")
                    wanted[row["rowId"]]=row["expectedVersion"]
            if expected!=wanted:
                raise ValueError("table command versions must cover the schema and every target row")
            if obj["toolName"]=="add_table_rows":
                ids=args["rowIds"]
                if len(ids)!=len(args["rows"]) or len(ids)!=len(set(ids)) or table_id in ids:
                    raise ValueError("application row IDs must uniquely match each new row")
                for row in args["rows"]:
                    cols=[cell["columnId"] for cell in row["cells"]]
                    if len(cols)!=len(set(cols)):
                        raise ValueError("duplicate column cell in a row")
            if obj["toolName"]=="update_table_rows":
                for row in args["rows"]:
                    cols=[cell["columnId"] for cell in row["patch"]]
                    if len(cols)!=len(set(cols)):
                        raise ValueError("duplicate column in row patch")
        if kind == "command-envelope" and obj["toolName"] in ("record_receipt", "record_obligation", "record_settlement", "correct_money_event"):
            target = args["personId"] if obj["toolName"] in ("record_receipt", "record_obligation") else (args["obligationId"] if obj["toolName"] == "record_settlement" else args["targetEventId"])
            if len(obj["expectedVersions"]) != 1 or obj["expectedVersions"][0]["id"] != target:
                raise ValueError("money command must bind its exact target version")
            if obj["toolName"] in ("record_settlement", "correct_money_event") and not obj["confirmationId"]:
                raise ValueError("money settlement/correction requires confirmation")
            if obj["toolName"]=="record_settlement" and args["amountMinorUnits"]>args["expectedRemainingMinorUnits"]:
                raise ValueError("settlement cannot silently overpay the versioned outstanding balance")
        if kind=="command-envelope" and obj["toolName"]=="restore_records":
            expected={item["id"]:item["version"] for item in obj["expectedVersions"]}
            wanted={item["recordId"]:item["expectedTrashVersion"] for item in args["records"]}
            if expected!=wanted:
                raise ValueError("restore command must bind every exact trash version")
        if kind=="command-envelope" and obj["toolName"]=="undo_change" and not obj["expectedVersions"]:
            raise ValueError("undo command must bind affected object versions")
        if kind=="command-envelope" and obj["toolName"]=="update_calendar_event":
            expected=obj["expectedVersions"]
            if len(expected)!=1 or expected[0]["id"]!=args["eventId"]:
                raise ValueError("calendar update must bind its exact target version")
    if kind == "tool-result" and obj["status"] in ("committed", "no_change", "replayed"):
        note = obj["data"]
        if note["kind"] == "deletion":
            values = [(x["id"], x["version"]) for x in note["targets"] + note["deactivatedLinks"]]
            affected = [(x["id"], x["version"]) for x in obj["affected"]]
            if len({x[0] for x in values}) != len(values) or len({x[0] for x in affected}) != len(affected) or set(values) != set(affected):
                raise ValueError("deletion targets and affected versions disagree")
        elif note["kind"] == "restored_records":
            values=[(x["id"],x["version"]) for x in note["records"]]
            affected=[(x["id"],x["version"]) for x in obj["affected"]]
            if len(values)!=len(set(values)) or set(values)!=set(affected):
                raise ValueError("restoration result must cover every restored record")
        elif note["kind"] == "undo_applied":
            values=[(x["id"],x["version"]) for x in note["compensated"]]
            affected=[(x["id"],x["version"]) for x in obj["affected"]]
            if len(values)!=len(set(values)) or set(values)!=set(affected):
                raise ValueError("undo result must cover every compensated object")
        elif note["kind"] == "table_rows_changed":
            if len(note["rowIds"])!=len(note["versions"]):
                raise ValueError("row IDs and versions must be aligned")
            values=list(zip(note["rowIds"],note["versions"]))
            affected=[(x["id"],x["version"]) for x in obj["affected"]]
            if len(values)!=len(set(values)) or set(values)!=set(affected):
                raise ValueError("row batch result must cover all and only changed rows")
        elif note["kind"] in ("table_created","table_schema_changed"):
            if obj["affected"] != [{"id":note["tableId"],"version":note["schemaVersion"]}]:
                raise ValueError("table result version differs from affected table")
        elif note["kind"] == "calendar_event":
            if obj["affected"] != [{"id":note["eventId"],"version":note["version"]}]:
                raise ValueError("calendar result version differs from affected event")
        else:
            target_id = note["noteId"] if note["kind"] == "note" else note["id"]
            if obj["affected"] != [{"id": target_id, "version": note["version"]}]:
                raise ValueError("result and affected version disagree")
        if not obj["evidenceRefs"]:
            raise ValueError("committed note requires evidence")
    if kind == "tool-result" and obj["data"] and obj["data"]["kind"] in ("records", "person_candidates"):
        payload = obj["data"]
        rows = payload["items"] if payload["kind"] == "records" else payload["candidates"]
        keys = [(row["type"], row["id"]) for row in rows]
        errors = [(row["type"], row["id"]) for row in payload.get("errors", [])]
        if len(set(keys + errors)) != len(keys + errors):
            raise ValueError("duplicate or contradictory record result")
        for row in rows:
            for field, limit in (("title", 200), ("body", 20000), ("name", 200)):
                if field in row and not text_valid(row[field], limit):
                    raise ValueError("invalid result text")


def check(kind, obj):
    schema = json.loads((ROOT / (kind + ".schema.json")).read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    Draft202012Validator(schema, format_checker=FormatChecker()).validate(obj)
    validate_semantics(kind, obj)


def bind_note_result(command, result):
    """Cross-envelope integrity only; stored state, access and commit remain separate checks."""
    check("command-envelope", command)
    check("tool-result", result)
    if result["status"] not in ("committed", "no_change", "replayed"):
        raise ValueError("not a successful note result")
    if result["commandId"] != command["commandId"] or result["data"]["noteId"] != command["arguments"]["noteId"]:
        raise ValueError("result refers to a different command or note")
    validate_result_version(command, result)
    expected_revision = command["arguments"].get("noteRevisionId", command["arguments"].get("newRevisionId"))
    if result["status"] in ("committed", "replayed") and result["data"]["revisionId"] != expected_revision:
        raise ValueError("result refers to a different revision")
    if not set(result["evidenceRefs"]).issubset(command["evidenceRefs"]):
        raise ValueError("result claims unrelated evidence")


def bind_read_result(call, result):
    """Cross-check requested IDs and fields; access control remains a runtime responsibility."""
    check("model-tool-call", call)
    check("tool-result", result)
    if call["toolName"] != "read_records" or result["data"] is None or result["data"]["kind"] != "records" or result["callId"] != call["providerCallId"]:
        raise ValueError("result does not belong to this read")
    requested = {(r["type"], r["id"]) for r in call["arguments"]["records"]}
    returned = {(r["type"], r["id"]) for r in result["data"]["items"] + result["data"]["errors"]}
    if not returned.issubset(requested) or (result["completeness"] == "complete" and requested != returned):
        raise ValueError("read is unrequested or not complete")
    fields = set(call["arguments"]["fields"])
    if any(field in row and field not in fields for row in result["data"]["items"] for field in ("title", "body", "name")):
        raise ValueError("result contains unrequested personal content")


def bind_search_result(call,result):
    check("model-tool-call",call);check("tool-result",result)
    if call["toolName"]!="search_memory" or result["callId"]!=call["providerCallId"] or result["data"] is None or result["data"]["kind"]!="search":
        raise ValueError("not a result for this search")
    types=set(call["arguments"]["types"]);hits=result["data"]["hits"]
    if any(hit["type"] not in types for hit in hits) or len({hit["id"] for hit in hits})!=len(hits):
        raise ValueError("search result contains unrequested or duplicate records")
    if len(hits)>call["arguments"]["limit"]:
        raise ValueError("search result exceeded requested limit")
    if result["status"]=="empty" and hits or result["status"]=="found" and not hits:
        raise ValueError("search status contradicts its hits")


def bind_person_candidates(call,result):
    check("model-tool-call",call);check("tool-result",result)
    if call["toolName"]!="resolve_person" or result["callId"]!=call["providerCallId"] or result["data"] is None or result["data"]["kind"]!="person_candidates":
        raise ValueError("not a result for person resolution")
    candidates=result["data"]["candidates"]
    if result["status"]=="empty" and candidates or result["status"]=="found" and not candidates:
        raise ValueError("candidate status is inconsistent")
    if len({x["id"] for x in candidates})!=len(candidates):
        raise ValueError("duplicate person candidate")


def bind_table_query(call,result):
    check("model-tool-call",call);check("tool-result",result)
    args=call["arguments"]
    if call["toolName"]!="query_table" or result["callId"]!=call["providerCallId"] or result["data"] is None or result["data"]["kind"]!="table_page":
        raise ValueError("not a result for this table query")
    data=result["data"]
    if data["tableId"]!=args["tableId"] or data["schemaVersion"]!=args["expectedSchemaVersion"]:
        raise ValueError("table query result is stale or targets another table")
    if any(any(cell["columnId"] not in args["projection"] for cell in row["cells"]) for row in data["rows"]):
        raise ValueError("table page contains an unrequested column")
    if len({row["rowId"] for row in data["rows"]})!=len(data["rows"]):
        raise ValueError("duplicate row in table page")
    if result["status"]=="partial" and (data["complete"] or not result["nextCursor"]):
        raise ValueError("partial page must expose a continuation cursor")
    if data["complete"] and result["nextCursor"] is not None:
        raise ValueError("complete page cannot carry a next cursor")


def bind_table_aggregate(call,result):
    check("model-tool-call",call);check("tool-result",result)
    if call["toolName"]!="aggregate_table" or result["callId"]!=call["providerCallId"] or result["data"] is None or result["data"]["kind"]!="table_aggregate":
        raise ValueError("not a result for this aggregate")
    data=result["data"];args=call["arguments"]
    if data["tableId"]!=args["tableId"] or data["schemaVersion"]!=args["expectedSchemaVersion"] or data["operation"]!=args["operation"] or data["columnId"]!=args["columnId"]:
        raise ValueError("aggregate output differs from table/schema/operation")


def bind_clarification(call,result):
    check("model-tool-call",call);check("tool-result",result)
    if call["toolName"]!="request_clarification" or result["callId"]!=call["providerCallId"] or result["status"]!="prepared" or result["data"]["kind"]!="clarification":
        raise ValueError("not a prepared clarification for this request")
    data=result["data"];args=call["arguments"]
    if data["reasonCode"]!=args["reasonCode"] or data["fields"]!=args["fields"] or data["options"]!=args["options"]:
        raise ValueError("clarification result changed reason, requested fields, or choices")


def bind_entity_result(command, result):
    check("command-envelope", command)
    check("tool-result", result)
    name = command["toolName"]
    kind = "person" if name in ("create_person", "update_person") else "project" if name in ("create_project", "update_project") else None
    if kind is None or result["status"] not in ("committed", "no_change", "replayed") or result["data"]["kind"] != kind:
        raise ValueError("wrong action or result kind")
    if result["commandId"] != command["commandId"] or result["data"]["id"] != command["arguments"][kind + "Id"]:
        raise ValueError("result refers to a different command or object")
    validate_result_version(command, result)
    if not set(result["evidenceRefs"]).issubset(command["evidenceRefs"]):
        raise ValueError("unrelated evidence")


def bind_link_result(command, result):
    check("command-envelope", command)
    check("tool-result", result)
    name = command["toolName"]
    if name not in ("link_records", "unlink_records") or result["status"] not in ("committed", "no_change", "replayed") or result["data"]["kind"] != "link":
        raise ValueError("wrong link action or result")
    if result["commandId"] != command["commandId"] or result["data"]["id"] != command["arguments"]["linkId"]:
        raise ValueError("result refers to a different command or link")
    if result["data"]["active"] != (name == "link_records"):
        raise ValueError("result has incorrect link state")
    validate_result_version(command, result)
    if not set(result["evidenceRefs"]).issubset(command["evidenceRefs"]):
        raise ValueError("unrelated evidence")


def bind_task_result(command, result):
    check("command-envelope", command)
    check("tool-result", result)
    if command["toolName"] not in ("create_task", "update_task") or result["status"] not in ("committed", "no_change", "replayed") or result["data"]["kind"] != "task":
        raise ValueError("wrong task action or result")
    if result["commandId"] != command["commandId"] or result["data"]["id"] != command["arguments"]["taskId"]:
        raise ValueError("result refers to a different command or task")
    validate_result_version(command, result)
    request = command["arguments"].get("patch", command["arguments"])
    if request.get("remind") is False and result["data"]["reminderStatus"] not in ("NONE", "CANCELLATION_PENDING", "ERROR"):
        raise ValueError("reminder result contradicts explicit disabled state")
    if request.get("remind") is True and result["data"]["reminderStatus"] == "NONE":
        raise ValueError("reminder result hides explicitly requested reminder")
    if request.get("status") == "DONE" and result["data"]["reminderStatus"] in ("SCHEDULED", "PENDING"):
        raise ValueError("completed task cannot report an active reminder")
    if not set(result["evidenceRefs"]).issubset(command["evidenceRefs"]):
        raise ValueError("unrelated evidence")


def bind_delete_preview(call, result):
    check("model-tool-call", call)
    check("tool-result", result)
    if call["toolName"] != "preview_delete" or result["status"] != "prepared" or result["data"]["kind"] != "delete_preview" or result["callId"] != call["providerCallId"]:
        raise ValueError("result is not the requested deletion preview")
    wanted = {(x["type"], x["id"]) for x in call["arguments"]["targets"]}
    shown = [(x["type"], x["id"]) for x in result["data"]["targets"]]
    if len(set(shown)) != len(shown) or set(shown) != wanted:
        raise ValueError("preview target set changed")
    data = result["data"]
    all_ids = [x["id"] for x in data["targets"] + data["dependentLinks"]]
    if len(all_ids) > 100 or len(set(all_ids)) != len(all_ids):
        raise ValueError("duplicate or excessive preview effects")
    if data["planDigest"] != deletion_preview_digest(data):
        raise ValueError("preview digest does not match exact effects")


def deletion_preview_digest(data):
    return hashlib.sha256(canonical_bytes({k: v for k, v in data.items() if k != "planDigest"})).hexdigest()


def bind_delete_result(command, result, preview_result):
    """Structural comparison only; trusted proof, atomic commit and actual deletion are independent."""
    check("command-envelope", command)
    check("tool-result", result)
    check("tool-result", preview_result)
    if preview_result["status"] != "prepared" or preview_result["data"]["kind"] != "delete_preview":
        raise ValueError("expected a prepared deletion preview")
    preview = preview_result["data"]
    if preview["planDigest"] != deletion_preview_digest(preview):
        raise ValueError("preview digest does not match exact effects")
    if command["toolName"] != "commit_delete" or result["status"] not in ("committed", "replayed") or result["data"]["kind"] != "deletion":
        raise ValueError("not a committed deletion")
    if command["confirmationId"] is None or result["commandId"] != command["commandId"]:
        raise ValueError("missing confirmation or incorrect command")
    if command["arguments"] != {"previewId": preview["previewId"], "planDigest": preview["planDigest"]}:
        raise ValueError("command is bound to a different preview")
    if command["scopeId"] != preview["scopeId"] or command["sourceRevisionId"] != preview["sourceRevisionId"]:
        raise ValueError("preview scope or source changed")
    target_rows = preview["targets"] + preview["dependentLinks"]
    targets = {x["id"]: x["version"] for x in target_rows}
    expected = {x["id"]: x["version"] for x in command["expectedVersions"]}
    if len(targets) != len(target_rows) or len(targets) > 100 or targets != expected:
        raise ValueError("preview and command versions differ")
    actual_targets = {x["id"]: x["version"] for x in result["data"]["targets"]}
    actual_links = {x["id"]: x["version"] for x in result["data"]["deactivatedLinks"]}
    if any(v == 9223372036854775807 for v in expected.values()) or actual_targets != {x["id"]: x["version"] + 1 for x in preview["targets"]} or actual_links != {x["id"]: x["version"] + 1 for x in preview["dependentLinks"]}:
        raise ValueError("deletion did not update every target exactly once")
    if not set(result["evidenceRefs"]).issubset(command["evidenceRefs"]):
        raise ValueError("unrelated evidence")


def validate_result_version(command, result):
    if command["toolName"].startswith("create_") or command["toolName"] == "link_records":
        if result["status"] == "no_change" or result["data"]["version"] != 1:
            raise ValueError("created object must start at version 1")
    else:
        previous = command["expectedVersions"][0]["version"]
        expected = previous if result["status"] == "no_change" else previous + 1
        if expected > 9223372036854775807 or result["data"]["version"] != expected:
            raise ValueError("result version does not follow expected target version")


def bind_finance_result(call, result):
    """Bind a deterministic local summary to the requested currency and period."""
    check("model-tool-call", call)
    check("tool-result", result)
    args = call["arguments"]
    if call["toolName"] != "query_finance" or result["callId"] != call["providerCallId"]:
        raise ValueError("result is not the requested finance query")
    if result["status"] not in ("found", "empty") or result["data"] is None or result["data"]["kind"] != "finance_summary":
        raise ValueError("finance result must be a typed complete summary")
    data = result["data"]
    period = args.get("period", {})
    if data["currency"] != args["currency"] or data["asOf"] != args["asOf"]:
        raise ValueError("currency or as-of differs from query")
    if data["periodStart"] != period.get("from") or data["periodEndExclusive"] != period.get("untilExclusive"):
        raise ValueError("summary period differs from query")
    if result["status"] == "empty" and (data["eventIds"] or data["totalMinorUnits"] != 0):
        raise ValueError("empty result cannot contain an aggregate")
    if result["status"] == "found" and not data["eventIds"]:
        raise ValueError("found result must reference supporting events")


def bind_money_result(command, result):
    check("command-envelope", command)
    check("tool-result", result)
    tool = command["toolName"]
    kinds = {"record_receipt": "money_event", "record_obligation": "obligation", "record_settlement": "settlement", "correct_money_event": "money_correction"}
    if tool not in kinds or result["commandId"] != command["commandId"] or result["status"] not in ("committed", "replayed"):
        raise ValueError("not a successful result for this money command")
    data = result["data"]
    if data["kind"] != kinds[tool] or result["affected"] != [{"id": data["id"], "version": data["version"]}]:
        raise ValueError("money result target does not match affected version")
    if tool in ("record_receipt", "record_settlement"):
        args=command["arguments"]
        if data["amountMinorUnits"] != args["amountMinorUnits"] or data["currency"] != args["currency"] or data["currencyScaleVersion"] != args["currencyScaleVersion"]:
            raise ValueError("money event differs from command")
        if data["occurredAt"]!=args["occurredAt"]:
            raise ValueError("money event timestamp differs from command")
        if tool=="record_receipt" and data["direction"]!=args["direction"]:
            raise ValueError("receipt direction differs from command")
    if tool=="record_obligation":
        args=command["arguments"]
        if data["principalMinorUnits"]!=args["amountMinorUnits"] or data["currency"]!=args["currency"] or data["currencyScaleVersion"]!=args["currencyScaleVersion"] or data["direction"]!=args["direction"] or data["occurredAt"]!=args["occurredAt"] or data["settledMinorUnits"]!=0:
            raise ValueError("new obligation must preserve its exact principal and start unsettled")
    if tool=="record_settlement":
        args=command["arguments"]
        if data["remainingMinorUnits"]!=args["expectedRemainingMinorUnits"]-args["amountMinorUnits"] or data["occurredAt"]!=args["occurredAt"]:
            raise ValueError("settlement balance must be exact and non-negative")
    if tool == "correct_money_event" and data["targetEventId"] != command["arguments"]["targetEventId"]:
        raise ValueError("correction result changed its target event")
    if tool=="correct_money_event" and data["replacementMinorUnits"]!=command["arguments"]["replacementMinorUnits"]:
        raise ValueError("correction result differs from requested compensation")
    if tool=="correct_money_event" and data["reason"]!=command["arguments"]["reason"]:
        raise ValueError("correction result changed its explanation")


def bind_calendar_result(command, result):
    check("command-envelope",command);check("tool-result",result)
    args=command["arguments"]
    if command["toolName"] not in ("create_calendar_event","update_calendar_event") or result["commandId"]!=command["commandId"] or result["status"] not in ("committed","replayed"):
        raise ValueError("not a successful calendar command result")
    data=result["data"]
    expected_id=args["eventId"]
    if data["kind"]!="calendar_event" or data["eventId"]!=expected_id:
        raise ValueError("calendar result changed event identity")
    expected=args if command["toolName"]=="create_calendar_event" else args["patch"]
    for field in ("title","start","end"):
        if field in expected and data[field]!=expected[field]:
            raise ValueError("calendar result differs from requested fields")
    if not set(result["evidenceRefs"]).issubset(command["evidenceRefs"]):
        raise ValueError("calendar result cites unrelated evidence")


def bind_table_result(command,result):
    check("command-envelope",command);check("tool-result",result)
    tool=command["toolName"];args=command["arguments"]
    if result["commandId"]!=command["commandId"] or result["status"] not in ("committed","replayed"):
        raise ValueError("not a successful table command result")
    data=result["data"]
    if tool=="create_table":
        if data["kind"]!="table_created" or data["tableId"]!=args["tableId"] or data["schemaVersion"]!=1:
            raise ValueError("created table identity/schema differs from command")
    elif tool in ("add_table_rows","update_table_rows"):
        expected=args["rowIds"] if tool=="add_table_rows" else [row["rowId"] for row in args["rows"]]
        if data["kind"]!="table_rows_changed" or data["tableId"]!=args["tableId"] or data["schemaVersion"]!=args["expectedSchemaVersion"] or set(data["rowIds"])!=set(expected):
            raise ValueError("row batch result differs from requested table/rows/schema")
    elif tool=="change_table_schema":
        if data["kind"]!="table_schema_changed" or data["tableId"]!=args["tableId"] or data["schemaVersion"]!=args["expectedSchemaVersion"]+1:
            raise ValueError("table schema result version does not advance expected schema")
    else: raise ValueError("unsupported table command")
    if not set(result["evidenceRefs"]).issubset(command["evidenceRefs"]):
        raise ValueError("table result cites unrelated evidence")


def bind_restore_result(command,result):
    check("command-envelope",command);check("tool-result",result)
    if command["toolName"]!="restore_records" or result["commandId"]!=command["commandId"] or result["status"] not in ("committed","replayed"):
        raise ValueError("not a successful restore result")
    expected={row["recordId"] for row in command["arguments"]["records"]}
    actual={row["id"] for row in result["data"]["records"]} if result["data"]["kind"]=="restored_records" else set()
    if actual!=expected: raise ValueError("restore result must match requested IDs")
    if not set(result["evidenceRefs"]).issubset(command["evidenceRefs"]):raise ValueError("restore result cites unrelated evidence")


def bind_undo_result(command,result):
    check("command-envelope",command);check("tool-result",result)
    if command["toolName"]!="undo_change" or result["commandId"]!=command["commandId"] or result["status"] not in ("committed","replayed") or result["data"]["kind"]!="undo_applied":
        raise ValueError("not a completed undo command")
    if result["data"]["changeSetId"]!=command["arguments"]["changeSetId"]:raise ValueError("undo result changed the requested change set")
    if not set(result["evidenceRefs"]).issubset(command["evidenceRefs"]):raise ValueError("undo result cites unrelated evidence")


def bind_prepared_plan(call,result):
    check("model-tool-call",call);check("tool-result",result)
    if call["toolName"]!="prepare_changes" or result["callId"]!=call["providerCallId"] or result["status"]!="prepared" or result["data"]["kind"]!="prepared_plan":
        raise ValueError("not a prepared result for this plan request")
    if result["data"]["operationCount"]!=len(call["arguments"]["operations"]):
        raise ValueError("prepared plan omitted or added operations")


def bind_undo_preview(call,result):
    check("model-tool-call",call);check("tool-result",result)
    if call["toolName"]!="undo_change" or result["callId"]!=call["providerCallId"] or result["status"]!="prepared" or result["data"]["kind"]!="undo_preview":
        raise ValueError("not an undo preview for this request")
    if result["data"]["changeSetId"]!=call["arguments"]["changeSetId"]:
        raise ValueError("undo preview changed the requested change set")


class AgentContractsTest(unittest.TestCase):
    def setUp(self):
        self.call = read_fixture("create-note-call.json")
        self.command = read_fixture("create-note-command.json")
        self.result = read_fixture("create-note-result.json")
        self.search = read_fixture("search-call.json")
        self.empty = read_fixture("search-empty-result.json")
        self.update_call = read_fixture("update-note-call.json")
        self.update = read_fixture("update-note-command.json")
        self.read = read_fixture("read-records-call.json")
        self.read_result = read_fixture("read-records-result.json")
        self.person = read_fixture("resolve-person-call.json")
        self.person_result = read_fixture("resolve-person-result.json")

    def assertRejected(self, kind, obj):
        with self.assertRaises(Exception):
            check(kind, obj)

    def test_schemas_and_synthetic_fixtures(self):
        for kind, obj in [("model-tool-call", self.call), ("model-tool-call", self.search),
                          ("command-envelope", self.command), ("tool-result", self.result),
                          ("tool-result", self.empty), ("model-tool-call", self.update_call),
                          ("command-envelope", self.update), ("model-tool-call", self.read),
                          ("tool-result", self.read_result), ("model-tool-call", self.person),
                          ("tool-result", self.person_result)]:
            check(kind, obj)

    def test_model_cannot_supply_authority_or_unknown_fields(self):
        for key in ("scopeId", "actorId", "commandId", "approved", "confirmationId"):
            forged = copy.deepcopy(self.call)
            forged[key] = True
            self.assertRejected("model-tool-call", forged)
        forged = copy.deepcopy(self.call)
        forged["arguments"]["approved"] = True
        self.assertRejected("model-tool-call", forged)
        forged = copy.deepcopy(self.call)
        forged["toolName"] = "execute_sql"
        self.assertRejected("model-tool-call", forged)

    def test_type_and_version_errors_fail_closed(self):
        for mutate in (lambda x: x.update(schemaVersion=2),
                       lambda x: x["arguments"].update(links=None),
                       lambda x: x["arguments"].pop("body"),
                       lambda x: x["arguments"].update(title="   ")):
            bad = copy.deepcopy(self.call)
            mutate(bad)
            self.assertRejected("model-tool-call", bad)
        bad = copy.deepcopy(self.command)
        bad["confirmationId"] = "not-a-uuid"
        self.assertRejected("command-envelope", bad)

    def test_unicode_utf16_and_search_bounds(self):
        bad = copy.deepcopy(self.call)
        bad["arguments"]["body"] = "🙂" * 10001
        self.assertRejected("model-tool-call", bad)
        bad["arguments"]["body"] = "\ud800"
        self.assertRejected("model-tool-call", bad)
        bad = copy.deepcopy(self.search)
        bad["arguments"]["limit"] = 51
        self.assertRejected("model-tool-call", bad)
        bad["arguments"]["limit"] = 2
        bad["arguments"]["period"] = {"from": "2026-02-02", "untilExclusive": "2026-02-01"}
        self.assertRejected("model-tool-call", bad)

    def test_versions_cover_each_link(self):
        linked = copy.deepcopy(self.command)
        target = "00000000-0000-4000-8000-000000000099"
        linked["arguments"]["links"] = [{"type": "person", "id": target}]
        self.assertRejected("command-envelope", linked)
        linked["expectedVersions"] = [{"id": target, "version": 3}]
        linked["payloadDigest"] = command_digest(linked)
        check("command-envelope", linked)
        linked["expectedVersions"].append({"id": target, "version": 4})
        self.assertRejected("command-envelope", linked)

    def test_success_and_empty_cannot_hide_error_or_false_effect(self):
        for field, value in [("commandId", None), ("errorCode", "STORAGE_UNAVAILABLE"),
                             ("retryable", True), ("evidenceRefs", []), ("affected", [])]:
            bad = copy.deepcopy(self.result)
            bad[field] = value
            self.assertRejected("tool-result", bad)
        bad = copy.deepcopy(self.empty)
        bad["completeness"] = "partial"
        self.assertRejected("tool-result", bad)
        bad = copy.deepcopy(self.empty)
        bad["status"] = "found"
        self.assertRejected("tool-result", bad)

    def test_prepared_is_not_a_committed_result(self):
        prepared = copy.deepcopy(self.result)
        prepared.update(status="prepared", commandId=None, data={"kind": "prepared", "planId": "00000000-0000-4000-8000-000000000010", "digest": "b" * 64}, affected=[], evidenceRefs=[], completeness=None)
        check("tool-result", prepared)
        prepared["affected"] = self.result["affected"]
        self.assertRejected("tool-result", prepared)

    def test_strict_parser_rejects_ambiguous_json(self):
        for text in ('{"a":1,"a":2}', '{"a":1.0}', '{"a":NaN}'):
            with self.assertRaises(ValueError):
                read_json(text)

    def test_digest_golden_and_exact_payload_binding(self):
        self.assertEqual("69657e6421bfe8cd44f32fe6827079efa296aff9df0427edb6f7c46ace3688c0", command_digest(self.command))
        self.assertEqual(b'{"a":"\\n","b":"\xc3\xa7"}', canonical_bytes({"b": "ç", "a": "\n"}))
        changed = copy.deepcopy(self.command)
        changed["arguments"]["body"] += "!"
        self.assertRejected("command-envelope", changed)
        changed = copy.deepcopy(self.command)
        changed["permissionSnapshotVersion"] += 1
        self.assertRejected("command-envelope", changed)
        changed = copy.deepcopy(self.command)
        changed["confirmationId"] = "00000000-0000-4000-8000-000000000009"
        self.assertEqual(command_digest(self.command), command_digest(changed))
        with self.assertRaises(ValueError):
            canonical_bytes({"float": 1.0})

    def test_update_is_a_nonempty_strict_patch_without_model_authority(self):
        for patch in ({}, {"body": None}, {"body": "   "}, {"links": []}, {"body": "ok", "version": 9}):
            bad = copy.deepcopy(self.update_call)
            bad["arguments"]["patch"] = patch
            self.assertRejected("model-tool-call", bad)
        bad = copy.deepcopy(self.update_call)
        bad["arguments"]["newRevisionId"] = "00000000-0000-4000-8000-000000000012"
        self.assertRejected("model-tool-call", bad)
        title_only = copy.deepcopy(self.update_call)
        title_only["arguments"]["patch"] = {"title": "Yeni başlıq"}
        check("model-tool-call", title_only)

    def test_update_binds_exact_target_version_revision_and_digest(self):
        self.assertEqual("b07a13fbe230114fb8ed25661e267e175c91abd55f9c5bc07a1e622698ec0537", command_digest(self.update))
        for change in ("no_version", "wrong_target", "duplicate_target", "same_source_revision", "changed_payload"):
            bad = copy.deepcopy(self.update)
            if change == "no_version":
                bad["expectedVersions"] = []
            elif change == "wrong_target":
                bad["expectedVersions"][0]["id"] = "00000000-0000-4000-8000-000000000099"
            elif change == "duplicate_target":
                bad["expectedVersions"].append({"id": bad["arguments"]["noteId"], "version": 2})
            elif change == "same_source_revision":
                bad["arguments"]["newRevisionId"] = bad["sourceRevisionId"]
            else:
                bad["arguments"]["patch"]["body"] += "!"
            if change != "changed_payload":
                bad["payloadDigest"] = command_digest(bad)
            self.assertRejected("command-envelope", bad)

    def test_note_result_is_bound_to_original_command_and_reserved_revision(self):
        bind_note_result(self.command, self.result)
        for field in ("commandId", "noteId", "revisionId", "evidenceRefs"):
            bad = copy.deepcopy(self.result)
            if field == "commandId":
                bad[field] = "00000000-0000-4000-8000-000000000099"
            elif field == "evidenceRefs":
                bad[field] = ["00000000-0000-4000-8000-000000000099"]
            elif field == "noteId":
                bad["data"][field] = "00000000-0000-4000-8000-000000000099"
                bad["affected"][0]["id"] = bad["data"][field]
            else:
                bad["data"][field] = "00000000-0000-4000-8000-000000000099"
            with self.assertRaises(ValueError):
                bind_note_result(self.command, bad)

    def test_read_results_are_bounded_to_requested_ids_and_fields(self):
        bind_read_result(self.read, self.read_result)
        for change in ("unrequested_content", "wrong_id", "wrong_call", "missing", "duplicated"):
            result = copy.deepcopy(self.read_result)
            if change == "unrequested_content": result["data"]["items"][0]["body"] = "not requested"
            elif change == "wrong_id": result["data"]["items"][0]["id"] = "00000000-0000-4000-8000-000000000099"
            elif change == "wrong_call": result["callId"] = "other"
            elif change == "missing": result["data"]["items"] = []
            else: result["data"]["items"].append(copy.deepcopy(result["data"]["items"][0]))
            with self.assertRaises(Exception):
                bind_read_result(self.read, result)

    def test_inaccessible_record_is_partial_not_falsely_empty(self):
        result = copy.deepcopy(self.read_result)
        target = result["data"]["items"].pop()
        result["data"]["errors"] = [{"type": "note", "id": target["id"], "errorCode": "NOT_ACCESSIBLE"}]
        result["status"] = "partial"
        result["completeness"] = "partial"
        bind_read_result(self.read, result)
        result["status"] = "empty"
        result["completeness"] = "complete"
        self.assertRejected("tool-result", result)

    def test_person_candidates_do_not_silently_resolve_namesakes(self):
        check("tool-result", self.person_result)
        ambiguous = copy.deepcopy(self.person_result)
        other = copy.deepcopy(ambiguous["data"]["candidates"][0])
        other["id"] = "00000000-0000-4000-8000-000000000015"
        ambiguous["data"]["candidates"].append(other)
        check("tool-result", ambiguous)
        ambiguous["data"]["candidates"].append(copy.deepcopy(other))
        self.assertRejected("tool-result", ambiguous)

    def test_more_pages_cannot_be_marked_complete(self):
        result = copy.deepcopy(self.read_result)
        result["nextCursor"] = "next-page"
        self.assertRejected("tool-result", result)
        result["status"] = "partial"
        result["completeness"] = "partial"
        check("tool-result", result)

    def test_person_and_project_commands_bind_ids_versions_and_evidence(self):
        person = "00000000-0000-4000-8000-000000000021"
        project = "00000000-0000-4000-8000-000000000022"
        samples = [
            ("create_person", {"name": "Samir", "aliases": []}, {"personId": person, "name": "Samir", "aliases": []}, []),
            ("update_person", {"personId": person, "patch": {"name": "Samir A."}}, {"personId": person, "patch": {"name": "Samir A."}}, [{"id": person, "version": 1}]),
            ("create_project", {"title": "Servis"}, {"projectId": project, "title": "Servis", "status": "ACTIVE"}, []),
            ("update_project", {"projectId": project, "patch": {"status": "ON_HOLD"}}, {"projectId": project, "patch": {"status": "ON_HOLD"}}, [{"id": project, "version": 1}]),
        ]
        for name, proposal, args, versions in samples:
            call = {"toolName": name, "schemaVersion": 1, "providerCallId": "synthetic", "arguments": proposal}
            check("model-tool-call", call)
            command = copy.deepcopy(self.command)
            command.update(toolName=name, arguments=args, expectedVersions=versions)
            command["payloadDigest"] = command_digest(command)
            check("command-envelope", command)
            kind = "person" if "person" in name else "project"
            item_id = args[kind + "Id"]
            result = copy.deepcopy(self.result)
            result_version = 2 if name.startswith("update_") else 1
            result["data"] = {"kind": kind, "id": item_id, "version": result_version}
            result["affected"] = [{"id": item_id, "version": result_version}]
            bind_entity_result(command, result)
            result["data"]["id"] = "00000000-0000-4000-8000-000000000099"
            result["affected"][0]["id"] = result["data"]["id"]
            with self.assertRaises(ValueError): bind_entity_result(command, result)

    def test_entity_golden_cases(self):
        for item in read_fixture("entity-cases.json"):
            check("model-tool-call", item["call"])
            bind_entity_result(item["command"], item["result"])
            if item["command"]["toolName"].startswith("update_"):
                bad = copy.deepcopy(item["result"])
                bad["data"]["version"] = item["command"]["expectedVersions"][0]["version"]
                bad["affected"][0]["version"] = bad["data"]["version"]
                with self.assertRaises(ValueError): bind_entity_result(item["command"], bad)

    def test_entity_proposals_cannot_assign_ids_or_unknown_statuses(self):
        invalid = [
            {"toolName": "create_person", "schemaVersion": 1, "providerCallId": "s", "arguments": {"name": "Samir", "personId": "00000000-0000-4000-8000-000000000021"}},
            {"toolName": "create_person", "schemaVersion": 1, "providerCallId": "s", "arguments": {"name": "Samir", "aliases": ["   "]}},
            {"toolName": "create_project", "schemaVersion": 1, "providerCallId": "s", "arguments": {"title": "Servis", "status": "COMPLETED"}},
            {"toolName": "update_project", "schemaVersion": 1, "providerCallId": "s", "arguments": {"projectId": "00000000-0000-4000-8000-000000000022", "patch": {"status": "UNKNOWN"}}},
        ]
        for call in invalid: self.assertRejected("model-tool-call", call)

    def test_link_and_unlink_bind_target_versions_and_never_remove_objects(self):
        person = "00000000-0000-4000-8000-000000000021"
        project = "00000000-0000-4000-8000-000000000022"
        link = "00000000-0000-4000-8000-000000000023"
        proposal = {"from": {"type": "person", "id": person}, "to": {"type": "project", "id": project}, "relationType": "ASSOCIATED_WITH"}
        call = {"toolName": "link_records", "schemaVersion": 1, "providerCallId": "synthetic-link", "arguments": proposal}
        check("model-tool-call", call)
        command = copy.deepcopy(self.command)
        command.update(toolName="link_records", arguments={**proposal, "linkId": link}, expectedVersions=[{"id": person, "version": 1}, {"id": project, "version": 2}])
        command["payloadDigest"] = command_digest(command)
        result = copy.deepcopy(self.result)
        result.update(data={"kind": "link", "id": link, "version": 1, "active": True}, affected=[{"id": link, "version": 1}])
        bind_link_result(command, result)
        bad = copy.deepcopy(command)
        bad["expectedVersions"].pop()
        bad["payloadDigest"] = command_digest(bad)
        self.assertRejected("command-envelope", bad)
        result["data"]["active"] = False
        with self.assertRaises(ValueError): bind_link_result(command, result)
        unlinked = copy.deepcopy(command)
        unlinked.update(toolName="unlink_records", arguments={"linkId": link}, expectedVersions=[{"id": link, "version": 1}])
        unlinked["payloadDigest"] = command_digest(unlinked)
        result.update(data={"kind": "link", "id": link, "version": 2, "active": False}, affected=[{"id": link, "version": 2}])
        bind_link_result(unlinked, result)

    def test_link_proposal_cannot_assign_link_id_or_link_an_object_to_itself(self):
        person = "00000000-0000-4000-8000-000000000021"
        proposal = {"from": {"type": "person", "id": person}, "to": {"type": "person", "id": person}, "relationType": "RELATED_TO"}
        call = {"toolName": "link_records", "schemaVersion": 1, "providerCallId": "synthetic-link", "arguments": proposal}
        self.assertRejected("model-tool-call", call)
        proposal["to"]["id"] = "00000000-0000-4000-8000-000000000022"
        proposal["linkId"] = "00000000-0000-4000-8000-000000000023"
        self.assertRejected("model-tool-call", call)

    def test_link_golden_cases(self):
        for item in read_fixture("link-cases.json"):
            check("model-tool-call", item["call"])
            bind_link_result(item["command"], item["result"])

    def test_deletion_binds_preview_scope_source_all_versions_and_confirmation(self):
        note = "00000000-0000-4000-8000-000000000006"
        link = "00000000-0000-4000-8000-000000000023"
        preview_id = "00000000-0000-4000-8000-000000000030"
        call = {"toolName": "preview_delete", "schemaVersion": 1, "providerCallId": "preview-1", "arguments": {"targets": [{"type": "note", "id": note}]}}
        preview = copy.deepcopy(self.empty)
        preview.update(callId="preview-1", status="prepared", completeness=None, data={"kind": "delete_preview", "previewId": preview_id, "planDigest": "0" * 64,
            "scopeId": self.command["scopeId"], "sourceRevisionId": self.command["sourceRevisionId"], "mode": "TRASH",
            "targets": [{"type": "note", "id": note, "version": 2}], "dependentLinks": [{"id": link, "version": 5}]})
        preview["data"]["planDigest"] = deletion_preview_digest(preview["data"])
        bind_delete_preview(call, preview)
        command = copy.deepcopy(self.command)
        command.update(toolName="commit_delete", arguments={"previewId": preview_id, "planDigest": preview["data"]["planDigest"]},
                       expectedVersions=[{"id": note, "version": 2}, {"id": link, "version": 5}], confirmationId="00000000-0000-4000-8000-000000000031")
        command["payloadDigest"] = command_digest(command)
        result = copy.deepcopy(self.result)
        result.update(data={"kind": "deletion", "mode": "TRASH", "targets": [{"id": note, "version": 3}], "deactivatedLinks": [{"id": link, "version": 6}]},
                      affected=[{"id": note, "version": 3}, {"id": link, "version": 6}])
        bind_delete_result(command, result, preview)
        for change in ("missing_confirmation", "source", "scope", "target_version", "preview_effect", "missing_link", "result_error"):
            c, r, p = copy.deepcopy(command), copy.deepcopy(result), copy.deepcopy(preview)
            if change == "missing_confirmation": c["confirmationId"] = None
            elif change == "source": c["sourceRevisionId"] = "00000000-0000-4000-8000-000000000099"
            elif change == "scope": c["scopeId"] = "00000000-0000-4000-8000-000000000099"
            elif change == "target_version": c["expectedVersions"][0]["version"] = 4
            elif change == "preview_effect": p["data"]["dependentLinks"][0]["version"] = 7
            elif change == "missing_link": r["data"]["deactivatedLinks"] = []; r["affected"] = r["affected"][:1]
            else: r["status"] = "failed"; r["errorCode"] = "STORAGE_UNAVAILABLE"
            c["payloadDigest"] = command_digest(c)
            with self.assertRaises(Exception): bind_delete_result(c, r, p)

    def test_model_cannot_self_confirm_or_request_permanent_delete(self):
        args = {"previewId": "00000000-0000-4000-8000-000000000030"}
        call = {"toolName": "commit_delete", "schemaVersion": 1, "providerCallId": "delete", "arguments": args}
        check("model-tool-call", call)
        for field in ("confirmationId", "approved", "mode"):
            bad = copy.deepcopy(call)
            bad["arguments"][field] = "PURGE"
            self.assertRejected("model-tool-call", bad)

    def test_deletion_golden_case(self):
        case = read_fixture("delete-case.json")
        bind_delete_preview(case["call"], case["preview"])
        bind_delete_result(case["command"], case["result"], case["preview"])

    def test_task_date_time_and_reminder_are_explicit(self):
        call = {"toolName": "create_task", "schemaVersion": 1, "providerCallId": "task", "arguments": {"title": "Zəng et", "due": {"precision": "date", "date": "2026-11-02"}}}
        check("model-tool-call", call)
        call["arguments"]["remind"] = True
        self.assertRejected("model-tool-call", call)
        call["arguments"]["due"] = {"precision": "minute", "localDateTime": "2026-11-02T09:00", "zoneId": "Asia/Baku"}
        check("model-tool-call", call)
        for local in ("2024-03-10T02:30", "2024-11-03T01:30", "2024-02-30T12:00"):
            bad = copy.deepcopy(call)
            bad["arguments"]["due"] = {"precision": "minute", "localDateTime": local, "zoneId": "America/New_York"}
            self.assertRejected("model-tool-call", bad)
        bad = copy.deepcopy(call)
        bad["arguments"]["due"]["zoneId"] = "Not/AZone"
        self.assertRejected("model-tool-call", bad)
        bad = copy.deepcopy(call)
        bad["arguments"]["title"] = "🙂" * 150
        check("model-tool-call", bad)

    def test_task_update_does_not_silently_disable_reminders(self):
        task = "00000000-0000-4000-8000-000000000040"
        call = {"toolName": "update_task", "schemaVersion": 1, "providerCallId": "task", "arguments": {"taskId": task, "patch": {"due": None}}}
        self.assertRejected("model-tool-call", call)
        call["arguments"]["patch"]["remind"] = False
        check("model-tool-call", call)
        call["arguments"]["patch"] = {"remind": True}
        self.assertRejected("model-tool-call", call)
        call["arguments"]["patch"] = {"status": "DONE"}
        check("model-tool-call", call)

    def test_task_result_does_not_conflate_notification_delivery_with_saving(self):
        task = "00000000-0000-4000-8000-000000000040"
        command = copy.deepcopy(self.command)
        command.update(toolName="create_task", arguments={"taskId": task, "title": "Zəng et", "due": None, "remind": False, "links": []}, expectedVersions=[])
        command["payloadDigest"] = command_digest(command)
        result = copy.deepcopy(self.result)
        result.update(data={"kind": "task", "id": task, "version": 1, "reminderStatus": "NONE"}, affected=[{"id": task, "version": 1}])
        bind_task_result(command, result)
        result["data"]["reminderStatus"] = "SCHEDULED"
        with self.assertRaises(ValueError): bind_task_result(command, result)

    def test_task_golden_cases(self):
        for item in read_fixture("task-cases.json"):
            check("model-tool-call", item["call"])
            bind_task_result(item["command"], item["result"])
            if item["command"]["toolName"] == "update_task":
                bad = copy.deepcopy(item["result"])
                bad["data"]["reminderStatus"] = "SCHEDULED"
                with self.assertRaises(ValueError): bind_task_result(item["command"], bad)


if __name__ == "__main__":
    unittest.main()
