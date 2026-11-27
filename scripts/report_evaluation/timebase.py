"""Task time resolution and window statistics on the authoritative effective timeline."""
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from .util import CaseError

_FIXED_OFFSETS = {"Asia/Shanghai": 8, "UTC": 0, "Etc/UTC": 0}


def get_timezone(name):
    # A non-string must not reach the dict lookup below: an unhashable value raises TypeError
    # from inside the except block and escapes as a traceback instead of a field error.
    if not isinstance(name, str):
        raise CaseError(f"unknown timezone: {name!r}")
    try:
        return ZoneInfo(name)
    except Exception:
        if name in _FIXED_OFFSETS:
            return timezone(timedelta(hours=_FIXED_OFFSETS[name]), name)
        raise CaseError(f"unknown timezone: {name!r}")


def parse_dt(value, tz):
    if not isinstance(value, str):
        raise CaseError(f"bad datetime: {value!r}")
    value = value.strip()
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d"):
        try:
            return datetime.strptime(value, fmt).replace(tzinfo=tz)
        except ValueError:
            continue
    raise CaseError(f"bad datetime: {value!r}")


def resolve_window(task_clock, spec):
    """Deterministic [start, end) window; never reads the system clock."""
    if not isinstance(task_clock, dict):
        raise CaseError(f"task_clock must be a JSON object: {task_clock!r}")
    for field in ("timezone", "reference_time"):
        if field not in task_clock:
            raise CaseError(f"task_clock missing field: {field}")
        if not isinstance(task_clock[field], str) or not task_clock[field].strip():
            raise CaseError(f"task_clock.{field} must be a non-empty string: {task_clock[field]!r}")
    if not isinstance(spec, dict):
        raise CaseError(f"task_window must be a JSON object: {spec!r}")
    tz = get_timezone(task_clock["timezone"])
    reference = parse_dt(task_clock["reference_time"], tz)
    kind = spec.get("kind")
    if kind == "absolute":
        for field in ("start", "end"):
            if field not in spec:
                raise CaseError(f"absolute window missing field: {field}")
        start = parse_dt(spec["start"], tz)
        end = parse_dt(spec["end"], tz)
        basis = "absolute"
    elif kind == "relative":
        if "days" not in spec:
            raise CaseError("relative window missing field: days")
        days = spec["days"]
        # bool is an int subclass, so True would otherwise be accepted as a one-day window.
        if not isinstance(days, int) or isinstance(days, bool) or days <= 0:
            raise CaseError(f"relative window days must be a positive integer: {days!r}")
        anchor = spec.get("anchor", "reference_day")
        try:
            if anchor == "yesterday":
                anchor_day = reference.date() - timedelta(days=1)
            elif anchor == "reference_day":
                anchor_day = reference.date()
            else:
                raise CaseError(f"unknown relative anchor: {anchor!r}")
            end = datetime(anchor_day.year, anchor_day.month, anchor_day.day, tzinfo=tz) + timedelta(days=1)
            start = end - timedelta(days=days)
        except OverflowError as e:
            # Beyond datetime's representable range; the window is uncomputable, not a crash.
            raise CaseError(f"relative window is not computable: {e}") from e
        basis = f"relative:{anchor}:{days}d"
    else:
        raise CaseError(f"unknown window kind: {kind!r}")
    if start >= end:
        raise CaseError("window start must be before end")
    return {"start": start.isoformat(), "end": end.isoformat(),
            "timezone": task_clock["timezone"], "basis": basis}


def window_stats(records, time_field, start, end):
    count = 0
    missing_time = 0
    by_date = {}
    for rec in records:
        raw = rec.get(time_field)
        if not raw:
            missing_time += 1
            continue
        dt = parse_dt(raw, start.tzinfo)
        if start <= dt < end:
            count += 1
            d = dt.date().isoformat()
            by_date[d] = by_date.get(d, 0) + 1
    return {"count": count, "by_date": by_date, "missing_time": missing_time}