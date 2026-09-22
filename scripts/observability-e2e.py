"""Helpers for the observability acceptance: read structured logs, query the trace and log backends."""
import base64
import json
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

MARKER = 'probe'


def structured_lines(path):
    """Yield the parsed structured log lines of one process, skipping plain output."""
    for raw in pathlib.Path(path).read_text(errors='replace').splitlines():
        raw = raw.strip()
        if not raw.startswith('{'):
            continue
        try:
            yield json.loads(raw)
        except ValueError:
            continue


def trace_ids(path):
    return {line['traceId'] for line in structured_lines(path) if line.get('traceId')}


def command_trace_id(paths):
    """Print the single trace identifier that appears in every process log."""
    shared = None
    for path in paths:
        current = trace_ids(path)
        shared = current if shared is None else (shared & current)
    print(sorted(shared)[0] if shared else '')
    return 0


def command_log_fields(trace_id, paths):
    """Every process must have a line carrying that trace, and their span identifiers must differ."""
    spans = []
    for path in paths:
        matching = [line for line in structured_lines(path) if line.get('traceId') == trace_id]
        if not matching:
            print(f'{path} 没有带该链路标识的结构化行', file=sys.stderr)
            return 1
        line = matching[0]
        for field in ('spanId', 'message'):
            if not line.get(field):
                print(f'{path} 的结构化行缺少 {field}', file=sys.stderr)
                return 1
        if not line.get('service', {}).get('name'):
            print(f'{path} 的结构化行缺少服务名', file=sys.stderr)
            return 1
        spans.append({candidate['spanId'] for candidate in matching})
    for index, current in enumerate(spans):
        for other in spans[index + 1:]:
            if current & other:
                print('两个进程写出了相同的 span 标识', file=sys.stderr)
                return 1
    return 0


def fetch(url, headers=None, data=None, timeout=20):
    request = urllib.request.Request(url, data=data, headers=headers or {})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.status, response.read()


def command_trace(query_base, trace_id, expected):
    """The trace backend must return one trace whose processes cover every expected service."""
    for attempt in range(10):
        try:
            status, body = fetch(f'{query_base}/api/traces/{trace_id}')
        except urllib.error.URLError as error:
            print(f'查询追踪后端失败：{error}', file=sys.stderr)
            return 1
        if status == 200:
            payload = json.loads(body)
            traces = payload.get('data') or []
            if traces:
                names = {process.get('serviceName') for process in traces[0].get('processes', {}).values()}
                missing = set(expected) - names
                if not missing:
                    return 0
                print(f'第 {attempt + 1} 次查询缺少 {sorted(missing)}', file=sys.stderr)
        time.sleep(3)
    print('追踪后端在重试后仍未返回完整的链路', file=sys.stderr)
    return 1


def command_push_logs(loki_base, run_id, specs):
    """Push the structured lines of each process with labels the query step can select on."""
    streams = []
    for spec in specs:
        service, path = spec.split(':', 1)
        values = []
        base = time.time_ns()
        for offset, line in enumerate(structured_lines(path)):
            values.append([str(base + offset), json.dumps(line, ensure_ascii=False)])
        if not values:
            print(f'{path} 没有结构化行可推送', file=sys.stderr)
            return 1
        streams.append({'stream': {'job': 'verify-observability-e2e', 'run_id': run_id,
                                   'service_name': service}, 'values': values})
    payload = json.dumps({'streams': streams}).encode()
    try:
        status, _ = fetch(f'{loki_base}/loki/api/v1/push', {'Content-Type': 'application/json'}, payload)
    except urllib.error.HTTPError as error:
        print(f'推送日志失败：{error.code} {error.read()[:200]}', file=sys.stderr)
        return 1
    return 0 if status in (200, 204) else 1


def command_query_logs(loki_base, run_id, trace_id, expected):
    """Selecting by the trace identifier must return the lines of every expected service."""
    query = '{job="verify-observability-e2e", run_id="%s"} | json | traceId="%s"' % (run_id, trace_id)
    url = f'{loki_base}/loki/api/v1/query_range?' + urllib.parse.urlencode({
        'query': query, 'limit': '500', 'since': '10m'})
    for _ in range(10):
        try:
            status, body = fetch(url)
        except urllib.error.URLError as error:
            print(f'查询日志后端失败：{error}', file=sys.stderr)
            return 1
        if status == 200:
            result = json.loads(body).get('data', {}).get('result', [])
            names = {stream.get('stream', {}).get('service_name') for stream in result}
            if not set(expected) - names:
                return 0
        time.sleep(3)
    print('日志后端按链路标识查不回全部服务', file=sys.stderr)
    return 1


def command_applications(monitor_base, user, password, expected):
    """The monitor must list every expected application with an UP status."""
    token = base64.b64encode(f'{user}:{password}'.encode()).decode()
    headers = {'Authorization': 'Basic ' + token, 'Accept': 'application/json'}
    for _ in range(20):
        try:
            status, body = fetch(f'{monitor_base}/applications', headers)
        except urllib.error.URLError as error:
            print(f'读取监控面板失败：{error}', file=sys.stderr)
            return 1
        if status == 200:
            applications = json.loads(body)
            up = {item.get('name') for item in applications
                  if item.get('status') == 'UP'}
            if not set(expected) - up:
                return 0
        time.sleep(3)
    print('监控面板没有列出全部实例', file=sys.stderr)
    return 1


COMMANDS = {
    'trace-id': lambda args: command_trace_id(args),
    'log-fields': lambda args: command_log_fields(args[0], args[1:]),
    'trace': lambda args: command_trace(args[0], args[1], args[2:]),
    'push-logs': lambda args: command_push_logs(args[0], args[1], args[2:]),
    'query-logs': lambda args: command_query_logs(args[0], args[1], args[2], args[3:]),
    'applications': lambda args: command_applications(args[0], args[1], args[2], args[3:]),
}

if __name__ == '__main__':
    name, rest = sys.argv[1], sys.argv[2:]
    raise SystemExit(COMMANDS[name](rest))
