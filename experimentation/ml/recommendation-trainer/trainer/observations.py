"""Turn analytics events into weighted engagement observations for both training stages.

Consumption and depth use percentage points (0–100). Missing consumption is unknown, with
event-based targets: page open .5, interaction .75, completion 1. Confidence is .25, 1, and 2
respectively; qualified ignored exposure has target 0 and confidence .1. These are training
policy defaults, not calibrated probabilities of user satisfaction.
"""

from bisect import bisect_left
import json
import logging

import numpy as np
import pandas as pd

log = logging.getLogger(__name__)
_ATTRIBUTION = ["recommendation_source_id", "recommendation_context", "recommendation_model_version", "recommendation_request_id"]
_COLUMNS = ["user_id", "content_id", "label", "sample_weight", "retrieval_positive", "interaction_created", "feature_time"] + _ATTRIBUTION
_CONTEXT = ["user_id", "app_id", "session_id", "page_id"]


def _strings(frame, column):
    return frame.get(column, pd.Series(index=frame.index, dtype=object)).fillna("").astype(str).str.strip()


def _times(frame, column):
    return pd.to_datetime(frame.get(column, pd.Series(index=frame.index, dtype=object)),
                          errors="coerce", utc=True, format="mixed")


def _percent(frame, column, report):
    raw = frame.get(column, pd.Series(index=frame.index, dtype=object))
    values = pd.to_numeric(raw, errors="coerce")
    valid = values.between(0, 100) & np.isfinite(values)
    present = raw.notna() & raw.astype(str).str.strip().ne("")
    report[f"missing_{column}"] = int((~present).sum())
    report[f"invalid_{column}"] = int((present & ~valid).sum())
    return values.where(valid) / 100.0


def _decay(times, as_of, half_life_days):
    age = (as_of - times).dt.total_seconds().fillna(0).clip(lower=0) / 86400
    return np.exp2(-age / half_life_days)


