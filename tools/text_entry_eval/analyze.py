"""Summarize exported NoTouch evaluation JSON, without inventing unrun trials."""
import argparse
import json
import statistics
from collections import defaultdict
from pathlib import Path


def summarize(data):
    groups = defaultdict(list)
    for trial in data.get('trials', []):
        key = (trial['method'], trial['input_mode'], bool(trial.get('touch_assistance_reported')))
        groups[key].append(trial)
    result = []
    for (method, mode, assisted), rows in groups.items():
        completed = [r for r in rows if r['outcome'] == 'completed']
        attempted = [r for r in rows if r['outcome'] in ('completed', 'failed')]
        start_event = 'voice_ready' if method == '음성입력' else 'writing_opened'
        starts = [r for r in attempted if any(e['event'] == start_event for e in r['events'])]
        result.append(dict(method=method, input_mode=mode, touch_assistance=assisted,
            trials=len(rows), completed=len(completed), failed=sum(r['outcome']=='failed' for r in rows),
            interrupted=len(rows)-len(attempted),
            start_success_rate=len(starts)/len(attempted) if attempted else None,
            task_success_rate=sum(bool(r['success']) for r in attempted)/len(attempted) if attempted else None,
            median_completed_duration_ms=statistics.median(r['duration_ms'] for r in completed) if completed else None,
            mean_completed_cer=statistics.mean(r['cer'] for r in completed) if completed else None,
            wrong_field_change_events=sum(r['wrong_field_change_events'] for r in rows),
            accepted_prediction_events=sum(e['event']=='prediction_accepted' for r in rows for e in r['events'])))
    return dict(schema=1, session=data['session'], app_version=data.get('app_version'),
                active_trial_present=data.get('active_trial') is not None, groups=result,
                limitation='Descriptive single-session metrics; no inference of independent-user accuracy. Start success is per trial, not per blink attempt. Completed-only CER excludes failures; report failure rate alongside it.')


if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('input',type=Path)
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    report=json.dumps(summarize(json.loads(args.input.read_text(encoding='utf-8-sig'))),ensure_ascii=False,indent=2)
    if args.output: args.output.write_text(report,encoding='utf-8')
    else: print(report)