def prepare_observations(interactions, feedback, content_ids, *, as_of=None,
                         half_life_days=30.0, attribution_minutes=30.0):
    """Return labeled observations and aggregate data-quality counts.

    Merge within a known user/app/session/page visit, starting a fresh visit at each page impression.
    Without a page-open boundary, split after an attribution window of inactivity. Without correlation
    identifiers, retain discrete engagements separately.
    Contentless depth attaches only to a page visit explicitly bound to exactly one content item.
    Ignored impressions require recorded continuous visibility (>=50%, >=1s), a complete attribution
    window, and no engagement with that item elsewhere in the same session during that window.
    """
    if not np.isfinite(half_life_days) or half_life_days <= 0:
        raise ValueError("recency_half_life_days must be finite and positive")
    if not np.isfinite(attribution_minutes) or attribution_minutes <= 0:
        raise ValueError("attribution_minutes must be finite and positive")
    as_of = pd.Timestamp(as_of) if as_of is not None else pd.Timestamp.now(tz="UTC")
    if pd.isna(as_of) or as_of.tzinfo is None:
        raise ValueError("training snapshot time must include a timezone")
    as_of = as_of.tz_convert("UTC")
    window = pd.Timedelta(minutes=attribution_minutes)
    eligible = set(map(str, content_ids))
    report = {"raw_events": len(interactions), "snapshot_at": as_of.isoformat(),
              "recency_half_life_days": half_life_days, "attribution_minutes": attribution_minutes}
    events = interactions.copy().reset_index(drop=True)
    for key in _CONTEXT + ["content_id", "event_id", "interaction_type", "element_type"]:
        events[key] = _strings(events, key)
    for key in _ATTRIBUTION:
        events[key] = _strings(events, key)
    events["recommendation_context"] = events.recommendation_context.str.lower()
    events["interaction_created"] = _times(events, "interaction_created")
    report["missing_or_invalid_event_time"] = int(events.interaction_created.isna().sum())
    events["consumption"] = _percent(events, "view_percent", report)
    events["depth"] = _percent(events, "depth_percent", report)
    for key in ["visible_ms", "visibility_threshold"]:
        events[key] = pd.to_numeric(events.get(key, pd.Series(index=events.index, dtype=float)), errors="coerce")
    allowed = events.interaction_type.isin(["Interaction", "Completion", "Impression"])
    report["unsupported_events"] = int((~allowed).sum())
    identified = events.user_id.ne("")
    report["unidentified_events"] = int((~identified).sum())
    future = events.interaction_created > as_of
    report["future_events"] = int(future.sum())
    events = events[allowed & identified & ~future].copy()
    identity = ["user_id", "app_id", "event_id", "content_id"]
    payload = ["interaction_type", "element_type", "interaction_created", "session_id", "page_id",
               "consumption", "depth", "visible_ms", "visibility_threshold"] + _ATTRIBUTION
    distinct = events[events.event_id.ne("")].drop_duplicates(identity + payload)
    conflicting = distinct[distinct.duplicated(identity, keep=False)]
    conflicts = pd.MultiIndex.from_frame(events[identity]).isin(pd.MultiIndex.from_frame(conflicting[identity]))
    report["conflicting_event_rows"] = int(conflicts.sum())
    events = events[~conflicts].copy()
    duplicates = events.event_id.ne("") & events.duplicated(identity, keep="first")
    report["duplicate_event_rows"] = int(duplicates.sum())
    events = events[~duplicates].copy()
    events["page_open"] = events.interaction_type.eq("Impression") & events.element_type.eq("page")
    events["quality"] = events.interaction_type.eq("Interaction") & events.element_type.isin(
        ["scroll_depth", "scroll_max_depth"])
    events["engagement"] = (events.interaction_type.isin(["Interaction", "Completion"]) & ~events.quality) | events.page_open
    guide_state = events.interaction_type.eq("Completion") & events.element_type.isin(
        ["guide_step", "guide_progress", "guide"])
    partial_guide = guide_state & events.element_type.isin(["guide_step", "guide_progress"])
    events["confidence"] = np.select(
        [partial_guide, events.interaction_type.eq("Completion"), events.page_open],
        [1.0, 2.0, 0.25], default=1.0)
    events["fallback"] = np.select(
        [events.interaction_type.eq("Completion"), events.page_open], [1.0, 0.5], default=0.75)
    visible_ms = events.visible_ms
    threshold = events.visibility_threshold
    events["qualified"] = (events.interaction_type.eq("Impression") & ~events.page_open &
                           visible_ms.ge(1000) & np.isfinite(visible_ms) & threshold.between(0.5, 1.0))
    events["exposure_started"] = events.interaction_created - pd.to_timedelta(
        visible_ms.where(events.qualified, 0.0), unit="ms", errors="coerce")
    events["qualified"] &= events.exposure_started.notna()
    events["correlated"] = events.session_id.ne("") & events.page_id.ne("") & events.interaction_created.notna()
    events = events.sort_values(_CONTEXT + ["interaction_created", "event_id"], kind="stable")
    # Unique visit numbers also isolate every event that lacks reliable correlation identifiers.
    visit = 0
    visits = {}
    for _, group in events.groupby(_CONTEXT, sort=False):
        previous = None
        last_page = None
        has_page = False
        for row in group.itertuples():
            page_marker = row.event_id or row.interaction_created
            if (not row.correlated or previous is None or (row.page_open and page_marker != last_page) or
                    (not has_page and row.interaction_created - previous >= window)):
                visit += 1
            visits[row.Index] = visit
            previous = row.interaction_created if row.correlated else None
            if row.page_open:
                last_page = page_marker
                has_page = row.correlated
    events["visit"] = pd.Series(visits, dtype="int64")

    page_bindings = events[events.page_open & events.content_id.ne("")].groupby("visit").content_id.agg(set)
    page_bindings = page_bindings[page_bindings.map(len).eq(1)].map(lambda ids: next(iter(ids)))
    page_bindings = page_bindings[page_bindings.isin(eligible)]
    missing_depth = events.quality & events.content_id.eq("")
    events.loc[missing_depth, "content_id"] = events.loc[missing_depth, "visit"].map(page_bindings).fillna("")
    report["unattributed_depth_events"] = int((events.quality & events.content_id.eq("")).sum())
    report["unattributed_page_events"] = int((events.page_open & events.content_id.eq("")).sum())
    report["ineligible_content_events"] = int((events.content_id.ne("") & ~events.content_id.isin(eligible)).sum())
    events = events[events.content_id.isin(eligible)].copy()
    report["guide_step_completions"] = int((guide_state & events.element_type.eq("guide_step")).sum())
    report["guide_progress_observations"] = int((guide_state & events.element_type.eq("guide_progress")).sum())
    report["guide_completions"] = int((guide_state & events.element_type.eq("guide")).sum())

    # An engagement after navigating away from a feed still explains that feed's item impression.
    engagement_times = {}
    for key, group in events[events.engagement & events.interaction_created.notna()].groupby(
            ["user_id", "app_id", "session_id", "content_id"], sort=False):
        engagement_times[key] = sorted(group.interaction_created.tolist())

    observations = []
    report.update(qualified_impressions=int(events.qualified.sum()), ignored_exposures=0,
                  unqualified_impressions=int((events.interaction_type.eq("Impression") & ~events.page_open & ~events.qualified).sum()),
                  unattributable_impressions=0, pending_impressions=0, engaged_impressions=0,
                  merged_engagement_events=0, joined_depth_events=0)
    for _, group in events.groupby(["visit", "content_id"] + _ATTRIBUTION, sort=False):
        content_id = group.iloc[0].content_id
        positive = group[group.engagement]
        first = group.iloc[0]
        if not positive.empty:
            quality = group[group.quality]
            completions = positive[positive.interaction_type.eq("Completion")]
            observed = pd.concat([positive.consumption, quality.depth]).dropna()
            if not completions.empty:
                label = float(completions.consumption.fillna(1.0).max())
            else:
                label = float(observed.max() if not observed.empty else positive.fallback.max())
            confidence = float(positive.confidence.max())
            created = pd.concat([positive.interaction_created, quality.interaction_created]).max()
            report["merged_engagement_events"] += len(positive) - 1
            report["joined_depth_events"] += len(quality)
        else:
            exposures = group[group.qualified]
            if exposures.empty:
                continue
            if not exposures.correlated.all():
                report["unattributable_impressions"] += len(exposures)
                continue
            if (exposures.interaction_created + window > as_of).any():
                report["pending_impressions"] += len(exposures)
                continue
            times = engagement_times.get((first.user_id, first.app_id, first.session_id, content_id), [])
            engaged = False
            for exposure in exposures.itertuples():
                index = bisect_left(times, exposure.exposure_started)
                if index < len(times) and times[index] <= exposure.interaction_created + window:
                    engaged = True
                    break
            if engaged:
                report["engaged_impressions"] += len(exposures)
                continue
            label, confidence = 0.0, 0.1
            created = exposures.interaction_created.max()
            report["ignored_exposures"] += 1
        observations.append(dict(user_id=first.user_id, content_id=content_id, label=label,
                                 sample_weight=confidence, retrieval_positive=label > 0,
                                 interaction_created=created,
                                 feature_time=group.exposure_started.min(),
                                 **{key: first[key] for key in _ATTRIBUTION}))

    rows = pd.DataFrame(observations, columns=_COLUMNS)
    report["explicit_feedback_rows"] = 0
    report["invalid_feedback_rows"] = 0
    if feedback is not None and not feedback.empty:
        fb = feedback.copy()
        for key in ["user_id", "content_id", "feedback_source"]:
            fb[key] = _strings(fb, key)
        # Durable ratings/dismissals have no recorded recommendation request attribution.
        for key in _ATTRIBUTION:
            fb[key] = ""
        fb["label"] = pd.to_numeric(fb.feedback_label, errors="coerce")
        fb["interaction_created"] = _times(fb, "feedback_created")
        fb["feature_time"] = fb["interaction_created"]
        valid = (fb.label.between(0, 1) & fb.user_id.ne("") & fb.content_id.isin(eligible) &
                 fb.feedback_source.isin(["rating", "dismissal"]) &
                 ~(fb.interaction_created > as_of))
        report["invalid_feedback_rows"] = int((~valid).sum())
        fb = fb[valid].copy()
        fb.loc[fb.feedback_source.eq("dismissal"), "label"] = 0.0
        # Explicit rejection prevails; otherwise the latest rating replaces older ratings.
        fb["dismissed"] = fb.feedback_source.eq("dismissal")
        fb = fb.sort_values(["dismissed", "interaction_created"], ascending=[False, False], kind="stable")
        fb = fb.drop_duplicates(["user_id", "content_id"], keep="first")
        pairs = pd.MultiIndex.from_frame(fb[["user_id", "content_id"]])
        rows = rows[~pd.MultiIndex.from_frame(rows[["user_id", "content_id"]]).isin(pairs)]
        fb["sample_weight"] = 5.0
        fb["retrieval_positive"] = fb.feedback_source.eq("rating") & fb.label.ge(0.5)
        report["explicit_feedback_rows"] = len(fb)
        rows = pd.concat([rows, fb[_COLUMNS]], ignore_index=True)

    rows["interaction_created"] = _times(rows, "interaction_created")
    rows["sample_weight"] = (rows.sample_weight.astype(float) *
                             _decay(rows.interaction_created, as_of, half_life_days)).astype(np.float32)
    rows["label"] = rows.label.astype(np.float32)
    rows["retrieval_positive"] = rows.retrieval_positive.astype(bool)
    report.update(observations=len(rows), retrieval_positives=int(rows.retrieval_positive.sum()),
                  zero_targets=int(rows.label.eq(0).sum()),
                  mean_target=float(rows.label.mean()) if len(rows) else 0.0,
                  total_weight=float(rows.sample_weight.sum()))
    log.info("Training data quality: %s", json.dumps(report, sort_keys=True))
    return rows.reset_index(drop=True), report
